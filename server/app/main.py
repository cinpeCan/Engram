"""Engram同仓轻量服务端: 端侧 MNN 模型清单分发 + OpenAI 兼容 SSE 结构化记忆提炼."""

from __future__ import annotations

import json
import os
import time
from typing import AsyncIterator

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse

from .schemas import (
    AnalysisStreamChunk,
    AnalysisStreamRequest,
    ModelDescriptor,
    ModelFile,
    ModelManifestResponse,
)

app = FastAPI(
    title="Engram Gateway",
    version="0.2.0",
    description="On-device MNN model catalog and cloud SSE structured analysis gateway.",
)


def build_default_manifest() -> ModelManifestResponse:
    return ModelManifestResponse(
        schemaVersion="1.0.0",
        models=[
            ModelDescriptor(
                id="qwen2.5-0.5b-instruct-mnn",
                name="Qwen2.5-0.5B Instruct (MNN INT4)",
                role="generation",
                quantization="INT4",
                sizeBytes=398_458_880,
                minRamMb=2048,
                downloadUrl="https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN/resolve/main/llm.mnn.weight",
                mirrorUrl="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                sha256=None,
                description="端侧离线推理核心模型，用于实时流式提炼结构化摘要、日程提议与实体关系三元组。",
                files=[
                    ModelFile(
                        name="config.json",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/config.json",
                        sizeBytes=624,
                        sha256=None,
                    ),
                    ModelFile(
                        name="llm_config.json",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm_config.json",
                        sizeBytes=780,
                        sha256=None,
                    ),
                    ModelFile(
                        name="tokenizer.txt",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/tokenizer.txt",
                        sizeBytes=2_450_000,
                        sha256=None,
                    ),
                    ModelFile(
                        name="llm.mnn",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn",
                        sizeBytes=4_200_000,
                        sha256=None,
                    ),
                    ModelFile(
                        name="llm.mnn.weight",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                        sizeBytes=391_807_476,
                        sha256=None,
                    ),
                ],
            ),
        ],
    )


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok", "service": "engram-gateway"}


@app.get("/api/v1/models/manifest", response_model=ModelManifestResponse)
async def get_model_manifest() -> ModelManifestResponse:
    return build_default_manifest()


def _build_analysis_messages(request: AnalysisStreamRequest) -> list[dict[str, str]]:
    system_prompt = (
        "你是印迹（Engram）白盒记忆结构化提炼助手。"
        "请将用户的原始随手记提炼为 JSON 对象，严格匹配以下 schema，不要输出额外解释或 Markdown：\n"
        '{"summary":"若含多个要点，请分2~3条输出，每行以 \\"• \\" 开头并用 \\\\n 分隔，每条不超过22字，用主谓宾短句直述重点、杜绝长难句；若仅单一简短事项可直接1条短句（不超过24字）；严禁泄露明文密码/口令（统一替换为详见原文）",'
        '"tags":["场景标签（最多3个，无合适返回[]）"],'
        '"calendarProposal":{"title":"日程标题","startTimeIso":"YYYY-MM-DDTHH:MM:SS","durationMinutes":60,"location":null},'
        '"relations":[{"source":"实体A","target":"实体B","relation":"关系标签","bidirectional":false}]}\n'
        "若无明确时间安排，calendarProposal 返回 null；relations 禁止自环且至多 15 条。"
    )
    return [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": f"当前本地时间：{request.nowIso}\n原文：{request.content}"},
    ]


async def _stream_upstream_analysis(
    request: AnalysisStreamRequest,
    base_url: str,
    api_key: str,
    model_name: str,
    start_time: float,
) -> AsyncIterator[str]:
    payload = {
        "model": model_name,
        "stream": True,
        "messages": _build_analysis_messages(request),
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    url = f"{base_url.rstrip('/')}/chat/completions"

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            async with client.stream("POST", url, headers=headers, json=payload) as resp:
                resp.raise_for_status()
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
async def analyze_stream(request: AnalysisStreamRequest) -> StreamingResponse:
    api_key = os.getenv("ENGRAM_CLOUD_API_KEY", "").strip()
    if not api_key:
        raise HTTPException(status_code=503, detail="ENGRAM_CLOUD_API_KEY is not configured")

    base_url = os.getenv("ENGRAM_CLOUD_BASE_URL", "https://api.openai.com/v1").strip()
    cloud_model = os.getenv("ENGRAM_CLOUD_MODEL", "gpt-4o-mini").strip() or request.model

    start_time = time.perf_counter()
    generator = _stream_upstream_analysis(
        request=request,
        base_url=base_url,
        api_key=api_key,
        model_name=cloud_model,
        start_time=start_time,
    )
    return StreamingResponse(
        generator,
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
