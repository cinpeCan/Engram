"""Contract tests verifying FastAPI payloads conform to root contracts/*.schema.json."""

from __future__ import annotations

import json
from pathlib import Path

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
    roles = {m["role"] for m in payload["models"]}
    assert "generation" in roles
    assert "embedding" in roles


def test_chat_stream_sse_matches_contract_schema() -> None:
    schema = _load_schema("chat-sse.schema.json")
    req_validator = jsonschema.Draft202012Validator(
        {
            "$schema": schema["$schema"],
            "$defs": schema["$defs"],
            "$ref": "#/$defs/ChatStreamRequest",
        }
    )
    chunk_validator = jsonschema.Draft202012Validator(
        {
            "$schema": schema["$schema"],
            "$defs": schema["$defs"],
            "$ref": "#/$defs/ChatStreamChunk",
        }
    )

    request_body = {
        "query": "屏幕采购目前卡在谁手里？",
        "citations": [
            {
                "memoryId": "seed-2",
                "content": "法务张姐提醒付款条款需确认，采购合同目前停在法务评审阶段。",
            }
        ],
        "model": "engram-cloud-fallback",
        "stream": True,
    }
    req_validator.validate(request_body)

    chunks = []
    saw_done_marker = False
    with client.stream("POST", "/api/v1/chat/stream", json=request_body) as resp:
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
    assert len(chunks) >= 2
    assert chunks[-1]["done"] is True
    combined_text = "".join(c["delta"] for c in chunks)
    assert "法务" in combined_text
