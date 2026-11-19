"""Engram gateway service: multi-device SQLite sync, Vertex AI SSE distillation, and quota controls."""

from __future__ import annotations

import inspect
import json
import os
import time
from typing import AsyncIterator

import httpx
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from google import genai

from .schemas import (
    AnalysisStreamChunk,
    AnalysisStreamRequest,
    AuthStatusResponse,
    MemoryDeleteRequest,
    MemoryDeleteResponse,
    MemorySyncRequest,
    MemorySyncResponse,
)
from .storage import (
    delete_synced_memories,
    get_demo_quota_status,
    refund_demo_quota,
    sync_workspace,
    try_acquire_demo_quota,
)
from .tokens import require_demo_claims_if_enabled

MAX_INPUT_CHARS = 4000
MAX_OUTPUT_TOKENS = 1024
DEFAULT_VERTEX_LOCATION = "global"
DEFAULT_VERTEX_MODEL = "gemini-3.5-flash-lite"

_ANALYSIS_SYSTEM_PROMPT = (
    "你是印迹（Engram）白盒记忆结构化提炼助手。"
    "请将用户的原始随手记提炼为 JSON 对象，严格匹配以下 schema，不要输出额外解释或 Markdown：\n"
    '{"summary":"若含多个要点，请分2~3条输出，每行以 \\"• \\" 开头并用 \\\\n 分隔，每条不超过22字，用主谓宾短句直述重点、杜绝长难句；若仅单一简短事项可直接1条短句（不超过24字）；严禁泄露明文密码/口令（统一替换为详见原文）",'
    '"tags":["场景标签（最多3个，无合适返回[]）"],'
    '"calendarProposal":{"title":"日程标题","startTimeIso":"YYYY-MM-DDTHH:MM:SS","durationMinutes":60,"location":null},'
    '"relations":[{"source":"实体A","target":"实体B","relation":"关系标签","bidirectional":false}]}\n'
    "若无明确时间安排，calendarProposal 返回 null；relations 禁止自环且至多 15 条。"
)

app = FastAPI(
    title="Engram Sync & Cloud Inference Service",
    version="0.4.0",
    description="Multi-device memory synchronization and Vertex AI / OpenAI-compatible cloud SSE distillation.",
)


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "engram-server"}


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/v1/auth/status", response_model=AuthStatusResponse)
async def auth_status(
    authorization: str | None = Header(default=None),
) -> AuthStatusResponse:
    claims = require_demo_claims_if_enabled(authorization)
    if claims is None:
        return AuthStatusResponse(mode="open", remainingQuota=None)

    max_per_token = int(os.getenv("ENGRAM_DEMO_MAX_REQUESTS_PER_TOKEN", "50"))
    monthly_cap_usd = float(os.getenv("ENGRAM_MONTHLY_HARD_CAP_USD", "5.0"))
    est_cost_usd = float(os.getenv("ENGRAM_ESTIMATED_COST_PER_REQ_USD", "0.0004"))
    available, remaining = get_demo_quota_status(
        tenant_id=claims.tenant_id,
        max_requests_per_token=max_per_token,
        monthly_hard_cap_usd=monthly_cap_usd,
        estimated_cost_usd=est_cost_usd,
    )
    if not available:
        raise HTTPException(status_code=429, detail="Demo token quota exhausted")
    return AuthStatusResponse(mode="demo", remainingQuota=remaining)


@app.post("/api/v1/memories/sync", response_model=MemorySyncResponse)
async def sync_memories(
    request: MemorySyncRequest,
    authorization: str | None = Header(default=None),
) -> MemorySyncResponse:
    claims = require_demo_claims_if_enabled(authorization)
    cleaned_key = request.syncKey.strip()
    if not cleaned_key:
        raise HTTPException(status_code=400, detail="syncKey must not be empty")

    storage_sync_key = f"demo:{claims.tenant_id}:{cleaned_key}" if claims is not None else cleaned_key
    upserted_count, merged_memories = sync_workspace(storage_sync_key, request.memories)
    return MemorySyncResponse(
        syncKey=cleaned_key,
        upsertedCount=upserted_count,
        totalCount=len(merged_memories),
        serverTimeMillis=int(time.time() * 1000),
        memories=merged_memories,
    )


