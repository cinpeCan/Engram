"""Contract tests verifying FastAPI payloads conform to root contracts/*.schema.json."""

from __future__ import annotations

import json
from pathlib import Path
from typing import AsyncIterator

import httpx
import jsonschema
from fastapi.testclient import TestClient

from app.main import app

ROOT_DIR = Path(__file__).resolve().parents[2]
CONTRACTS_DIR = ROOT_DIR / "contracts"

client = TestClient(app)


def _load_schema(filename: str) -> dict:
    return json.loads((CONTRACTS_DIR / filename).read_text(encoding="utf-8"))


def test_health_endpoint() -> None:
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_multi_device_sync_merges_by_timestamp_and_isolates_sync_keys(tmp_path, monkeypatch) -> None:
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
                "content": "明天下午3点在软件园B座和张姐对齐合同。",
                "summary": "• 明日 15:00 · 软件园B座\n• 与张姐对齐合同",
                "tags": ["商务合同", "日程提醒"],
                "createdAtMillis": 1700000000000,
                "updatedAtMillis": 1700000001000,
                "analysisStatus": "READY",
                "calendarProposal": {
                    "title": "合同对齐",
                    "startTimeIso": "2026-11-19T15:00:00",
                    "durationMinutes": 60,
                    "location": "软件园B座",
                },
                "calendarSaved": False,
                "analysisSource": "MNN_EDGE",
                "telemetryBadge": "MNN 0.5B · TTFT 120ms · 32.0 tok/s",
                "relations": [
                    {
                        "source": "张姐",
                        "target": "合同",
                        "relation": "对齐条款",
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
                "content": "明天下午3点在软件园B座和张姐对齐合同。",
                "summary": "旧摘要不应覆盖",
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
                "content": "陈工提交烧屏验收报告",
                "summary": "陈工提交烧屏验收报告",
                "tags": ["项目推进"],
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
    monkeypatch.delenv("ENGRAM_CLOUD_API_KEY", raising=False)
    resp = client.post(
        "/api/v1/analyze/stream",
        json={
            "content": "明天下午3点和张姐对齐柔性屏采购合同。",
            "nowIso": "2026-11-18T09:00:00",
            "model": "qwen-turbo",
        },
    )
    assert resp.status_code == 503


def test_analyze_stream_sse_matches_contract_and_analysis_schema(monkeypatch) -> None:
    analysis_payload = {
        "summary": "• 明日 15:00 · 软件园三期B区7楼会议室\n• 与法务张姐对齐采购合同补充条款",
        "tags": ["法务商务", "日程跟进"],
        "calendarProposal": {
            "title": "柔性屏采购合同补充条款评审",
            "startTimeIso": "2026-11-19T15:00:00",
            "durationMinutes": 60,
            "location": "软件园三期B区7楼会议室",
        },
        "relations": [
            {
                "source": "法务张姐",
                "target": "采购合同",
                "relation": "过补充条款",
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

    monkeypatch.setenv("ENGRAM_CLOUD_API_KEY", "sk-test-valid")
    monkeypatch.setenv("ENGRAM_CLOUD_BASE_URL", "https://example.invalid/v1")
    monkeypatch.setenv("ENGRAM_CLOUD_MODEL", "qwen-turbo")
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
        "content": "明天下午15:00在软件园三期B区7楼会议室，和法务张姐过一遍柔性屏采购合同补充条款。",
        "nowIso": "2026-11-18T09:00:00",
        "model": "qwen-turbo",
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
        "content": "合同卡在法务张姐条款对齐。",
        "nowIso": "2026-11-18T09:00:00",
        "model": "qwen-turbo",
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
