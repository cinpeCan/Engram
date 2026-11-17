"""Pydantic models aligned 1:1 with root contracts/*.schema.json."""

from __future__ import annotations

from typing import Literal
from pydantic import BaseModel, Field


class ModelFile(BaseModel):
    name: str = Field(min_length=1)
    url: str = Field(min_length=1)
    sizeBytes: int = Field(ge=1)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")


class ModelDescriptor(BaseModel):
    id: str = Field(min_length=1)
    name: str = Field(min_length=1)
    role: Literal["generation", "embedding"]
    quantization: str = Field(min_length=1)
    sizeBytes: int = Field(ge=1)
    minRamMb: int = Field(ge=256)
    downloadUrl: str = Field(min_length=1)
    mirrorUrl: str = Field(min_length=1)
    sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    description: str | None = None
    files: list[ModelFile] = Field(min_length=1)


class ModelManifestResponse(BaseModel):
    schemaVersion: Literal["1.0.0"] = "1.0.0"
    models: list[ModelDescriptor] = Field(min_length=1)


class CitationItem(BaseModel):
    memoryId: str = Field(min_length=1)
    content: str = Field(min_length=1)


class ChatStreamRequest(BaseModel):
    query: str = Field(min_length=1)
    citations: list[CitationItem] = Field(default_factory=list)
    model: str = Field(min_length=1, default="engram-cloud-fallback")
    stream: bool = True


class ChatStreamChunk(BaseModel):
    delta: str
    done: bool
    model: str = Field(min_length=1)
    latencyMs: int = Field(ge=0)
