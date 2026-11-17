"""Engram同仓轻量服务端: 端侧 MNN 模型清单分发 + OpenAI 兼容 SSE 云端协同回退."""

from __future__ import annotations

import asyncio
import json
import os
import time
from typing import AsyncIterator

import httpx
from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from .schemas import (
    ChatStreamChunk,
    ChatStreamRequest,
    ModelDescriptor,
    ModelFile,
    ModelManifestResponse,
)

# Sentinel 64-hex hash indicates upstream rolling weights where client records computed SHA-256 on completion.
UNPINNED_SHA256 = "0" * 64

app = FastAPI(
    title="Engram Gateway",
    version="0.1.0",
    description="On-device MNN model catalog and cloud SSE collaboration gateway.",
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
                sha256=UNPINNED_SHA256,
                description="轻量端侧推理首选，适合快速下载验证与结构化标签/三元组抽取。",
                files=[
                    ModelFile(
                        name="config.json",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/config.json",
                        sizeBytes=624,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="llm_config.json",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm_config.json",
                        sizeBytes=780,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="tokenizer.txt",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/tokenizer.txt",
                        sizeBytes=2_450_000,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="llm.mnn",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn",
                        sizeBytes=4_200_000,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="llm.mnn.weight",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                        sizeBytes=391_807_476,
                        sha256=UNPINNED_SHA256,
                    ),
                ],
            ),
            ModelDescriptor(
                id="qwen2.5-1.5b-instruct-mnn",
                name="Qwen2.5-1.5B Instruct (MNN INT4)",
                role="generation",
                quantization="INT4",
                sizeBytes=1_027_604_480,
                minRamMb=4096,
                downloadUrl="https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN/resolve/main/llm.mnn.weight",
                mirrorUrl="https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                sha256=UNPINNED_SHA256,
                description="主力高质量端侧模型，中文摘要、RAG 问答与复杂实体关系抽取精度更高。",
                files=[
                    ModelFile(
                        name="config.json",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/config.json",
                        sizeBytes=640,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="llm_config.json",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm_config.json",
                        sizeBytes=812,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="tokenizer.txt",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/tokenizer.txt",
                        sizeBytes=2_450_000,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="llm.mnn",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm.mnn",
                        sizeBytes=6_153_028,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="llm.mnn.weight",
                        url="https://modelscope.cn/models/MNN/Qwen2.5-1.5B-Instruct-MNN/resolve/master/llm.mnn.weight",
                        sizeBytes=1_019_000_000,
                        sha256=UNPINNED_SHA256,
                    ),
                ],
            ),
            ModelDescriptor(
                id="gte-multilingual-base-mnn",
                name="GTE Multilingual Base (MNN Embedding)",
                role="embedding",
                quantization="INT8",
                sizeBytes=167_772_160,
                minRamMb=1024,
                downloadUrl="https://huggingface.co/taobao-mnn/gte_sentence-embedding_multilingual-base-MNN/resolve/main/model.mnn.weight",
                mirrorUrl="https://modelscope.cn/models/MNN/gte_sentence-embedding_multilingual-base-MNN/resolve/master/model.mnn.weight",
                sha256=UNPINNED_SHA256,
                description="中英双语本地句向量模型，支撑本地 Room 混合 RAG 语义召回。",
                files=[
                    ModelFile(
                        name="model.mnn",
                        url="https://modelscope.cn/models/MNN/gte_sentence-embedding_multilingual-base-MNN/resolve/master/model.mnn",
                        sizeBytes=1_772_160,
                        sha256=UNPINNED_SHA256,
                    ),
                    ModelFile(
                        name="model.mnn.weight",
                        url="https://modelscope.cn/models/MNN/gte_sentence-embedding_multilingual-base-MNN/resolve/master/model.mnn.weight",
                        sizeBytes=166_000_000,
                        sha256=UNPINNED_SHA256,
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


def _compose_local_synthesis(request: ChatStreamRequest) -> str:
    if not request.citations:
        return (
            "未在本地记忆库检索到直接关联片段。你可以先在「印迹」页记录相关内容，"
            "系统会自动生成双字全文索引与语义向量用于后续寻迹。"
        )
    bullet_lines = []
    for idx, citation in enumerate(request.citations[:3], start=1):
        snippet = citation.content.strip().replace("\n", " ")
        if len(snippet) > 96:
            snippet = snippet[:96] + "..."
        bullet_lines.append(f"[{idx}] {snippet}")

    joined = "\n".join(bullet_lines)
    return (
        f"结合已命中的 {len(request.citations)} 条本地记忆，针对「{request.query}」归纳如下：\n"
        f"{joined}\n\n"
        "核心结论：上述引用片段已覆盖关键实体与约束条件，如需在图谱中查看关联上下文，可点击原文卡片进入 2D 脉络视图。"
    )


async def _stream_upstream_openai(
    request: ChatStreamRequest,
    base_url: str,
    api_key: str,
    model_name: str,
    start_time: float,
) -> AsyncIterator[str]:
    citations_block = "\n".join(
        f"- (id={c.memoryId}) {c.content}" for c in request.citations
    ) or "(无本地引用片段)"
    payload = {
        "model": model_name,
        "stream": True,
        "messages": [
            {
                "role": "system",
                "content": (
                    "你是 Engram 白盒记忆助手。严格依据用户提供的本地记忆引用回答问题，"
                    "禁止编造引用之外的事实；回答简洁清晰。"
                ),
            },
            {
                "role": "user",
                "content": f"问题：{request.query}\n\n本地记忆引用：\n{citations_block}",
            },
        ],
    }
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    url = f"{base_url.rstrip('/')}/chat/completions"

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
                    chunk = ChatStreamChunk(
                        delta=delta_text,
                        done=False,
                        model=model_name,
                        latencyMs=int((time.perf_counter() - start_time) * 1000),
                    )
                    yield f"data: {chunk.model_dump_json()}\n\n"

    final_chunk = ChatStreamChunk(
        delta="",
        done=True,
        model=model_name,
        latencyMs=int((time.perf_counter() - start_time) * 1000),
    )
    yield f"data: {final_chunk.model_dump_json()}\n\n"
    yield "data: [DONE]\n\n"


async def _stream_local_fallback(request: ChatStreamRequest) -> AsyncIterator[str]:
    start_time = time.perf_counter()
    model_name = request.model or "engram-cloud-fallback"
    full_text = _compose_local_synthesis(request)
    step = 6
    for i in range(0, len(full_text), step):
        piece = full_text[i : i + step]
        chunk = ChatStreamChunk(
            delta=piece,
            done=False,
            model=model_name,
            latencyMs=int((time.perf_counter() - start_time) * 1000),
        )
        yield f"data: {chunk.model_dump_json()}\n\n"
        await asyncio.sleep(0.01)

    final_chunk = ChatStreamChunk(
        delta="",
        done=True,
        model=model_name,
        latencyMs=int((time.perf_counter() - start_time) * 1000),
    )
    yield f"data: {final_chunk.model_dump_json()}\n\n"
    yield "data: [DONE]\n\n"


@app.post("/api/v1/chat/stream")
async def chat_stream(request: ChatStreamRequest) -> StreamingResponse:
    api_key = os.getenv("ENGRAM_CLOUD_API_KEY", "").strip()
    base_url = os.getenv("ENGRAM_CLOUD_BASE_URL", "https://api.openai.com/v1").strip()
    cloud_model = os.getenv("ENGRAM_CLOUD_MODEL", "gpt-4o-mini").strip()

    if api_key:
        start_time = time.perf_counter()
        generator = _stream_upstream_openai(
            request=request,
            base_url=base_url,
            api_key=api_key,
            model_name=cloud_model,
            start_time=start_time,
        )
    else:
        generator = _stream_local_fallback(request)

    return StreamingResponse(
        generator,
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
