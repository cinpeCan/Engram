# Engram — Monorepo Engineering Charter (`AGENTS.md`)

> **Engram (印迹)** is a privacy-first, on-device AI memory & 2D relationship graph application structured as an AI-native Monorepo.

---

## 1. WHAT — Repository Topology

```text
engram/
├── AGENTS.md                       # Global architecture & AI collaboration rules (source of truth)
├── CLAUDE.md                       # Pointer & quick command reference
├── .cursor/rules/                  # Path-scoped modular engineering constraints (.mdc)
├── contracts/                      # Shared JSON Schemas across Android, Edge Runtime, and Server
│   ├── model-manifest.schema.json
│   ├── memory-analysis.schema.json
│   └── chat-sse.schema.json
├── app/                            # :app Android client (io.github.cinpecan.engram)
├── packages/
│   └── edge-mnn/                   # :packages:edge-mnn standalone MNN C++/JNI runtime library
├── server/                         # Python FastAPI gateway (model manifest & cloud SSE fallback)
├── scripts/                        # MNN native build, model fetch, and local dev server scripts
└── docs/                           # Architecture specification & real-device MNN benchmarks
```

---

## 2. WHY — Core Architectural Principles

1. **White-Box Memory (Transparent & Verifiable)**:
   - Every captured memory is persisted locally in Jetpack Room (`WAL` + `@Fts4` + `@Relation` + L2-normalized embedding `BLOB` + directed relation triples) as the Single Source of Truth (SSOT), exposed via lazy cold `Flow`s from `MemoryDao`.
   - AI outputs are structured and inspectable: auto-extracted tags, calendar proposals, 2D relation triples rendered via `Deponder 0.5.0`, and citation-grounded RAG answers.
2. **Human-in-the-Loop Tool Execution**:
   - On-device action proposals (such as inserting a system calendar event via `CalendarContract`) MUST surface an explicit confirmation card in UI before invoking system intents. Never mutate user calendar or external state silently.
3. **Contract-First Cross-Stack Alignment**:
   - All shared data payloads between `:app`, `:packages:edge-mnn`, and `server/` are specified in `contracts/*.schema.json` before modifying Kotlin DTOs or Python Pydantic models.
4. **Native Memory & Thread Safety First**:
   - Quantized LLM weights consume hundreds of MB in Native Heap. Lifecycle transitions (`onTrimMemory`), single-threaded JNI confinement, and 16KB ELF page alignment are non-negotiable invariants.

---

## 3. HOW — Hard Engineering Rules & Verification

### A. Android Client (`:app`)
- **Stack**: Kotlin, Jetpack Compose (Material 3), Navigation 3 (`SnapshotStateList` user-owned back stack, `rememberViewModelStoreNavEntryDecorator()`, Material 3 Adaptive `ListDetailSceneStrategy`), Koin 4.2 (`koin-compose-navigation3` `navigation<T>` + `koinEntryProvider()`), Fine-Grained Stream MVI (`sealed interface` Intent + `Channel` Effect + `combine` / `flatMapLatest` + `SharingStarted.WhileSubscribed(5_000L)`), Jetpack Room (`WAL` + `@Fts4` + `@Relation` cold `Flow`), DataStore Preferences (config only), OkHttp + Retrofit + Kotlinx Serialization, `io.github.cinpecan:deponder-compose:0.5.0`.
- **No Blocking I/O on Main**: All Room queries, embedding math, file streaming (`RandomAccessFile`), and network calls execute on `Dispatchers.IO` or `Dispatchers.Default`.
- **Deponder Graph Invariants**: Nodes passed to `DeponderDefaultRubber` / `Modifier.deponderPlanet` under a `deponderRoot` MUST have unique IDs, zero self-loops (`source != target`), and merged bidirectional edges (undirected key `(min(a,b), max(a,b))`).

### B. On-Device Runtime (`:packages:edge-mnn`)
- **Single-Thread JNI Confinement**: ALL calls into `NativeBridge` MUST run on the dedicated single-thread `mnn-jni-serial` dispatcher guarded by a `Mutex`. Never invoke native session methods concurrently from arbitrary coroutine threads.
- **16KB Page Size Alignment**: `CMakeLists.txt` MUST keep `-Wl,-z,max-page-size=16384` for Android 15/16 compatibility.
- **Frame-Paced Streaming**: High-frequency C++ token callbacks MUST pass through `TokenStreamCoalescer` (32ms coalescing window) before updating UI `StateFlow` to avoid per-token Compose recomposition jank.
- **Memory State Machine**: Respect `Unloaded -> Loading -> Ready -> Generating -> Releasing -> Unloaded` transitions and wire `Application.onTrimMemory` to `MnnEngineManager.onTrimMemory`.

### C. Verification Commands

Run these checks after modifying corresponding modules:

```bash
# Android unit tests & debug APK build
./gradlew testDebugUnitTest assembleDebug

# Edge runtime C++/JNI & Kotlin unit tests
./gradlew :packages:edge-mnn:testDebugUnitTest :packages:edge-mnn:assembleDebug

# Python FastAPI schema & endpoint contract tests
server/.venv/bin/pytest server/tests
```