@app.post("/api/v1/memories/delete", response_model=MemoryDeleteResponse)
async def delete_memories(
    request: MemoryDeleteRequest,
    authorization: str | None = Header(default=None),
) -> MemoryDeleteResponse:
    claims = require_demo_claims_if_enabled(authorization)
    cleaned_key = request.syncKey.strip()
    if not cleaned_key:
        raise HTTPException(status_code=400, detail="syncKey must not be empty")

    storage_sync_key = f"demo:{claims.tenant_id}:{cleaned_key}" if claims is not None else cleaned_key
    deleted_count = delete_synced_memories(storage_sync_key, request.deletedIds)
    return MemoryDeleteResponse(deletedCount=deleted_count)


def _build_user_prompt(now_iso: str, content: str) -> str:
    truncated_content = content[:MAX_INPUT_CHARS]
    return f"当前本地时间：{now_iso}\n原文：{truncated_content}"


def _build_analysis_messages(request: AnalysisStreamRequest) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": _ANALYSIS_SYSTEM_PROMPT},
        {"role": "user", "content": _build_user_prompt(request.nowIso, request.content)},
    ]


def _create_vertex_client(project: str, location: str) -> genai.Client:
    return genai.Client(vertexai=True, project=project, location=location)


async def _stream_vertex_analysis(
    request: AnalysisStreamRequest,
    project: str,
    location: str,
    model_name: str,
    start_time: float,
    quota_tenant_id: str | None = None,
    quota_bucket: str | None = None,
    estimated_cost_usd: float = 0.0,
) -> AsyncIterator[str]:
    user_prompt = _build_user_prompt(request.nowIso, request.content)
    generation_config = {
        "max_output_tokens": MAX_OUTPUT_TOKENS,
        "thinking_level": "minimal" if "flash-lite" in model_name else "low",
    }

    stream_established = False
    try:
        client = _create_vertex_client(project=project, location=location)
        created = client.aio.interactions.create(
            model=model_name,
            input=user_prompt,
            system_instruction=_ANALYSIS_SYSTEM_PROMPT,
            generation_config=generation_config,
            stream=True,
            store=False,
        )
        stream = await created if inspect.isawaitable(created) else created
        async for event in stream:
            stream_established = True
            if getattr(event, "event_type", None) != "step.delta":
                continue
            delta = getattr(event, "delta", None)
            if getattr(delta, "type", None) != "text":
                continue
            delta_text = getattr(delta, "text", None) or ""
            if delta_text:
                chunk = AnalysisStreamChunk(
                    delta=delta_text,
                    done=False,
                    model=model_name,
                    latencyMs=int((time.perf_counter() - start_time) * 1000),
                )
                yield f"data: {chunk.model_dump_json()}\n\n"
    except Exception:
        if not stream_established and quota_tenant_id and quota_bucket:
            refund_demo_quota(quota_tenant_id, quota_bucket, estimated_cost_usd)
        err_chunk = AnalysisStreamChunk(
            delta="",
            done=True,
            model=model_name,
            latencyMs=int((time.perf_counter() - start_time) * 1000),
            error="Upstream request failed",
        )
        yield f"data: {err_chunk.model_dump_json()}\n\n"
        yield "data: [DONE]\n\n"
        return

    final_chunk = AnalysisStreamChunk(
        delta="",
        done=True,
        model=model_name,
        latencyMs=int((time.perf_counter() - start_time) * 1000),
    )
    yield f"data: {final_chunk.model_dump_json()}\n\n"
    yield "data: [DONE]\n\n"


