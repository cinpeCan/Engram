# Engram System Architecture

## 1. End-to-End Monorepo Data Flow

```mermaid
flowchart TB
    Contracts["contracts/*.schema.json\n(Single Source of Truth)"]

    subgraph AndroidApp[":app (Kotlin + Compose + Nav3 1.2.1)"]
        Capture["CaptureScreen\n(MVI + Calendar Confirmation Card)"]
        Graph["GraphScreen\n(Deponder 0.5.0 2D Capsules & Springs)"]
        Ask["AskScreen\n(Offline/Cloud Switch + Telemetry)"]
        Sheet["EngineBottomSheet\n(Resumable Range Download + Memory Release)"]

        Analyzer["MemoryAnalyzer\n(JSON Schema + Heuristic Fallback)"]
        Retriever["RrfHybridScorer\n(CJK Bigram FTS4 + Cosine)"]
        Router["UnifiedInferenceRouter"]

        SQLite["EngramSqliteStore\n(WAL + FTS4 + BLOB + Relations)"]
        Prefs["EnginePreferencesRepository\n(DataStore Config Only)"]
    end

    subgraph EdgeRuntime[":packages:edge-mnn"]
        Manager["MnnEngineManager\n(mnn-jni-serial + Mutex + onTrimMemory)"]
        Coalescer["TokenStreamCoalescer\n(32ms Tumbling Window)"]
        JNI["engram_mnn_jni.cpp\n(16KB Page Alignment)"]
    end

    subgraph Gateway["server/ (FastAPI)"]
        Manifest["GET /api/v1/models/manifest"]
        SSE["POST /api/v1/chat/stream"]
    end

    Capture --> Analyzer --> Router
    Analyzer --> SQLite
    Graph --> SQLite
    Ask --> Router
    Router --> Retriever --> SQLite
    Router -->|OFFLINE_EDGE| Manager
    Router -->|CLOUD_COLLAB| SSE
    Sheet -->|OkHttp Range + RandomAccessFile| Manifest
    Manager --> JNI
    JNI --> Coalescer --> Router
    Contracts -.-> Analyzer & Manifest & SSE
    Prefs --> Ask & Sheet
```

---

## 2. Edge Runtime Lifecycle & Native Memory State Machine

All calls to native handles execute sequentially on `mnn-jni-serial` protected by a Kotlin coroutine `Mutex`. `EngramApplication.onTrimMemory` drives automatic background reclamation:

```mermaid
stateDiagram-v2
    [*] --> Unloaded
    Unloaded --> Loading : loadModel() / lazy inference
    Loading --> Ready : createSession() != 0
    Loading --> Error : native failure
    Ready --> Generating : streamGenerate()
    Generating --> Ready : stream complete / telemetry updated
    Generating --> Releasing : critical trim + cancelActiveGeneration()
    Ready --> Releasing : USER_REQUESTED / UI_HIDDEN_TIMEOUT / MEMORY_PRESSURE
    Releasing --> Unloaded : nativeReleaseSession()
    Error --> Unloaded : unload()
```

### Memory Trim Policy
- `TRIM_MEMORY_UI_HIDDEN (20)`: Schedules deferred `unload(UI_HIDDEN_TIMEOUT)` after `15s` grace window; cancelled automatically if the user resumes inference.
- `TRIM_MEMORY_RUNNING_CRITICAL (15)` or `level >= TRIM_MEMORY_BACKGROUND (40)`: Immediately signals `nativeRequestCancel(handle)` and dispatches `unload(MEMORY_PRESSURE)`.

---

## 3. Hybrid Local RAG Formula (`CJK Bigram + Vector RRF`)

1. **Lexical Branch (`SQLite FTS4`)**: `CjkBigramTokenizer` emits overlapping 2-character CJK shingles (`"采购合同"` -> `"采购" "购合" "合同"`) alongside lowercased ASCII word tokens, using prefix queries (`"张*"`) for single-character CJK lookups.
2. **Semantic Branch (`FloatArray BLOB`)**: L2-normalized embeddings are stored in `memory_embeddings` and scored via cosine dot product in memory (`< 2ms` up to 10k personal notes without native vector index dependencies).
3. **Reciprocal Rank Fusion (`k = 60`)**:

$$\text{RRF}(d) = \frac{1}{60 + \text{rank}_{\text{fts}}(d)} + \frac{1}{60 + \text{rank}_{\text{vec}}(d)}$$

---

## 4. Deponder 0.5.0 Graph Invariants

Before passing triples into `DeponderDefaultRubber` / `Modifier.deponderPlanet`, `MemoryAnalyzer.sanitizeRelations` guarantees:
1. Unique non-blank entity IDs under `deponderRoot`.
2. Zero self-loops (`source != target`).
3. At most one edge per unordered pair `(min(a,b), max(a,b))`, automatically merging opposing `(A -> B)` and `(B -> A)` pairs into `DeponderArrowMode.BOTH`.
