"""Pydantic models aligned 1:1 with root contracts/*.schema.json."""

from __future__ import annotations

from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class ModelFile(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1)
    url: str = Field(min_length=1)
    sizeBytes: int = Field(ge=1)
    sha256: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")


class ModelDescriptor(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1)
    name: str = Field(min_length=1)
    role: Literal["generation"]
    quantization: str = Field(min_length=1)
    sizeBytes: int = Field(ge=1)
    minRamMb: int = Field(ge=256)
    downloadUrl: str = Field(min_length=1)
    mirrorUrl: str = Field(min_length=1)
    sha256: str | None = Field(default=None, pattern=r"^[a-f0-9]{64}$")
    description: str | None = None
    files: list[ModelFile] = Field(min_length=1)


class ModelManifestResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schemaVersion: Literal["1.0.0"] = "1.0.0"
    models: list[ModelDescriptor] = Field(min_length=1)


class AnalysisStreamRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1)
    nowIso: str = Field(min_length=10)
    model: str = Field(min_length=1, default="gpt-4o-mini")


class AnalysisStreamChunk(BaseModel):
    model_config = ConfigDict(extra="forbid")

    delta: str
    done: bool
    model: str = Field(min_length=1)
    latencyMs: int = Field(ge=0)
    error: str | None = None