async def _stream_upstream_analysis(
    request: AnalysisStreamRequest,
    base_url: str,
    api_key: str,
    model_name: str,
    start_time: float,
    quota_tenant_id: str | None = None,
    quota_bucket: str | None = None,
    estimated_cost_usd: float = 0.0,
) -> AsyncIterator[str]:
    payload = {
        "model": model_name,
        "stream": True,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "messages": _build_analysis_messages(request),
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    url = f"{base_url.rstrip('/')}/chat/completions"

    stream_established = False
    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            async with client.stream("POST", url, headers=headers, json=payload) as resp:
                resp.raise_for_status()
                stream_established = True
                async for line in resp.aiter_lines():
                    if not line or not line.startswith("data:"):
                        continue
                    data = line.removeprefix("data:").strip()
                    if data == "[DONE]":
                        break
                    parsed = json.loads(data)
                    choices = parsed.get("choices") or []
                    if not choices:
                        continue
                    delta_text = (choices[0].get("delta") or {}).get("content") or ""
                    if delta_text:
                        chunk = AnalysisStreamChunk(
                            delta=delta_text,
                            done=False,
                            model=model_name,
                            latencyMs=int((time.perf_counter() - start_time) * 1000),
                        )
                        yield f"data: {chunk.model_dump_json()}\n\n"
    except httpx.HTTPError:
        if not stream_established and quota_tenant_id and quota_bucket:
            refund_demo_quota(quota_tenant_id, quota_bucket, estimated_cost_usd)
        err_chunk = AnalysisStreamChunk(
            delta="",
            done=True,
            model=model_name,
            latencyMs=int((time.perf_counter() - start_time) * 1000),
            error="Upstream request failed",
        )
        yield f"data: {err_chunk.model_dump_json()}\n\n"
        yield "data: [DONE]\n\n"
        return

    final_chunk = AnalysisStreamChunk(
        delta="",
        done=True,
        model=model_name,
        latencyMs=int((time.perf_counter() - start_time) * 1000),
    )
    yield f"data: {final_chunk.model_dump_json()}\n\n"
    yield "data: [DONE]\n\n"


@app.post("/api/v1/analyze/stream")
async def analyze_stream(
    request: AnalysisStreamRequest,
    authorization: str | None = Header(default=None),
) -> StreamingResponse:
    claims = require_demo_claims_if_enabled(authorization)

    gcp_project = os.getenv("GOOGLE_CLOUD_PROJECT", "").strip()
    if gcp_project:
        gcp_location = os.getenv("GOOGLE_CLOUD_LOCATION", DEFAULT_VERTEX_LOCATION).strip() or DEFAULT_VERTEX_LOCATION
        vertex_model = os.getenv("ENGRAM_VERTEX_MODEL", DEFAULT_VERTEX_MODEL).strip() or DEFAULT_VERTEX_MODEL
    else:
        api_key = os.getenv("ENGRAM_CLOUD_API_KEY", "").strip()
        if not api_key:
            raise HTTPException(status_code=503, detail="ENGRAM_CLOUD_API_KEY is not configured")
        base_url = os.getenv("ENGRAM_CLOUD_BASE_URL", "").strip()
        if not base_url:
            raise HTTPException(status_code=503, detail="ENGRAM_CLOUD_BASE_URL is not configured")
        cloud_model = os.getenv("ENGRAM_CLOUD_MODEL", "").strip() or request.model

    quota_tenant_id: str | None = None
    quota_bucket: str | None = None
    est_cost_usd = 0.0
    if claims is not None:
        max_per_token = int(os.getenv("ENGRAM_DEMO_MAX_REQUESTS_PER_TOKEN", "50"))
        monthly_cap_usd = float(os.getenv("ENGRAM_MONTHLY_HARD_CAP_USD", "5.0"))
        est_cost_usd = float(os.getenv("ENGRAM_ESTIMATED_COST_PER_REQ_USD", "0.0004"))
        allowed, detail, reserved_bucket = try_acquire_demo_quota(
            tenant_id=claims.tenant_id,
            max_requests_per_token=max_per_token,
            monthly_hard_cap_usd=monthly_cap_usd,
            estimated_cost_usd=est_cost_usd,
        )
        if not allowed:
            raise HTTPException(status_code=429, detail=detail)
        quota_tenant_id = claims.tenant_id
        quota_bucket = reserved_bucket

    start_time = time.perf_counter()
    if gcp_project:
        generator = _stream_vertex_analysis(
            request=request,
            project=gcp_project,
            location=gcp_location,
            model_name=vertex_model,
            start_time=start_time,
            quota_tenant_id=quota_tenant_id,
            quota_bucket=quota_bucket,
            estimated_cost_usd=est_cost_usd,
        )
    else:
        generator = _stream_upstream_analysis(
            request=request,
            base_url=base_url,
            api_key=api_key,
            model_name=cloud_model,
            start_time=start_time,
            quota_tenant_id=quota_tenant_id,
            quota_bucket=quota_bucket,
            estimated_cost_usd=est_cost_usd,
        )

    return StreamingResponse(
        generator,
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
