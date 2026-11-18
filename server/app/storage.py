"""SQLite WAL persistence layer for Engram multi-device memory synchronization."""

from __future__ import annotations

import json
import os
import sqlite3
from pathlib import Path

from .schemas import SyncMemoryItem

_DEFAULT_DB_PATH = Path(__file__).resolve().parents[1] / "data" / "engram_sync.db"


def _resolve_db_path() -> Path:
    configured = os.getenv("ENGRAM_DB_PATH", "").strip()
    path = Path(configured) if configured else _DEFAULT_DB_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def _connect() -> sqlite3.Connection:
    conn = sqlite3.connect(_resolve_db_path(), timeout=15.0)
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
    return conn


def sync_workspace(sync_key: str, incoming: list[SyncMemoryItem]) -> tuple[int, list[SyncMemoryItem]]:
    """Merge incoming device records via timestamp-ordered upsert and return full workspace state."""
    upserted_count = 0
    with _connect() as conn:
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
        conn.commit()

    merged = [SyncMemoryItem.model_validate_json(row[0]) for row in rows]
    return upserted_count, merged
