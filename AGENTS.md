# Engram — Monorepo Engineering Charter (`AGENTS.md`)

> **Engram** is a privacy-first, on-device AI memory & 2D relationship graph application structured as an AI-native Monorepo.

---

## 1. WHAT — Repository Topology

```text
engram/
├── AGENTS.md                       # Global architecture & AI collaboration rules (source of truth)
├── CLAUDE.md                       # Pointer & quick command reference
├── .cursor/rules/                  # Path-scoped modular engineering constraints (.mdc)
├── contracts/                      # Shared JSON Schemas across Android, Edge Runtime, and Server
│   ├── memory-analysis.schema.json
│   ├── analysis-sse.schema.json
│   └── memory-sync.schema.json
├── app/                            # :app Android client (io.github.cinpecan.engram)
├── packages/
│   └── edge-mnn/                   # :packages:edge-mnn standalone MNN C++/JNI runtime library
├── server/                         # Python FastAPI service (multi-device SQLite sync & cloud SSE)
├── scripts/                        # MNN native build, model fetch, and local dev server scripts
└── docs/                           # Architecture specification & real-device MNN benchmarks
```

---

## 2. WHY — Core Architectural Principles

1. **White-Box Memory (Transparent & Verifiable)**:
   - Every captured memory is persisted locally in Jetpack Room (`WAL` + `@Relation` + directed relation triples) as the Single Source of Truth (SSOT), exposed via lazy cold `Flow`s from `MemoryDao`.
   - Each memory records its true `AnalysisSource` (`MNN_EDGE`, `CLOUD_SSE`, `LOCAL_RULE_FALLBACK`, or `SEED`) alongside structured outputs: scannable bulleted summary, tags, calendar proposals, and 2D relation triples rendered via `Deponder 0.6.0`. Card telemetry badges are shown only for model-driven runs (`MNN_EDGE` / `CLOUD_SSE`), while `SEED` cards display a subtle `"Sample"` origin label — never render redundant fallback labels on cards.
   - **Summary-First & De-emphasized Raw Text**: Timeline cards have NO separate card title and NEVER inline raw user text (`Source`) directly. Multi-point summaries use concise `\n`-separated `• ` bullets (avoid dense multi-clause sentences); inspecting raw input is intentionally tucked into a quiet expanded-card footer trigger that opens Navigation 3's `RawMemorySheet` bottom sheet.
   - Zero fake states: when native MNN libraries or weights are absent, or when the cloud gateway has no API key, the system never fakes `Ready` green indicators or simulated model streaming.
2. **Human-in-the-Loop Tool Execution & Reactive System Calendar Sync**:
   - On-device action proposals (such as inserting a system calendar event via `CalendarContract.Events`) MUST surface an explicit confirmation card in UI before invoking system intents (`ACTION_INSERT`). Never mutate user calendar or external state silently (`WRITE_CALENDAR` is intentionally omitted).
   - Never optimistically mark `calendarSaved = true` on `startActivity` success. Subscribe reactively to `CalendarContract.CONTENT_URI` via `SystemCalendarObserver` (cold `callbackFlow` + `ContentObserver` + `WhileSubscribed(5_000L)`, zero imperative `ON_RESUME` polling) using `READ_CALENDAR` and `[engram:<memoryId>]` markers so canceling or deleting in System Calendar reverts `"Added"` automatically and tapping a synced card opens `ACTION_VIEW` on the real `eventId`.
3. **Contract-First Cross-Stack Alignment**:
   - All shared data payloads between `:app`, `:packages:edge-mnn`, and `server/` are specified in `contracts/*.schema.json` before modifying Kotlin DTOs or Python Pydantic models.
4. **Native Memory & Thread Safety First**:
   - Quantized LLM weights consume hundreds of MB via `mmap` / native allocations. Lifecycle transitions (`onTrimMemory`), single-threaded JNI confinement, step-cancelable decoding, and 16KB ELF page alignment are non-negotiable invariants.

---

## 3. HOW — Hard Engineering Rules & Verification

