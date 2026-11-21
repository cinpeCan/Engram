"""Contract tests verifying FastAPI payloads conform to root contracts/*.schema.json."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path
from types import SimpleNamespace
from typing import AsyncIterator

import httpx
import jsonschema
import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.tokens import mint_demo_token, verify_demo_token

ROOT_DIR = Path(__file__).resolve().parents[2]
CONTRACTS_DIR = ROOT_DIR / "contracts"

client = TestClient(app)


def _load_schema(filename: str) -> dict:
    return json.loads((CONTRACTS_DIR / filename).read_text(encoding="utf-8"))


def test_health_endpoint() -> None:
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_healthz_endpoint() -> None:
    resp = client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}


def test_multi_device_sync_merges_by_timestamp_and_isolates_sync_keys(tmp_path, monkeypatch) -> None:
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    db_path = tmp_path / "sync_test.db"
    monkeypatch.setenv("ENGRAM_DB_PATH", str(db_path))

    sync_schema = _load_schema("memory-sync.schema.json")
    req_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sync_schema["$schema"],
            "$defs": sync_schema["$defs"],
            "$ref": "#/$defs/MemorySyncRequest",
        }
    )
    resp_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sync_schema["$schema"],
            "$defs": sync_schema["$defs"],
            "$ref": "#/$defs/MemorySyncResponse",
        }
    )

    device_a_req = {
        "syncKey": "workspace-alpha",
        "memories": [
            {
                "id": "mem_1",
                "content": "Tomorrow 3pm at Software Park Building B, align the contract with Ms. Zhang.",
                "summary": "• Tomorrow 15:00 · Software Park B\n• Align contract with Ms. Zhang",
                "tags": ["contract", "reminder"],
                "createdAtMillis": 1700000000000,
                "updatedAtMillis": 1700000001000,
                "analysisStatus": "READY",
                "calendarProposal": {
                    "title": "Contract alignment",
                    "startTimeIso": "2026-11-19T15:00:00",
                    "durationMinutes": 60,
                    "location": "Software Park Building B",
                },
                "calendarSaved": False,
                "analysisSource": "MNN_EDGE",
                "telemetryBadge": "MNN 0.5B · TTFT 120ms · 32.0 tok/s",
                "relations": [
                    {
                        "source": "Ms. Zhang",
                        "target": "Contract",
                        "relation": "aligns terms",
                        "bidirectional": False,
                    }
                ],
            }
        ],
    }
    req_validator.validate(device_a_req)
    resp_a = client.post("/api/v1/memories/sync", json=device_a_req)
    assert resp_a.status_code == 200
    body_a = resp_a.json()
    resp_validator.validate(body_a)
    assert body_a["upsertedCount"] == 1
    assert body_a["totalCount"] == 1

    # Device B brings stale version of mem_1 (older updatedAtMillis) and a new mem_2
    device_b_req = {
        "syncKey": "workspace-alpha",
        "memories": [
            {
                "id": "mem_1",
                "content": "Tomorrow 3pm at Software Park Building B, align the contract with Ms. Zhang.",
                "summary": "Stale summary must not overwrite",
                "tags": [],
                "createdAtMillis": 1700000000000,
                "updatedAtMillis": 1700000000500,
                "analysisStatus": "PENDING",
                "calendarProposal": None,
                "calendarSaved": False,
                "analysisSource": "LOCAL_RULE_FALLBACK",
                "telemetryBadge": "",
                "relations": [],
            },
            {
                "id": "mem_2",
                "content": "Chen submits the burn-in acceptance report",
                "summary": "Chen submits the burn-in acceptance report",
                "tags": ["project"],
                "createdAtMillis": 1700000005000,
                "updatedAtMillis": 1700000005000,
                "analysisStatus": "READY",
                "calendarProposal": None,
                "calendarSaved": False,
                "analysisSource": "CLOUD_SSE",
                "telemetryBadge": "Cloud SSE · TTFT 95ms · 60.0 tok/s",
                "relations": [],
            },
        ],
    }
    req_validator.validate(device_b_req)
    resp_b = client.post("/api/v1/memories/sync", json=device_b_req)
    assert resp_b.status_code == 200
    body_b = resp_b.json()
    resp_validator.validate(body_b)
    assert body_b["upsertedCount"] == 1
    assert body_b["totalCount"] == 2
    by_id = {m["id"]: m for m in body_b["memories"]}
    assert by_id["mem_1"]["analysisStatus"] == "READY"
    assert by_id["mem_1"]["telemetryBadge"].startswith("MNN 0.5B")
    assert by_id["mem_2"]["analysisSource"] == "CLOUD_SSE"

    # Separate syncKey stays isolated
    other_resp = client.post(
        "/api/v1/memories/sync",
        json={"syncKey": "workspace-beta", "memories": []},
    )
    assert other_resp.status_code == 200
    assert other_resp.json()["totalCount"] == 0


def test_analyze_stream_returns_503_when_api_key_missing(monkeypatch) -> None:
    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    monkeypatch.delenv("ENGRAM_CLOUD_API_KEY", raising=False)
    resp = client.post(
        "/api/v1/analyze/stream",
        json={
            "content": "Tomorrow 3pm, align the flexible display purchase contract with Ms. Zhang.",
            "nowIso": "2026-11-18T09:00:00",
            "model": "gemini-3.5-flash-lite",
        },
    )
    assert resp.status_code == 503


def test_analyze_stream_sse_matches_contract_and_analysis_schema(monkeypatch) -> None:
    analysis_payload = {
        "summary": "• Tomorrow 15:00 · Software Park III, B7 meeting room\n• Review contract addendum with legal (Ms. Zhang)",
        "tags": ["legal", "follow-up"],
        "calendarProposal": {
            "title": "Flexible display contract addendum review",
            "startTimeIso": "2026-11-19T15:00:00",
            "durationMinutes": 60,
            "location": "Software Park III, B7 meeting room",
        },
        "relations": [
            {
                "source": "Ms. Zhang (legal)",
                "target": "Purchase contract",
                "relation": "reviews addendum",
                "bidirectional": False,
            }
        ],
    }
    serialized = json.dumps(analysis_payload, ensure_ascii=False)
    midpoint = len(serialized) // 2
    upstream_lines = [
        f"data: {json.dumps({'choices': [{'delta': {'content': serialized[:midpoint]}}]})}",
        f"data: {json.dumps({'choices': [{'delta': {'content': serialized[midpoint:]}}]})}",
        "data: [DONE]",
    ]

    class MockStreamResponse:
        def raise_for_status(self) -> None:
            return None

        async def aiter_lines(self) -> AsyncIterator[str]:
            for line in upstream_lines:
                yield line

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb) -> None:
            return None

    class MockAsyncClient:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb) -> None:
            return None

        def stream(self, *args, **kwargs):
            return MockStreamResponse()

    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    monkeypatch.setenv("ENGRAM_CLOUD_API_KEY", "sk-test-valid")
    monkeypatch.setenv("ENGRAM_CLOUD_BASE_URL", "https://example.invalid/v1")
    monkeypatch.setenv("ENGRAM_CLOUD_MODEL", "gemini-3.5-flash-lite")
    monkeypatch.setattr("app.main.httpx.AsyncClient", MockAsyncClient)

    sse_schema = _load_schema("analysis-sse.schema.json")
    analysis_schema = _load_schema("memory-analysis.schema.json")

    req_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sse_schema["$schema"],
            "$defs": sse_schema["$defs"],
            "$ref": "#/$defs/AnalysisStreamRequest",
        }
    )
    chunk_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sse_schema["$schema"],
            "$defs": sse_schema["$defs"],
            "$ref": "#/$defs/AnalysisStreamChunk",
        }
    )

    request_body = {
        "content": "Tomorrow 15:00 in Software Park III B7 meeting room, review the flexible display contract addendum with Ms. Zhang from legal.",
        "nowIso": "2026-11-18T09:00:00",
        "model": "gemini-3.5-flash-lite",
    }
    req_validator.validate(request_body)

    chunks = []
    saw_done_marker = False
    with client.stream("POST", "/api/v1/analyze/stream", json=request_body) as resp:
        assert resp.status_code == 200
        for line in resp.iter_lines():
            if not line or not line.startswith("data:"):
                continue
            raw = line.removeprefix("data:").strip()
            if raw == "[DONE]":
                saw_done_marker = True
                continue
            chunk_obj = json.loads(raw)
            chunk_validator.validate(chunk_obj)
            chunks.append(chunk_obj)

    assert saw_done_marker
    assert len(chunks) == 3
    assert chunks[-1]["done"] is True
    assert chunks[-1]["error"] is None

    combined_json = json.loads("".join(c["delta"] for c in chunks))
    jsonschema.validate(instance=combined_json, schema=analysis_schema)


def test_analyze_stream_emits_error_chunk_when_upstream_fails(monkeypatch) -> None:
    class FailingAsyncClient:
        def __init__(self, *args, **kwargs) -> None:
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb) -> None:
            return None

        def stream(self, *args, **kwargs):
            raise httpx.ConnectError("upstream unreachable")

    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    monkeypatch.setenv("ENGRAM_CLOUD_API_KEY", "sk-test-dummy")
    monkeypatch.setenv("ENGRAM_CLOUD_BASE_URL", "https://example.invalid/v1")
    monkeypatch.setattr("app.main.httpx.AsyncClient", FailingAsyncClient)

    sse_schema = _load_schema("analysis-sse.schema.json")
    chunk_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sse_schema["$schema"],
            "$defs": sse_schema["$defs"],
            "$ref": "#/$defs/AnalysisStreamChunk",
        }
    )

    request_body = {
        "content": "Contract is blocked on term alignment with legal.",
        "nowIso": "2026-11-18T09:00:00",
        "model": "gemini-3.5-flash-lite",
    }

    chunks = []
    saw_done_marker = False
    with client.stream("POST", "/api/v1/analyze/stream", json=request_body) as resp:
        assert resp.status_code == 200
        for line in resp.iter_lines():
            if not line or not line.startswith("data:"):
                continue
            raw = line.removeprefix("data:").strip()
            if raw == "[DONE]":
                saw_done_marker = True
                continue
            chunk_obj = json.loads(raw)
            chunk_validator.validate(chunk_obj)
            chunks.append(chunk_obj)

    assert saw_done_marker
    assert len(chunks) == 1
    assert chunks[0]["done"] is True
    assert chunks[0]["delta"] == ""
    assert chunks[0]["error"] == "Upstream request failed"


def test_vertex_ai_stream_distills_sse_truncates_input_and_handles_errors(monkeypatch) -> None:
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    monkeypatch.delenv("GOOGLE_CLOUD_LOCATION", raising=False)
    monkeypatch.delenv("ENGRAM_VERTEX_MODEL", raising=False)
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-gcp-project")

    analysis_payload = {
        "summary": "• Tomorrow 15:00 · Software Park B\n• Align contract terms with Ms. Zhang",
        "tags": ["contract", "follow-up"],
        "calendarProposal": None,
        "relations": [
            {
                "source": "Ms. Zhang",
                "target": "Contract terms",
                "relation": "aligns",
                "bidirectional": True,
            }
        ],
    }
    serialized = json.dumps(analysis_payload, ensure_ascii=False)
    midpoint = len(serialized) // 2
    captured_calls: list[dict] = []

    async def fake_event_stream():
        yield SimpleNamespace(event_type="interaction.created")
        yield SimpleNamespace(event_type="step.start", index=0)
        yield SimpleNamespace(
            event_type="step.delta",
            delta=SimpleNamespace(type="thought_summary", text="ignored thought"),
        )
        yield SimpleNamespace(
            event_type="step.delta",
            delta=SimpleNamespace(type="text", text=serialized[:midpoint]),
        )
        yield SimpleNamespace(
            event_type="step.delta",
            delta=SimpleNamespace(type="text", text=serialized[midpoint:]),
        )
        yield SimpleNamespace(event_type="step.stop", index=0)
        yield SimpleNamespace(event_type="interaction.completed")

    class FakeInteractions:
        async def create(self, **kwargs):
            captured_calls.append(kwargs)
            return fake_event_stream()

    class FakeVertexClient:
        def __init__(self) -> None:
            self.aio = SimpleNamespace(interactions=FakeInteractions())

    init_args: list[tuple[str, str]] = []

    def fake_factory(project: str, location: str):
        init_args.append((project, location))
        return FakeVertexClient()

    monkeypatch.setattr("app.main._create_vertex_client", fake_factory)

    sse_schema = _load_schema("analysis-sse.schema.json")
    analysis_schema = _load_schema("memory-analysis.schema.json")
    chunk_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sse_schema["$schema"],
            "$defs": sse_schema["$defs"],
            "$ref": "#/$defs/AnalysisStreamChunk",
        }
    )

    long_content = "n" * 4500
    chunks = []
    saw_done_marker = False
    with client.stream(
        "POST",
        "/api/v1/analyze/stream",
        json={
            "content": long_content,
            "nowIso": "2026-11-20T09:00:00",
            "model": "client-requested-ignored",
        },
    ) as resp:
        assert resp.status_code == 200
        for line in resp.iter_lines():
            if not line or not line.startswith("data:"):
                continue
            raw = line.removeprefix("data:").strip()
            if raw == "[DONE]":
                saw_done_marker = True
                continue
            chunk_obj = json.loads(raw)
            chunk_validator.validate(chunk_obj)
            chunks.append(chunk_obj)

    assert saw_done_marker
    assert init_args == [("test-gcp-project", "global")]
    assert len(captured_calls) == 1
    call_kwargs = captured_calls[0]
    assert call_kwargs["model"] == "gemini-3.5-flash-lite"
    assert call_kwargs["stream"] is True
    assert call_kwargs["store"] is False
    assert call_kwargs["generation_config"]["max_output_tokens"] == 1024
    assert len(call_kwargs["input"].split("\nNote: ", 1)[1]) == 4000
    assert all(c["model"] == "gemini-3.5-flash-lite" for c in chunks)
    combined_json = json.loads("".join(c["delta"] for c in chunks))
    jsonschema.validate(instance=combined_json, schema=analysis_schema)


def test_token_cli_mint_verify_401_and_tenant_isolation(tmp_path, monkeypatch) -> None:
    secret = "test-hmac-signing-secret"
    db_path = tmp_path / "tenant_isolation.db"
    monkeypatch.setenv("ENGRAM_DB_PATH", str(db_path))
    monkeypatch.setenv("ENGRAM_DEMO_SIGNING_SECRET", secret)
    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    monkeypatch.setenv("ENGRAM_CLOUD_API_KEY", "sk-test-unused")
    monkeypatch.setenv("ENGRAM_CLOUD_BASE_URL", "https://example.invalid/v1")

    server_dir = Path(__file__).resolve().parents[1]
    env = dict(os.environ)
    env["ENGRAM_DEMO_SIGNING_SECRET"] = secret
    proc = subprocess.run(
        [sys.executable, "-m", "app.tokens", "mint", "--tenant", "team01", "--days", "7"],
        cwd=str(server_dir),
        env=env,
        capture_output=True,
        text=True,
        check=True,
    )
    minted_lines = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    assert len(minted_lines) == 1
    token_team1 = minted_lines[0]
    claims_team1 = verify_demo_token(token_team1, secret=secret)
    assert claims_team1.tenant_id == "team01"

    token_team2 = mint_demo_token(tenant_id="team02", days=7, secret=secret)
    expired_token = mint_demo_token(
        tenant_id="team01",
        days=1,
        secret=secret,
        now_epoch=int(time.time()) - 10 * 86400,
    )
    parts = token_team1.split("_")
    tampered_token = f"{parts[0]}_{parts[1]}_{parts[2]}_{'0' * 24}"

    # Unauthenticated, tampered, and expired tokens all return 401 on both endpoints
    for bad_headers in [
        {},
        {"Authorization": f"Bearer {tampered_token}"},
        {"Authorization": f"Bearer {expired_token}"},
    ]:
        sync_401 = client.post(
            "/api/v1/memories/sync",
            headers=bad_headers,
            json={"syncKey": "engram-demo", "memories": []},
        )
        assert sync_401.status_code == 401

        analyze_401 = client.post(
            "/api/v1/analyze/stream",
            headers=bad_headers,
            json={
                "content": "Unauthorized request should be rejected",
                "nowIso": "2026-11-20T09:00:00",
                "model": "gemini-3.5-flash-lite",
            },
        )
        assert analyze_401.status_code == 401

    # Different tenants sharing the same syncKey="engram-demo" remain strictly isolated
    sample_memory_1 = {
        "id": "mem_t1",
        "content": "Private note of team member A",
        "summary": "Private note of team member A",
        "tags": [],
        "createdAtMillis": 1700000000000,
        "updatedAtMillis": 1700000001000,
        "analysisStatus": "READY",
        "calendarProposal": None,
        "calendarSaved": False,
        "analysisSource": "MNN_EDGE",
        "telemetryBadge": "",
        "relations": [],
    }
    resp_t1 = client.post(
        "/api/v1/memories/sync",
        headers={"Authorization": f"Bearer {token_team1}"},
        json={"syncKey": "engram-demo", "memories": [sample_memory_1]},
    )
    assert resp_t1.status_code == 200
    assert [m["id"] for m in resp_t1.json()["memories"]] == ["mem_t1"]

    resp_t2 = client.post(
        "/api/v1/memories/sync",
        headers={"Authorization": f"Bearer {token_team2}"},
        json={"syncKey": "engram-demo", "memories": []},
    )
    assert resp_t2.status_code == 200
    assert resp_t2.json()["totalCount"] == 0
    assert resp_t2.json()["memories"] == []


def test_quota_cap_refund_on_failed_stream_and_monthly_hard_cap(tmp_path, monkeypatch) -> None:
    secret = "test-quota-secret"
    db_path = tmp_path / "quota_test.db"
    monkeypatch.setenv("ENGRAM_DB_PATH", str(db_path))
    monkeypatch.setenv("ENGRAM_DEMO_SIGNING_SECRET", secret)
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-gcp-project")
    monkeypatch.setenv("ENGRAM_DEMO_MAX_REQUESTS_PER_TOKEN", "2")
    monkeypatch.setenv("ENGRAM_MONTHLY_HARD_CAP_USD", "1.0")
    monkeypatch.setenv("ENGRAM_ESTIMATED_COST_PER_REQ_USD", "0.35")

    token_a = mint_demo_token("tenant-a", days=7, secret=secret)
    token_b = mint_demo_token("tenant-b", days=7, secret=secret)

    should_fail = True

    class FlakyOrOkInteractions:
        async def create(self, **kwargs):
            if should_fail:
                raise RuntimeError("Vertex handshake failed")

            async def ok_stream():
                yield SimpleNamespace(
                    event_type="step.delta",
                    delta=SimpleNamespace(type="text", text="{}"),
                )

            return ok_stream()

    monkeypatch.setattr(
        "app.main._create_vertex_client",
        lambda project, location: SimpleNamespace(aio=SimpleNamespace(interactions=FlakyOrOkInteractions())),
    )

    payload = {
        "content": "Quota counting and refund test",
        "nowIso": "2026-11-20T09:00:00",
        "model": "gemini-3.5-flash-lite",
    }

    # Failed stream establishment before first event refunds quota (only established streams are counted)
    sse_schema = _load_schema("analysis-sse.schema.json")
    chunk_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sse_schema["$schema"],
            "$defs": sse_schema["$defs"],
            "$ref": "#/$defs/AnalysisStreamChunk",
        }
    )
    with client.stream(
        "POST",
        "/api/v1/analyze/stream",
        headers={"Authorization": f"Bearer {token_a}"},
        json=payload,
    ) as fail_resp:
        assert fail_resp.status_code == 200
        body_lines = [line for line in fail_resp.iter_lines() if line.startswith("data:")]
        chunk_objs = [
            json.loads(line.removeprefix("data:").strip())
            for line in body_lines
            if line.removeprefix("data:").strip() != "[DONE]"
        ]
        assert len(chunk_objs) == 1
        chunk_validator.validate(chunk_objs[0])
        assert chunk_objs[0]["done"] is True
        assert chunk_objs[0]["error"] == "Upstream request failed"

    # Now succeed twice for tenant-a (should consume 2/2 slots -> $0.70 spent)
    should_fail = False
    for _ in range(2):
        with client.stream(
            "POST",
            "/api/v1/analyze/stream",
            headers={"Authorization": f"Bearer {token_a}"},
            json=payload,
        ) as ok_resp:
            assert ok_resp.status_code == 200
            _ = list(ok_resp.iter_lines())

    # 3rd request for tenant-a exceeds per-token cap (2) -> HTTP 429
    over_token_resp = client.post(
        "/api/v1/analyze/stream",
        headers={"Authorization": f"Bearer {token_a}"},
        json=payload,
    )
    assert over_token_resp.status_code == 429

    # Global monthly hard cap across tenants: tenant-b's first request ($0.70 + $0.35 > $1.00) -> HTTP 429
    over_monthly_resp = client.post(
        "/api/v1/analyze/stream",
        headers={"Authorization": f"Bearer {token_b}"},
        json=payload,
    )
    assert over_monthly_resp.status_code == 429
    assert "Monthly" in over_monthly_resp.json()["detail"]


def test_auth_status_probe_is_zero_cost_and_delete_is_tenant_isolated(tmp_path, monkeypatch) -> None:
    db_path = tmp_path / "auth_and_delete.db"
    monkeypatch.setenv("ENGRAM_DB_PATH", str(db_path))
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)

    sync_schema = _load_schema("memory-sync.schema.json")
    auth_status_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sync_schema["$schema"],
            "$defs": sync_schema["$defs"],
            "$ref": "#/$defs/AuthStatusResponse",
        }
    )
    delete_req_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sync_schema["$schema"],
            "$defs": sync_schema["$defs"],
            "$ref": "#/$defs/MemoryDeleteRequest",
        }
    )
    delete_resp_validator = jsonschema.Draft202012Validator(
        {
            "$schema": sync_schema["$schema"],
            "$defs": sync_schema["$defs"],
            "$ref": "#/$defs/MemoryDeleteResponse",
        }
    )

    # Open mode returns 200 without Bearer token
    open_resp = client.get("/api/v1/auth/status")
    assert open_resp.status_code == 200
    open_json = open_resp.json()
    auth_status_validator.validate(open_json)
    assert open_json == {"status": "ok", "mode": "open", "remainingQuota": None}

    # Demo mode checks token without decrementing quota, and rejects invalid/exhausted tokens
    secret = "test-auth-status-secret"
    monkeypatch.setenv("ENGRAM_DEMO_SIGNING_SECRET", secret)
    monkeypatch.setenv("ENGRAM_DEMO_MAX_REQUESTS_PER_TOKEN", "2")
    monkeypatch.setenv("ENGRAM_MONTHLY_HARD_CAP_USD", "5.0")
    monkeypatch.setenv("ENGRAM_ESTIMATED_COST_PER_REQ_USD", "0.0004")

    token_t1 = mint_demo_token("team01", days=7, secret=secret)
    token_t2 = mint_demo_token("team02", days=7, secret=secret)

    assert client.get("/api/v1/auth/status").status_code == 401
    for _ in range(3):
        status_ok = client.get(
            "/api/v1/auth/status",
            headers={"Authorization": f"Bearer {token_t1}"},
        )
        assert status_ok.status_code == 200
        status_json = status_ok.json()
        auth_status_validator.validate(status_json)
        # Repeated probes must not consume quota
        assert status_json == {"status": "ok", "mode": "demo", "remainingQuota": 2}

    # Seed memories for team01 and team02 under same syncKey
    def make_item(mid: str) -> dict:
        return {
            "id": mid,
            "content": f"note {mid}",
            "summary": f"summary {mid}",
            "tags": [],
            "createdAtMillis": 1700000000000,
            "updatedAtMillis": 1700000001000,
            "analysisStatus": "READY",
            "calendarProposal": None,
            "calendarSaved": False,
            "analysisSource": "MNN_EDGE",
            "telemetryBadge": "",
            "relations": [],
        }

    assert (
        client.post(
            "/api/v1/memories/sync",
            headers={"Authorization": f"Bearer {token_t1}"},
            json={"syncKey": "engram-demo", "memories": [make_item("mem_shared_id")]},
        ).status_code
        == 200
    )
    assert (
        client.post(
            "/api/v1/memories/sync",
            headers={"Authorization": f"Bearer {token_t2}"},
            json={"syncKey": "engram-demo", "memories": [make_item("mem_shared_id")]},
        ).status_code
        == 200
    )

    # Deleting from team01 removes only team01's item and keeps team02's item intact
    del_payload = {"syncKey": "engram-demo", "deletedIds": ["mem_shared_id"]}
    delete_req_validator.validate(del_payload)
    del_resp = client.post(
        "/api/v1/memories/delete",
        headers={"Authorization": f"Bearer {token_t1}"},
        json=del_payload,
    )
    assert del_resp.status_code == 200
    del_json = del_resp.json()
    delete_resp_validator.validate(del_json)
    assert del_json["deletedCount"] == 1

    t1_after = client.post(
        "/api/v1/memories/sync",
        headers={"Authorization": f"Bearer {token_t1}"},
        json={"syncKey": "engram-demo", "memories": []},
    ).json()
    t2_after = client.post(
        "/api/v1/memories/sync",
        headers={"Authorization": f"Bearer {token_t2}"},
        json={"syncKey": "engram-demo", "memories": []},
    ).json()
    assert t1_after["totalCount"] == 0
    assert [m["id"] for m in t2_after["memories"]] == ["mem_shared_id"]

    # Exhausting quota flips /api/v1/auth/status to 429
    from app.storage import try_acquire_demo_quota

    for _ in range(2):
        ok, _, _ = try_acquire_demo_quota("team01", 2, 5.0, 0.0004)
        assert ok
    exhausted = client.get(
        "/api/v1/auth/status",
        headers={"Authorization": f"Bearer {token_t1}"},
    )
    assert exhausted.status_code == 429


# ---------------------------------------------------------------------------
# POST /api/v1/answer/stream (grounded GraphRAG "Ask")
# ---------------------------------------------------------------------------


def _sse_validator(def_name: str) -> jsonschema.Draft202012Validator:
    sse_schema = _load_schema("analysis-sse.schema.json")
    return jsonschema.Draft202012Validator(
        {
            "$schema": sse_schema["$schema"],
            "$defs": sse_schema["$defs"],
            "$ref": f"#/$defs/{def_name}",
        }
    )


def _collect_sse(resp) -> tuple[list[dict], bool]:
    chunks: list[dict] = []
    saw_done_marker = False
    for line in resp.iter_lines():
        if not line or not line.startswith("data:"):
            continue
        raw = line.removeprefix("data:").strip()
        if raw == "[DONE]":
            saw_done_marker = True
            continue
        chunks.append(json.loads(raw))
    return chunks, saw_done_marker


def _answer_request(**overrides) -> dict:
    body = {
        "question": "When is the contract review with Ms. Zhang?",
        "nowIso": "2026-11-20T09:00:00+08:00",
        "contexts": [
            {
                "id": "mem_1",
                "text": "Tomorrow 15:00 in Software Park III B7, review the contract addendum with Ms. Zhang.",
                "createdAtMillis": 1763600000000,
            },
            {
                "id": "mem_2",
                "text": "Contract is blocked on term alignment with legal.",
                "createdAtMillis": 1763500000000,
            },
        ],
        "model": "gemini-3.5-flash-lite",
    }
    body.update(overrides)
    return body


def test_answer_request_and_chunk_match_schema() -> None:
    req_validator = _sse_validator("AnswerStreamRequest")
    chunk_validator = _sse_validator("AnalysisStreamChunk")

    req_validator.validate(_answer_request())
    req_validator.validate(_answer_request(contexts=[]))
    chunk_validator.validate({"delta": "On Nov 21 at 15:00 [1].", "done": False, "model": "m", "latencyMs": 3})

    for bad in [
        _answer_request(question=""),
        _answer_request(question="q" * 501),
        _answer_request(contexts=[_answer_request()["contexts"][0]] * 9),
        _answer_request(contexts=[{"id": "x", "text": "t" * 1201, "createdAtMillis": 0}]),
        _answer_request(contexts=[{"id": "x", "text": "t", "createdAtMillis": -1}]),
        _answer_request(extra="nope"),
    ]:
        assert not req_validator.is_valid(bad)

    no_model = _answer_request()
    del no_model["model"]
    assert not req_validator.is_valid(no_model)


def test_answer_stream_requires_token_when_secret_set(tmp_path, monkeypatch) -> None:
    secret = "test-answer-secret"
    monkeypatch.setenv("ENGRAM_DB_PATH", str(tmp_path / "answer_auth.db"))
    monkeypatch.setenv("ENGRAM_DEMO_SIGNING_SECRET", secret)
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-gcp-project")

    def must_not_be_called(project: str, location: str):
        raise AssertionError("Vertex client must not be created for unauthorized requests")

    monkeypatch.setattr("app.main._create_vertex_client", must_not_be_called)

    expired_token = mint_demo_token(
        tenant_id="team01",
        days=1,
        secret=secret,
        now_epoch=int(time.time()) - 10 * 86400,
    )
    for bad_headers in [{}, {"Authorization": f"Bearer {expired_token}"}, {"Authorization": "Bearer garbage"}]:
        resp = client.post("/api/v1/answer/stream", headers=bad_headers, json=_answer_request())
        assert resp.status_code == 401


def test_answer_stream_vertex_streams_cited_text_and_builds_grounded_prompt(monkeypatch) -> None:
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    monkeypatch.delenv("GOOGLE_CLOUD_LOCATION", raising=False)
    monkeypatch.delenv("ENGRAM_VERTEX_MODEL", raising=False)
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-gcp-project")

    answer_parts = ["The review is tomorrow ", "(Nov 21) at 15:00 in Software Park III B7 [1]."]
    captured_calls: list[dict] = []

    async def fake_event_stream():
        yield SimpleNamespace(event_type="interaction.created")
        yield SimpleNamespace(
            event_type="step.delta",
            delta=SimpleNamespace(type="thought_summary", text="ignored thought"),
        )
        for part in answer_parts:
            yield SimpleNamespace(event_type="step.delta", delta=SimpleNamespace(type="text", text=part))
        yield SimpleNamespace(event_type="interaction.completed")

    class FakeInteractions:
        async def create(self, **kwargs):
            captured_calls.append(kwargs)
            return fake_event_stream()

    monkeypatch.setattr(
        "app.main._create_vertex_client",
        lambda project, location: SimpleNamespace(aio=SimpleNamespace(interactions=FakeInteractions())),
    )

    chunk_validator = _sse_validator("AnalysisStreamChunk")
    long_context = {"id": "mem_long", "text": "x" * 1200, "createdAtMillis": 1763400000000}
    request_body = _answer_request(
        contexts=_answer_request()["contexts"] + [long_context] * 6,
        model="client-requested-ignored",
    )
    _sse_validator("AnswerStreamRequest").validate(request_body)

    with client.stream("POST", "/api/v1/answer/stream", json=request_body) as resp:
        assert resp.status_code == 200
        assert resp.headers["content-type"].startswith("text/event-stream")
        chunks, saw_done_marker = _collect_sse(resp)

    assert saw_done_marker
    for chunk in chunks:
        chunk_validator.validate(chunk)
    assert len(chunks) == len(answer_parts) + 1
    assert chunks[-1]["done"] is True and chunks[-1]["error"] is None
    assert all(c["model"] == "gemini-3.5-flash-lite" for c in chunks)
    answer = "".join(c["delta"] for c in chunks)
    assert "[1]" in answer

    assert len(captured_calls) == 1
    call_kwargs = captured_calls[0]
    assert call_kwargs["model"] == "gemini-3.5-flash-lite"
    assert call_kwargs["stream"] is True
    assert call_kwargs["store"] is False
    assert call_kwargs["generation_config"]["max_output_tokens"] == 1024
    assert "ONLY" in call_kwargs["system_instruction"]
    assert "[1], [2]" in call_kwargs["system_instruction"]

    prompt = call_kwargs["input"]
    assert prompt.startswith("Current local time: 2026-11-20T09:00:00+08:00\n")
    assert "[1] (2025-11-20) Tomorrow 15:00 in Software Park III B7" in prompt
    assert "[2] (2025-11-19) Contract is blocked" in prompt
    assert prompt.rstrip().endswith("Question: When is the contract review with Ms. Zhang?")
    snippet_block = prompt.split("Memory snippets:\n", 1)[1].split("\n\nQuestion:", 1)[0]
    snippet_text_chars = sum(len(line.split(") ", 1)[1]) for line in snippet_block.splitlines())
    assert snippet_text_chars <= 6000
    assert "[3] " in snippet_block


def test_answer_stream_counts_quota_and_refunds_on_pre_stream_failure(tmp_path, monkeypatch) -> None:
    secret = "test-answer-quota-secret"
    monkeypatch.setenv("ENGRAM_DB_PATH", str(tmp_path / "answer_quota.db"))
    monkeypatch.setenv("ENGRAM_DEMO_SIGNING_SECRET", secret)
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-gcp-project")
    monkeypatch.setenv("ENGRAM_DEMO_MAX_REQUESTS_PER_TOKEN", "1")
    monkeypatch.setenv("ENGRAM_MONTHLY_HARD_CAP_USD", "5.0")
    monkeypatch.setenv("ENGRAM_ESTIMATED_COST_PER_REQ_USD", "0.0004")

    token = mint_demo_token("tenant-ask", days=7, secret=secret)
    headers = {"Authorization": f"Bearer {token}"}
    should_fail = True

    class FlakyOrOkInteractions:
        async def create(self, **kwargs):
            if should_fail:
                raise RuntimeError("Vertex handshake failed")

            async def ok_stream():
                yield SimpleNamespace(event_type="step.delta", delta=SimpleNamespace(type="text", text="Yes [1]."))

            return ok_stream()

    monkeypatch.setattr(
        "app.main._create_vertex_client",
        lambda project, location: SimpleNamespace(aio=SimpleNamespace(interactions=FlakyOrOkInteractions())),
    )
    chunk_validator = _sse_validator("AnalysisStreamChunk")

    # Pre-stream failure emits a schema-valid error frame and refunds the reserved slot
    with client.stream("POST", "/api/v1/answer/stream", headers=headers, json=_answer_request()) as fail_resp:
        assert fail_resp.status_code == 200
        chunks, saw_done_marker = _collect_sse(fail_resp)
    assert saw_done_marker
    assert len(chunks) == 1
    chunk_validator.validate(chunks[0])
    assert chunks[0]["done"] is True
    assert chunks[0]["error"] == "Upstream request failed"
    assert client.get("/api/v1/auth/status", headers=headers).json()["remainingQuota"] == 1

    # A successful stream consumes the only slot; the next request is rejected with 429
    should_fail = False
    with client.stream("POST", "/api/v1/answer/stream", headers=headers, json=_answer_request()) as ok_resp:
        assert ok_resp.status_code == 200
        chunks, _ = _collect_sse(ok_resp)
    assert "".join(c["delta"] for c in chunks) == "Yes [1]."
    assert client.post("/api/v1/answer/stream", headers=headers, json=_answer_request()).status_code == 429


def test_answer_stream_returns_503_when_unconfigured(monkeypatch) -> None:
    monkeypatch.delenv("GOOGLE_CLOUD_PROJECT", raising=False)
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    monkeypatch.delenv("ENGRAM_CLOUD_API_KEY", raising=False)
    resp = client.post("/api/v1/answer/stream", json=_answer_request())
    assert resp.status_code == 503

    monkeypatch.setenv("ENGRAM_CLOUD_API_KEY", "sk-test-only-key")
    monkeypatch.delenv("ENGRAM_CLOUD_BASE_URL", raising=False)
    resp = client.post("/api/v1/answer/stream", json=_answer_request())
    assert resp.status_code == 503


def test_answer_stream_rejects_invalid_requests(monkeypatch) -> None:
    monkeypatch.delenv("ENGRAM_DEMO_SIGNING_SECRET", raising=False)
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "test-gcp-project")

    def must_not_be_called(project: str, location: str):
        raise AssertionError("Vertex client must not be created for invalid requests")

    monkeypatch.setattr("app.main._create_vertex_client", must_not_be_called)

    context = _answer_request()["contexts"][0]
    for bad in [
        _answer_request(question=""),
        _answer_request(contexts=[context] * 9),
        _answer_request(contexts=[{**context, "text": "t" * 1201}]),
        _answer_request(extra="nope"),
    ]:
        assert client.post("/api/v1/answer/stream", json=bad).status_code == 422
