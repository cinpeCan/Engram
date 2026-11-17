# Engram (印迹)

> **Privacy-First On-Device AI Memory & 2D Directed Relationship Workspace**  
> Built as an AI-Native Monorepo combining **Alibaba MNN Edge Inference**, **Cloud SSE Structured Distillation**, **Human-in-the-Loop System Tools**, and **[Deponder 0.5.0](https://github.com/cinpeCan/Deponder)** 2D Physics Graphs inside a single-screen Material 3 workspace.

---

## Core Architecture Highlights

- **Monorepo & Contract-First (`contracts/`)**: Shared JSON Schemas (`model-manifest`, `memory-analysis`, `analysis-sse`) align the Kotlin Android client, `:packages:edge-mnn` C++/JNI runtime, and Python `FastAPI` gateway from a single source of truth, reinforced by root `AGENTS.md` and `.cursor/rules/*.mdc`.
- **Single-Screen Material 3 Workspace & In-Place Streaming Distillation**:
  - Focused `CaptureScreen` timeline with scroll-collapsing capsule input console (`CollapsibleBottomConsoleLayout`), optical nested corner radii, and Material 3 Expressive springs.
  - Newly captured memories stream real coalesced deltas in-place during `PENDING` state, then converge via `AnimatedContent` (with subtle haptic feedback) to distilled summaries, tags, `CalendarContract` confirmation strips, expandable raw text with copy action, and honest `AnalysisSource` telemetry badges (`MNN 0.5B`, `Cloud SSE`, or `本地规则兜底`).
- **Two-Stage Expandable 2D Graph Sheet (`Deponder 0.5.0`)**:
  - `BottomSheetSceneStrategy(expandable = true)` renders `GraphScreen` as a half-height (`50%`) to full-height (`92%`) stretchable Material 3 `ModalBottomSheet` with a `48dp` accessible drag/tap handle, predictive back support, and sibling canvas gesture isolation so `deponderDrag()` never triggers sheet dismissal.
  - Anchor-driven physics & scale updates prevent per-frame drag jank and support **Streaming Growth Replay** plus node-tap entity memory citations from Room (`memories` + `memory_relations`).
- **Koin 4.2 + Navigation 3 + Fine-Grained Stream MVI**:
  - Uses official Koin-Nav3 integration (`koin-compose-navigation3` `navigation<T>` + `koinEntryProvider()`), per-entry `ViewModelStore` scoping (`rememberViewModelStoreNavEntryDecorator()`), and `BottomSheetSceneStrategy` overlays (`GraphSheet` & `EngineSheet`).
  - Decouples high-frequency text inputs and streaming previews from low-frequency controls into specific `StateFlow` streams, driven by sealed `Intent`s and `Channel`-backed one-shot `Effect`s.
- **Zero-Stub Native Lifecycle & Memory Safety (`:packages:edge-mnn`)**:
  - Confines every JNI session call onto a single dedicated `mnn-jni-serial` coroutine dispatcher guarded by `Mutex`.
  - Refuses to enter `EngineState.Ready` without real `libMNN.so` / `libllm.so` and verified model weights on disk; runs a step-cancelable `Llm::generate(1)` decode loop piped through a **32ms tumbling window** (`TokenStreamCoalescer`).
  - Tracks process resident memory (RSS via `/proc/self/statm`), enforces **16KB ELF page-size alignment** (`-Wl,-z,max-page-size=16384`), and implements `ComponentCallbacks2.onTrimMemory` unloading (`Unloaded -> Loading -> Ready -> Generating -> Releasing`).
- **Honest Edge-Cloud Collaboration & Resumable Range Downloads**:
  - `EngineBottomSheet` manages a single focused generation model (`qwen2.5-0.5b-instruct-mnn`) via `OkHttp Range + RandomAccessFile` with `.sha256` completion markers, `Content-Range` / `Content-Length` calibration, and `llm_config.json` `embedding_file` discovery.
  - `UnifiedInferenceRouter` toggles seamlessly between **Offline MNN** and **Cloud Gateway SSE** (`/api/v1/analyze/stream`, returning HTTP `503` when `ENGRAM_CLOUD_API_KEY` is unconfigured), degrading transparently to on-device MNN or deterministic local rule extraction.

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
│   └── analysis-sse.schema.json
├── app/                            # :app Android client (Compose + Nav3 + Koin + Stream MVI + Room)
├── packages/
│   └── edge-mnn/                   # :packages:edge-mnn C++/JNI MNN runtime & RSS lifecycle guard
├── server/                         # Python FastAPI gateway (model catalog & cloud SSE analysis)
├── scripts/                        # MNN Android build, model download, and dev server scripts
└── docs/                           # Architecture specification & benchmark guide
```

---

## Quick Start

### 1. Build & Test Android Client (Out-of-the-Box)

The `:packages:edge-mnn` CMake build compiles cleanly out-of-the-box (`ENGRAM_HAS_MNN_LIB=0` until prebuilt `.so` files are placed in `jniLibs/arm64-v8a/`), and `:app` transparently displays `规则兜底` when on-device weights or native MNN libraries are absent:

```bash
./gradlew testDebugUnitTest assembleDebug
```

Open the root directory directly in Android Studio and run `:app` on an emulator or `arm64-v8a` device.

### 2. Enable Hardware MNN-LLM Inference & Quantized Weights

```bash
# Build alibaba/MNN arm64-v8a shared libraries with 16KB flexible page-size support
export ANDROID_NDK=/path/to/Android/sdk/ndk/28.2.13676358
./scripts/build_mnn_android.sh

# Fetch 4-bit Qwen2.5-0.5B MNN model weights and push to device (or download in-app)
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
