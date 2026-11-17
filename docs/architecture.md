# Engram System Architecture

## 1. End-to-End Monorepo Data Flow

```mermaid
flowchart TB
    Contracts["contracts/*.schema.json\n(Single Source of Truth)"]

    subgraph AndroidApp[":app (Kotlin + Compose M3 + Nav3 + Koin 4.2)"]
        Capture["CaptureScreen\n(Single-Screen Timeline, In-Place Stream & Raw Card, Calendar Confirm)"]
        Graph["GraphScreen (Expandable ModalBottomSheet)\n(50% <-> 92% Handle, Gesture Isolation, Deponder 0.5.0)"]
        EngineSheet["EngineBottomSheet\n(Edge/Cloud Mode Switch, Resumable Range Download, RSS & Unload)"]

        Analyzer["MemoryAnalyzer\n(JSON Schema Parser + Heuristic Fallback)"]
        Router["UnifiedInferenceRouter"]

        SQLite["EngramDatabase / RoomMemoryStore\n(Room WAL + @Relation Cold Flow)"]
        Prefs["EnginePreferencesRepository\n(DataStore Config Only)"]
    end

    subgraph EdgeRuntime[":packages:edge-mnn"]
        Manager["MnnEngineManager\n(mnn-jni-serial + Mutex + onTrimMemory)"]
        Coalescer["TokenStreamCoalescer\n(32ms Tumbling Window)"]
        JNI["engram_mnn_jni.cpp\n(Step-Cancelable MNN::Llm + 16KB Page Alignment)"]
    end

    subgraph Gateway["server/ (FastAPI)"]
        Manifest["GET /api/v1/models/manifest"]
        SSE["POST /api/v1/analyze/stream"]
    end

    Capture -->|captureMemory| Router
    Router -->|StreamingPreview| Capture
    Router -->|CLOUD_COLLAB| SSE
    Router -->|OFFLINE_EDGE / fallback| Manager
    Router -->|Completed + AnalysisSource| SQLite
    SQLite --> Capture
    SQLite --> Graph
    EngineSheet -->|OkHttp Range + RandomAccessFile + .sha256| Manifest
    Manager --> JNI
    JNI --> Coalescer --> Router
    Contracts -.-> Analyzer & Manifest & SSE
    Prefs --> Router & EngineSheet
```

---

## 2. Edge Runtime Lifecycle & Native Memory State Machine

All calls to native handles execute sequentially on `mnn-jni-serial` protected by a Kotlin coroutine `Mutex`. Without prebuilt `libMNN.so` / `libllm.so` (`isBackendAvailable == false`) or verified local weights, `MnnEngineManager.loadModel` rejects the request with `EngineState.Error` instead of faking `Ready`. `EngramApplication.onTrimMemory` drives automatic background reclamation:

```mermaid
stateDiagram-v2
    [*] --> Unloaded
    Unloaded --> Loading : loadModel(verifiedDir) / lazy inference
    Loading --> Ready : createSession() != 0 (records RSS delta)
    Loading --> Error : missing .so / missing weights / native failure
    Ready --> Generating : streamGenerate()
    Generating --> Ready : step loop complete / telemetry updated
    Generating --> Releasing : critical trim + cancelActiveGeneration()
    Ready --> Releasing : USER_REQUESTED / UI_HIDDEN_TIMEOUT / MEMORY_PRESSURE
    Releasing --> Unloaded : nativeReleaseSession()
    Error --> Unloaded : unload()
```

### Memory Accounting & Trim Policy
- **Process RSS (`AndroidMemoryProvider`)**: Reads `/proc/self/statm` resident pages multiplied by `Os.sysconf(_SC_PAGESIZE)` so `MNN_LOW_MEMORY` `mmap`-backed weights are reflected accurately on both 4KB and 16KB page-size devices.
- **`TRIM_MEMORY_UI_HIDDEN (20)`**: Schedules deferred `unload(UI_HIDDEN_TIMEOUT)` after `15s` grace window; cancelled automatically if the user resumes inference.
- **`TRIM_MEMORY_RUNNING_CRITICAL (15)`** or **`level >= TRIM_MEMORY_BACKGROUND (40)`**: Immediately sets `cancel_requested` (halting `Llm::generate(1)` on the next token step) and dispatches `unload(MEMORY_PRESSURE)`.

---

## 3. Expandable Sheet Gesture Isolation & Deponder 0.5.0 Invariants

1. **Two-Stage Sheet (`BottomSheetSceneStrategy`)**: Opens at `50%` `PartiallyExpanded`, supports dragging or single-tapping the `48dp` `DragHandle` to toggle `92%` `Expanded`, and provides a close button when expanded alongside system Predictive Back.
2. **Canvas Gesture Isolation**: A sibling background drag absorber and `NestedScrollConnection` inside `GraphScreen` consume missed drags on empty canvas without intercepting node-level `.deponderDrag()`, preventing accidental sheet dismissal while keeping header/handle drags responsive.
3. **Graph Invariants (`MemoryAnalyzer.sanitizeRelations`)**:
   - Unique non-blank entity IDs under `deponderRoot`.
   - Zero self-loops (`source != target`).
   - At most one edge per unordered pair `(min(a,b), max(a,b))`, automatically merging opposing `(A -> B)` and `(B -> A)` pairs into `DeponderArrowMode.BOTH`.
