"""Engram gateway service: multi-device SQLite sync, Vertex AI SSE distillation, and quota controls."""

from __future__ import annotations

import inspect
import json
import os
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import AsyncIterator

import httpx
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from google import genai

from .schemas import (
    AnalysisStreamChunk,
    AnalysisStreamRequest,
    AnswerStreamRequest,
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

MAX_QUESTION_CHARS = 500
MAX_CONTEXT_CHARS = 1200
MAX_CONTEXT_BLOCK_CHARS = 6000

_ANALYSIS_SYSTEM_PROMPT = (
    "You are Engram's white-box memory distiller. "
    "Turn the user's raw note into ONE JSON object that strictly matches this shape. "
    "Output JSON only: no Markdown, no code fences, no explanations.\n"
    '{"summary":"• ...","tags":[],"calendarProposal":null,'
    '"relations":[{"source":"","target":"","relation":"","bidirectional":false}]}\n'
    "Rules:\n"
    '- summary: 1-3 bullets, each starting with "• " and separated by \\n; each bullet a short '
    "subject-verb-object phrase of at most ~12 words. Capture clear intent smoothly across minor input typos "
    "while keeping real entity names intact. Never chat, answer questions, or compute arithmetic. Never reveal plaintext "
    'passwords, PINs or secrets; write "see source" instead.\n'
    "- tags: at most 3 short scene tags (each <= 24 characters), or [] when none fit.\n"
    "- calendarProposal: null unless the note states a concrete schedule. Convert relative times "
    "(e.g. tomorrow 3pm) into an exact ISO local datetime based on the provided current local time. "
    "durationMinutes between 5 and 480.\n"
    "- relations: at most 15 grounded entity triples; source and target must differ ([] when < 2 entities); "
    "entity names <= 32 characters, relation labels <= 24 characters.\n"
    "- Write the summary, tags, calendar title and relation labels in the same language as the note.\n"
    "- If the note is written in romanized Mandarin Pinyin (e.g. 'wo yao chi niurou, xiang yao he ww, yy yi qi wan'), "
    "restore clear Pinyin words into natural Simplified Chinese ('he A, B yi qi' means 'with A and B together'; "
    "never invent unmentioned drinks or items), while keeping 2-3 letter abbreviations (such as ww, yy) verbatim without guessing full names."
)

_ANSWER_SYSTEM_PROMPT = (
    "You are Engram's memory assistant. Answer the user's question using ONLY the numbered "
    "memory snippets provided.\n"
    "Rules:\n"
    "- Cite sources inline as [1], [2], matching the snippet numbers.\n"
    "- If the snippets do not contain the answer, say so briefly. Never invent facts.\n"
    "- Be concise: at most 4 sentences or 4 bullets.\n"
    "- Never reveal plaintext passwords, PINs or secrets; refer to the cited snippet instead.\n"
    "- Answer in the same language as the question.\n"
    "- Output plain text only, not JSON or Markdown headings."
)

app = FastAPI(
    title="Engram Sync & Cloud Inference Service",
    version="0.5.0",
    description=(
        "Multi-device memory synchronization plus Vertex AI / OpenAI-compatible cloud SSE "
        "distillation and grounded memory answers."
    ),
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
    return f"Current local time: {now_iso}\nNote: {truncated_content}"


def _snippet_date(created_at_millis: int, now_iso: str) -> str:
    """Render a snippet date in the client's UTC offset when nowIso carries one, else in UTC."""
    try:
        tz = datetime.fromisoformat(now_iso).tzinfo or timezone.utc
    except ValueError:
        tz = timezone.utc
    return datetime.fromtimestamp(created_at_millis / 1000, tz=tz).date().isoformat()


def _build_answer_prompt(request: AnswerStreamRequest) -> str:
    lines: list[str] = []
    budget = MAX_CONTEXT_BLOCK_CHARS
    for index, context in enumerate(request.contexts, start=1):
        if budget <= 0:
            break
        text = " ".join(context.text.split())[:MAX_CONTEXT_CHARS][:budget]
        budget -= len(text)
        lines.append(f"[{index}] ({_snippet_date(context.createdAtMillis, request.nowIso)}) {text}")
    snippets = "\n".join(lines) if lines else "(no memory snippets)"
    question = request.question[:MAX_QUESTION_CHARS]
    return f"Current local time: {request.nowIso}\nMemory snippets:\n{snippets}\n\nQuestion: {question}"


def _create_vertex_client(project: str, location: str) -> genai.Client:
    return genai.Client(vertexai=True, project=project, location=location)


@dataclass(frozen=True)
class _QuotaReservation:
    tenant_id: str
    bucket: str
    estimated_cost_usd: float

    def refund(self) -> None:
        refund_demo_quota(self.tenant_id, self.bucket, self.estimated_cost_usd)


@dataclass(frozen=True)
class _Backend:
    model_name: str
    vertex_project: str = ""
    vertex_location: str = ""
    base_url: str = ""
    api_key: str = ""


def _sse_frame(
    model_name: str,
    start_time: float,
    delta: str = "",
    done: bool = False,
    error: str | None = None,
) -> str:
    chunk = AnalysisStreamChunk(
        delta=delta,
        done=done,
        model=model_name,
        latencyMs=int((time.perf_counter() - start_time) * 1000),
        error=error,
    )
    return f"data: {chunk.model_dump_json()}\n\n"


def _sse_failure(model_name: str, start_time: float) -> list[str]:
    return [_sse_frame(model_name, start_time, done=True, error="Upstream request failed"), "data: [DONE]\n\n"]


def _sse_success(model_name: str, start_time: float) -> list[str]:
    return [_sse_frame(model_name, start_time, done=True), "data: [DONE]\n\n"]


async def _stream_vertex_text(
    system_prompt: str,
    user_prompt: str,
    project: str,
    location: str,
    model_name: str,
    start_time: float,
    quota: _QuotaReservation | None = None,
) -> AsyncIterator[str]:
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
            system_instruction=system_prompt,
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
                yield _sse_frame(model_name, start_time, delta=delta_text)
    except Exception:
        if not stream_established and quota is not None:
            quota.refund()
        for frame in _sse_failure(model_name, start_time):
            yield frame
        return

    for frame in _sse_success(model_name, start_time):
        yield frame


async def _stream_upstream_text(
    system_prompt: str,
    user_prompt: str,
    base_url: str,
    api_key: str,
    model_name: str,
    start_time: float,
    quota: _QuotaReservation | None = None,
) -> AsyncIterator[str]:
    payload = {
        "model": model_name,
        "stream": True,
        "max_tokens": MAX_OUTPUT_TOKENS,
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
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
                        yield _sse_frame(model_name, start_time, delta=delta_text)
    except httpx.HTTPError:
        if not stream_established and quota is not None:
            quota.refund()
        for frame in _sse_failure(model_name, start_time):
            yield frame
        return

    for frame in _sse_success(model_name, start_time):
        yield frame


def _resolve_backend(request_model: str) -> _Backend:
    """Vertex AI (ADC) when GOOGLE_CLOUD_PROJECT is set, else an OpenAI-compatible gateway; 503 if neither."""
    gcp_project = os.getenv("GOOGLE_CLOUD_PROJECT", "").strip()
    if gcp_project:
        return _Backend(
            model_name=os.getenv("ENGRAM_VERTEX_MODEL", DEFAULT_VERTEX_MODEL).strip() or DEFAULT_VERTEX_MODEL,
            vertex_project=gcp_project,
            vertex_location=os.getenv("GOOGLE_CLOUD_LOCATION", DEFAULT_VERTEX_LOCATION).strip()
            or DEFAULT_VERTEX_LOCATION,
        )
    api_key = os.getenv("ENGRAM_CLOUD_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="ENGRAM_CLOUD_API_KEY is not configured")
    base_url = os.getenv("ENGRAM_CLOUD_BASE_URL", "").strip()
    if not base_url:
        raise HTTPException(status_code=503, detail="ENGRAM_CLOUD_BASE_URL is not configured")
    return _Backend(
        model_name=os.getenv("ENGRAM_CLOUD_MODEL", "").strip() or request_model,
        base_url=base_url,
        api_key=api_key,
    )


def _reserve_quota(claims) -> _QuotaReservation | None:
    if claims is None:
        return None
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
    return _QuotaReservation(tenant_id=claims.tenant_id, bucket=reserved_bucket, estimated_cost_usd=est_cost_usd)


def _stream_completion(
    authorization: str | None,
    system_prompt: str,
    user_prompt: str,
    request_model: str,
) -> StreamingResponse:
    """Shared pipeline: auth (401) -> backend resolution (503) -> quota reservation (429) -> SSE."""
    claims = require_demo_claims_if_enabled(authorization)
    backend = _resolve_backend(request_model)
    quota = _reserve_quota(claims)

    start_time = time.perf_counter()
    if backend.vertex_project:
        generator = _stream_vertex_text(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            project=backend.vertex_project,
            location=backend.vertex_location,
            model_name=backend.model_name,
            start_time=start_time,
            quota=quota,
        )
    else:
        generator = _stream_upstream_text(
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            base_url=backend.base_url,
            api_key=backend.api_key,
            model_name=backend.model_name,
            start_time=start_time,
            quota=quota,
        )

    return StreamingResponse(
        generator,
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@app.post("/api/v1/analyze/stream")
async def analyze_stream(
    request: AnalysisStreamRequest,
    authorization: str | None = Header(default=None),
) -> StreamingResponse:
    return _stream_completion(
        authorization=authorization,
        system_prompt=_ANALYSIS_SYSTEM_PROMPT,
        user_prompt=_build_user_prompt(request.nowIso, request.content),
        request_model=request.model,
    )


@app.post("/api/v1/answer/stream")
async def answer_stream(
    request: AnswerStreamRequest,
    authorization: str | None = Header(default=None),
) -> StreamingResponse:
    """Grounded answer over on-device retrieved memory snippets (GraphRAG "Ask"), cited as [n]."""
    return _stream_completion(
        authorization=authorization,
        system_prompt=_ANSWER_SYSTEM_PROMPT,
        user_prompt=_build_answer_prompt(request),
        request_model=request.model,
    )
