"""Pydantic models aligned 1:1 with root contracts/*.schema.json."""

from __future__ import annotations

from typing import Literal
from pydantic import BaseModel, ConfigDict, Field


class AnalysisStreamRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    content: str = Field(min_length=1)
    nowIso: str = Field(min_length=10)
    model: str = Field(min_length=1, default="qwen-turbo")


class AnalysisStreamChunk(BaseModel):
    model_config = ConfigDict(extra="forbid")

    delta: str
    done: bool
    model: str = Field(min_length=1)
    latencyMs: int = Field(ge=0)
    error: str | None = None


class CalendarProposalSchema(BaseModel):
    model_config = ConfigDict(extra="forbid")

    title: str = Field(min_length=1)
    startTimeIso: str = Field(min_length=10)
    durationMinutes: int = Field(ge=5, le=480)
    location: str | None = None


class SyncRelationTriple(BaseModel):
    model_config = ConfigDict(extra="forbid")

    source: str = Field(min_length=1, max_length=32)
    target: str = Field(min_length=1, max_length=32)
    relation: str = Field(min_length=1, max_length=24)
    bidirectional: bool = False


class SyncMemoryItem(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: str = Field(min_length=1)
    content: str = Field(min_length=1)
    summary: str = ""
    tags: list[str] = Field(default_factory=list)
    createdAtMillis: int = Field(ge=0)
    updatedAtMillis: int = Field(ge=0)
    analysisStatus: Literal["PENDING", "READY"]
    calendarProposal: CalendarProposalSchema | None = None
    calendarSaved: bool = False
    analysisSource: Literal["MNN_EDGE", "CLOUD_SSE", "LOCAL_RULE_FALLBACK", "SEED"]
    telemetryBadge: str = ""
    relations: list[SyncRelationTriple] = Field(default_factory=list)


class MemorySyncRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    syncKey: str = Field(min_length=1, max_length=64)
    memories: list[SyncMemoryItem] = Field(max_length=500)


class MemorySyncResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    syncKey: str = Field(min_length=1)
    upsertedCount: int = Field(ge=0)
    totalCount: int = Field(ge=0)
    serverTimeMillis: int = Field(ge=0)
    memories: list[SyncMemoryItem]
