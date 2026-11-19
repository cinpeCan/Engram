"""SQLite WAL persistence layer for Engram multi-device memory synchronization and quota ledger."""

from __future__ import annotations

import json
import os
import sqlite3
import time
from pathlib import Path

from .schemas import SyncMemoryItem

_DEFAULT_DB_PATH = Path(__file__).resolve().parents[1] / "data" / "engram_sync.db"


def _resolve_db_path() -> Path:
    configured = os.getenv("ENGRAM_DB_PATH", "").strip()
    path = Path(configured) if configured else _DEFAULT_DB_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def _ensure_schema(conn: sqlite3.Connection) -> None:
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS synced_memories (
            sync_key TEXT NOT NULL,
            id TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            payload_json TEXT NOT NULL,
            PRIMARY KEY (sync_key, id)
        );
        """
    )
    conn.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_synced_memories_key_created
        ON synced_memories (sync_key, created_at DESC);
        """
    )
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS demo_quota_ledger (
            tenant_id TEXT NOT NULL,
            month_bucket TEXT NOT NULL,
            request_count INTEGER NOT NULL DEFAULT 0,
            estimated_usd REAL NOT NULL DEFAULT 0.0,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (tenant_id, month_bucket)
        );
        """
    )


def _connect(*, isolation_level: str | None = "") -> sqlite3.Connection:
    conn = sqlite3.connect(_resolve_db_path(), timeout=15.0, isolation_level=isolation_level)
    _ensure_schema(conn)
    return conn


def sync_workspace(sync_key: str, incoming: list[SyncMemoryItem]) -> tuple[int, list[SyncMemoryItem]]:
    """Merge incoming device records via timestamp-ordered upsert and return full workspace state."""
    upserted_count = 0
    conn = _connect()
    try:
        with conn:
            for item in incoming:
                row = conn.execute(
                    "SELECT updated_at, payload_json FROM synced_memories WHERE sync_key = ? AND id = ?",
                    (sync_key, item.id),
                ).fetchone()
                should_write = False
                if row is None:
                    should_write = True
                else:
                    existing_updated = int(row[0])
                    if item.updatedAtMillis > existing_updated:
                        should_write = True
                    elif item.updatedAtMillis == existing_updated:
                        existing_payload = json.loads(row[1])
                        if item.analysisStatus == "READY" and existing_payload.get("analysisStatus") != "READY":
                            should_write = True

                if should_write:
                    conn.execute(
                        """
                        INSERT INTO synced_memories (sync_key, id, created_at, updated_at, payload_json)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(sync_key, id) DO UPDATE SET
                            created_at = excluded.created_at,
                            updated_at = excluded.updated_at,
                            payload_json = excluded.payload_json
                        """,
                        (
                            sync_key,
                            item.id,
                            item.createdAtMillis,
                            item.updatedAtMillis,
                            item.model_dump_json(),
                        ),
                    )
                    upserted_count += 1

            rows = conn.execute(
                "SELECT payload_json FROM synced_memories WHERE sync_key = ? ORDER BY created_at DESC",
                (sync_key,),
            ).fetchall()
    finally:
        conn.close()

    merged = [SyncMemoryItem.model_validate_json(row[0]) for row in rows]
    return upserted_count, merged


def try_acquire_demo_quota(
    tenant_id: str,
    max_requests_per_token: int,
    monthly_hard_cap_usd: float,
    estimated_cost_usd: float,
    month_bucket: str | None = None,
) -> tuple[bool, str, str]:
    """Atomically reserve one request slot under per-token and global monthly spend caps."""
    bucket = month_bucket or time.strftime("%Y-%m", time.gmtime())
    now_ms = int(time.time() * 1000)
    conn = _connect(isolation_level=None)
    try:
        conn.execute("BEGIN IMMEDIATE")
        tenant_row = conn.execute(
            "SELECT COALESCE(SUM(request_count), 0) FROM demo_quota_ledger WHERE tenant_id = ?",
            (tenant_id,),
        ).fetchone()
        tenant_total = int(tenant_row[0]) if tenant_row else 0
        if max_requests_per_token <= 0 or tenant_total >= max_requests_per_token:
            conn.execute("ROLLBACK")
            return False, "Demo token request limit reached", bucket

        monthly_row = conn.execute(
            "SELECT COALESCE(SUM(estimated_usd), 0.0) FROM demo_quota_ledger WHERE month_bucket = ?",
            (bucket,),
        ).fetchone()
        monthly_usd = float(monthly_row[0]) if monthly_row else 0.0
        if monthly_hard_cap_usd <= 0.0 or monthly_usd + estimated_cost_usd > monthly_hard_cap_usd + 1e-12:
            conn.execute("ROLLBACK")
            return False, "Monthly cloud spend cap reached", bucket

        conn.execute(
            """
            INSERT INTO demo_quota_ledger (tenant_id, month_bucket, request_count, estimated_usd, updated_at)
            VALUES (?, ?, 1, ?, ?)
            ON CONFLICT(tenant_id, month_bucket) DO UPDATE SET
                request_count = demo_quota_ledger.request_count + 1,
                estimated_usd = demo_quota_ledger.estimated_usd + excluded.estimated_usd,
                updated_at = excluded.updated_at
            """,
            (tenant_id, bucket, estimated_cost_usd, now_ms),
        )
        conn.execute("COMMIT")
        return True, "", bucket
    except Exception:
        try:
            conn.execute("ROLLBACK")
        except sqlite3.Error:
            pass
        raise
    finally:
        conn.close()


def refund_demo_quota(
    tenant_id: str,
    month_bucket: str,
    estimated_cost_usd: float,
) -> None:
    """Refund a reserved request slot when upstream stream establishment fails before first event."""
    now_ms = int(time.time() * 1000)
    conn = _connect(isolation_level=None)
    try:
        conn.execute("BEGIN IMMEDIATE")
        conn.execute(
            """
            UPDATE demo_quota_ledger
            SET request_count = MAX(0, request_count - 1),
                estimated_usd = MAX(0.0, estimated_usd - ?),
                updated_at = ?
            WHERE tenant_id = ? AND month_bucket = ?
            """,
            (estimated_cost_usd, now_ms, tenant_id, month_bucket),
        )
        conn.execute("COMMIT")
    except Exception:
        try:
            conn.execute("ROLLBACK")
        except sqlite3.Error:
            pass
        raise
    finally:
        conn.close()


def get_demo_quota_status(
    tenant_id: str,
    max_requests_per_token: int,
    monthly_hard_cap_usd: float,
    estimated_cost_usd: float,
    month_bucket: str | None = None,
) -> tuple[bool, int]:
    """Read-only check of remaining token quota and global monthly spend cap."""
    bucket = month_bucket or time.strftime("%Y-%m", time.gmtime())
    conn = _connect()
    try:
        tenant_row = conn.execute(
            "SELECT COALESCE(SUM(request_count), 0) FROM demo_quota_ledger WHERE tenant_id = ?",
            (tenant_id,),
        ).fetchone()
        tenant_total = int(tenant_row[0]) if tenant_row else 0
        remaining = max(0, max_requests_per_token - tenant_total)
        if remaining <= 0:
            return False, 0

        monthly_row = conn.execute(
            "SELECT COALESCE(SUM(estimated_usd), 0.0) FROM demo_quota_ledger WHERE month_bucket = ?",
            (bucket,),
        ).fetchone()
        monthly_usd = float(monthly_row[0]) if monthly_row else 0.0
        if monthly_hard_cap_usd <= 0.0 or monthly_usd + estimated_cost_usd > monthly_hard_cap_usd + 1e-12:
            return False, 0

        return True, remaining
    finally:
        conn.close()


def delete_synced_memories(sync_key: str, deleted_ids: list[str]) -> int:
    """Delete specified memory ids from the workspace and return removed row count."""
    cleaned_ids = [mid.strip() for mid in deleted_ids if mid and mid.strip()][:500]
    if not cleaned_ids:
        return 0
    conn = _connect()
    try:
        with conn:
            placeholders = ",".join("?" for _ in cleaned_ids)
            cursor = conn.execute(
                f"DELETE FROM synced_memories WHERE sync_key = ? AND id IN ({placeholders})",
                [sync_key, *cleaned_ids],
            )
            return max(0, cursor.rowcount)
    finally:
        conn.close()