### A. Android Client (`:app`)
- **Stack**: Kotlin, Jetpack Compose (Material 3 `MaterialTheme` + custom M3 Expressive spring shape-morphing surfaces & low-glare `LocalRippleConfiguration`), Navigation 3 (`rememberNavBackStack` user-owned back stack, `rememberViewModelStoreNavEntryDecorator()`, single-screen `Capture` workspace + `BottomSheetSceneStrategy` overlays for single-memory expandable `GraphSheet`, `RawMemorySheet`, and `EngineSheet`), Koin 4.2 (`koin-compose-navigation3` `navigation<T>` + `koinEntryProvider()`), Fine-Grained Stream MVI (`sealed interface` Intent + `Channel` Effect + `combine` / `flatMapLatest` + `SharingStarted.WhileSubscribed(5_000L)`), Jetpack Room (`WAL` + `@Relation` cold `Flow`), DataStore Preferences (config only), OkHttp + Kotlinx Serialization, `io.github.cinpecan:deponder-compose:0.6.0`.
- **UI/UX Invariants (Do Not Regress)**:
  - **True Edge-to-Edge Transparent System Bars & Inset Discipline**: `MainActivity` and `EngramTheme` apply `SystemBarStyle` with `android.graphics.Color.TRANSPARENT` for both status and navigation bars alongside `isStatusBarContrastEnforced = false` and `isNavigationBarContrastEnforced = false` (also applied inside `BottomSheetScene`'s `DialogWindowProvider` window and cold-start `values`/`values-night` `themes.xml`). `Scaffold` restricts `contentWindowInsets` to horizontal cutouts/system bars (`pinnedScrollBehavior` translucent `TopAppBar`), exposes `LocalWorkspaceContentPadding` so `CaptureScreen`'s `LazyColumn` renders unclipped edge-to-edge behind system bars, and `CollapsibleBottomConsoleLayout` applies a single `WindowInsets.ime.union(WindowInsets.navigationBars)` inset over a soft vertical scrim. The composer is a single symmetrical full-width rounded container enclosing an optically centered `ArrowUpward` submit button (`Alignment.BottomEnd`), computing multiline state via `rememberTextMeasurer()` to apply internal bottom padding (`44.dp`) without layout oscillation or text occlusion. Tapping blank list areas, tapping cards while keyboard is open, or scrolling MUST dismiss the soft keyboard.
  - **M3 Expressive Tactile Feedback**: Interactive cards, pills, and icon actions use `ExpressiveCardSurface` / `ExpressivePillSurface` (spring corner shape morphing, gentle scale, and tonal shift with `indication = null`) rather than harsh legacy radial ripples.
  - **Single-Memory Frameless `GraphSheet` & Minimalist `EngineSheet`**: `GraphSheet(memoryId)` MUST bind strictly to a single memory — no global unbound top-bar graph entry, no internal memory switcher chips, no repeated summary header, no shrink button, and no inner bordered rectangle. The sheet-level close (`✕`) control belongs exclusively in `BottomSheetScene`'s top `dragHandle` chrome bar; only a quiet floating icon-only `Replay` button may sit inside the frameless canvas. `EngineSheet` MUST likewise remain frameless (no nested `Card` borders), download `qwen2.5-0.5b-instruct-mnn` directly from ModelScope/HuggingFace mirrors without routing manifests through the app server, use the app server for reactive Bearer session checks (`GET /api/v1/auth/status`), multi-device SQLite sync & delete propagation (`POST /api/v1/memories/sync`, `POST /api/v1/memories/delete`), and Cloud SSE, and avoid exposing developer meta-jargon (e.g. "honest fallback", "bundled .so required", "same-repo FastAPI") in user-facing copy.
- **No Blocking I/O on Main**: All Room queries, file streaming (`RandomAccessFile`), SHA-256 verification, and network calls execute on `Dispatchers.IO` or `Dispatchers.Default`.
- **Ask (On-Device GraphRAG) Invariants**:
  - Zero extra UI surface: asking is triggered only by a composer input ending with `?` (ASCII or full-width). It opens the `AnswerSheet(question)` overlay and is NEVER persisted as a memory. No tabs, toggles, or second input field.
  - Retrieval = Room FTS4 (`tokenize=porter`, external content over `memories`) lexical channel + 2-hop BFS over the entity graph built from all READY memories' relation triples, fused with Reciprocal Rank Fusion (`k = 60`, top 6). FTS MATCH input MUST come from `QueryTokenizer.buildFtsMatchExpression` (sanitized lowercase tokens); never pass raw user text to MATCH.
  - Any change to `MemoryFtsEntity` or the memories table MUST ship a migration that reproduces Room's generated FTS SQL and MUST be covered by the Robolectric `EngramDatabaseMigrationTest` (no destructive data loss).
  - Generation follows the distillation fallback chain: cloud `POST /api/v1/answer/stream` → on-device MNN → retrieval-only with an explicit reason. Never fabricate an answer; answers cite `[n]` matching citation order, and the request `nowIso` carries a UTC offset.
  - The Reasoning Graph renders only walked trace edges of cited memories (merged by unordered pair, no self-loops) with the shared `GraphRubberPhysics` / `GraphPlanetPhysics`, isolated from sheet-dismiss gestures.
- **Deponder Graph Invariants**: Nodes passed to `DeponderDefaultRubber` / `Modifier.deponderPlanet` under a `deponderRoot` MUST have unique IDs, zero self-loops (`source != target`), and merged bidirectional edges (undirected key `(min(a,b), max(a,b))`). Background drags inside the canvas are isolated from `ModalBottomSheet` vertical dismissal gestures, and `onSizeChanged` shifts planet `y` by `Δheight / 2` across half/full transitions so the graph stays centered vertically.

### B. On-Device Runtime (`:packages:edge-mnn`)
- **Single-Thread JNI Confinement**: ALL calls into `NativeBridge` MUST run on the dedicated single-thread `mnn-jni-serial` dispatcher guarded by a `Mutex`. Never invoke native session methods concurrently from arbitrary coroutine threads.
- **No Stub Sessions or Fake Embeddings**: `nativeCreateSession` MUST return `0` when `ENGRAM_HAS_MNN_LIB == 0` or `config.json` is missing; `MnnEngineManager.loadModel` MUST reject blank model paths or unavailable backends rather than entering `EngineState.Ready`.
- **Step-Cancelable Native Loop**: When `ENGRAM_HAS_MNN_LIB == 1`, `nativeGenerateStream` runs `Llm::generate(1)` in a loop checking `llm->stoped()`, `cancel_requested`, and `max_tokens`.
- **16KB Page Size Alignment**: `CMakeLists.txt` MUST keep `-Wl,-z,max-page-size=16384` for Android 15/16 compatibility and link `libMNN_Express.so` when present.
- **Frame-Paced Streaming**: High-frequency C++ token callbacks MUST pass through `TokenStreamCoalescer` (32ms coalescing window) before updating UI `StateFlow` to avoid per-token Compose recomposition jank.
- **Memory State Machine**: Track process RSS via `/proc/self/statm` (`AndroidMemoryProvider`), respect `Unloaded -> Loading -> Ready -> Generating -> Releasing -> Unloaded` transitions, and wire `Application.onTrimMemory` to `MnnEngineManager.onTrimMemory`.

### C. Verification Commands

Run these checks after modifying corresponding modules:

```bash
# Android unit tests (incl. Robolectric Room FTS migration) & debug APK build
./gradlew testDebugUnitTest assembleDebug

# Edge runtime C++/JNI & Kotlin unit tests
./gradlew :packages:edge-mnn:testDebugUnitTest :packages:edge-mnn:assembleDebug

# Python FastAPI schema & endpoint contract tests
server/.venv/bin/pytest server/tests

# English-only gate (README_zh.md is the only allowed exception)
git grep -I -n -P "[\x{4e00}-\x{9fff}]" -- . ":!README_zh.md" ":!README.md"
```
