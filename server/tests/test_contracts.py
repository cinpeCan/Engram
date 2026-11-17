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


def test_manifest_matches_contract_schema() -> None:
    schema = _load_schema("model-manifest.schema.json")
    resp = client.get("/api/v1/models/manifest")
    assert resp.status_code == 200
    payload = resp.json()
    jsonschema.validate(instance=payload, schema=schema)
    assert len(payload["models"]) == 1
    model = payload["models"][0]
    assert model["id"] == "qwen2.5-0.5b-instruct-mnn"
    assert model["role"] == "generation"
    assert model["sha256"] is None


def test_analyze_stream_returns_503_when_api_key_missing(monkeypatch) -> None:
    monkeypatch.delenv("ENGRAM_CLOUD_API_KEY", raising=False)
    resp = client.post(
        "/api/v1/analyze/stream",
        json={
            "content": "明天下午3点和张姐对齐柔性屏采购合同。",
            "nowIso": "2026-11-18T09:00:00",
            "model": "gpt-4o-mini",
        },
    )
    assert resp.status_code == 503


def test_analyze_stream_sse_matches_contract_and_analysis_schema(monkeypatch) -> None:
    analysis_payload = {
        "summary": "明日 15:00 与法务张姐对齐采购合同补充条款。",
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
    monkeypatch.setenv("ENGRAM_CLOUD_MODEL", "gpt-4o-mini")
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
        "model": "gpt-4o-mini",
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
        "model": "gpt-4o-mini",
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
