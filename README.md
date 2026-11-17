# Engram (印迹)

> **Privacy-First On-Device AI Memory & 2D Directed Relationship Workspace**  
> Built as an AI-Native Monorepo combining **Alibaba MNN Edge Inference**, **White-Box Hybrid RAG**, **Human-in-the-Loop System Tools**, and **[Deponder 0.5.0](https://github.com/cinpeCan/Deponder)** 2D Physics Graphs.

---

## Core Architecture Highlights

- **Monorepo & Contract-First (`contracts/`)**: Shared JSON Schemas (`model-manifest`, `memory-analysis`, `chat-sse`) align the Kotlin Android client, `:packages:edge-mnn` C++/JNI runtime, and Python `FastAPI` gateway from a single source of truth, reinforced by root `AGENTS.md` and `.cursor/rules/*.mdc`.
- **White-Box Memory (SQLite WAL + FTS4 + Cosine + RRF)**:
  - Instant local persistence across 4 relational/FTS tables (`memories`, `memories_fts`, `memory_embeddings`, `memory_relations`), keeping `DataStore<Preferences>` strictly for lightweight engine configuration.
  - Overlapping **CJK Bigram** tokenization + L2-normalized `FloatArray` `BLOB` cosine similarity fused via **Reciprocal Rank Fusion (RRF, `k=60`)**, surfacing source memory citations above every answer.
- **2D Directed Relation Graph via [`io.github.cinpecan:deponder-compose:0.5.0`](https://github.com/cinpeCan/Deponder)**:
  - Renders extracted `(Entity A) ──relation──▶ (Entity B)` triples on a 2D force-directed plane using Material 3 rounded capsules and `DeponderDefaultRubber` springs—avoiding overlapping edge labels on mobile viewports and dropping to 0% CPU at rest.
  - Includes **Streaming Growth Replay**, live scale/elasticity controls, and node-tap RAG citation lookup.
- **Native Heap & Thread Safety (`:packages:edge-mnn`)**:
  - Confines every JNI session call onto a single dedicated `mnn-jni-serial` coroutine dispatcher guarded by `Mutex`.
  - Coalesces rapid C++ token callbacks in a **32ms tumbling window** (`TokenStreamCoalescer`) to prevent per-token Compose recomposition jank.
  - Enforces **16KB ELF page-size alignment** (`-Wl,-z,max-page-size=16384`) and implements a `ComponentCallbacks2.onTrimMemory` unloading state machine (`Unloaded -> Loading -> Ready -> Generating -> Releasing`).
- **Human-in-the-Loop Calendar Agent & Edge-Cloud Collaboration**:
  - Time-bearing notes surface an explicit confirmation card before launching `CalendarContract.Events` intents.
  - `AskScreen` toggles seamlessly between **Offline MNN Edge** and **Cloud Gateway SSE** (with automatic offline fallback when the gateway is unreachable) and displays real-time `TTFT · tok/s · Native Heap` telemetry.

---

## Repository Structure

```text
engram/
├── AGENTS.md                       # Global AI-native engineering charter & invariants
├── CLAUDE.md                       # Quick command reference
├── .cursor/rules/                  # Path-scoped engineering constraints (.mdc)
├── contracts/                      # Shared JSON Schemas (Draft 2020-12)
│   ├── model-manifest.schema.json
│   ├── memory-analysis.schema.json
│   └── chat-sse.schema.json
├── app/                            # :app Android client (Compose + Nav3 1.2.1 + MVI + SQLite)
├── packages/
│   └── edge-mnn/                   # :packages:edge-mnn C++/JNI MNN runtime & lifecycle guard
├── server/                         # Python FastAPI gateway (model catalog & SSE cloud fallback)
├── scripts/                        # MNN Android build, model download, and dev server scripts
└── docs/                           # Architecture specification & benchmark guide
```

---

## Quick Start

### 1. Build & Test Android Client (Out-of-the-Box)

The `:packages:edge-mnn` CMake build compiles cleanly out-of-the-box with a lightweight native JNI runtime fallback when prebuilt `libMNN.so` / `libllm.so` are not yet placed in `jniLibs/arm64-v8a/`:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Open the root directory directly in Android Studio and run `:app` on an emulator or `arm64-v8a` device.

### 2. Optional: Enable Hardware MNN-LLM Inference & Quantized Weights

```bash
# Build alibaba/MNN arm64-v8a shared libraries with 16KB flexible page-size support
export ANDROID_NDK=/path/to/Android/sdk/ndk/28.2.13676358
./scripts/build_mnn_android.sh

# Fetch 4-bit Qwen2.5 MNN model weights and push to device (or download in-app)
python3 scripts/fetch_models.py --model-id qwen2.5-0.5b-instruct-mnn --adb-push
```

### 3. Run Same-Repo FastAPI Gateway & Contract Tests

```bash
# Start gateway on http://0.0.0.0:8787 (Android emulator connects via http://10.0.2.2:8787)
./scripts/dev_server.sh

# Verify FastAPI endpoints against contracts/*.schema.json
server/.venv/bin/pytest server/tests
```

---

## Documentation

- [System Architecture & State Machines](docs/architecture.md)
- [On-Device MNN Benchmark Guide](docs/benchmark.md)
