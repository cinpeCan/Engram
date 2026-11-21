# On-Device MNN & Verification Benchmarks

## 1. Automated Verification Summary

| Check Suite | Scope | Command |
| :--- | :--- | :--- |
| `:packages:edge-mnn` C++/JNI & JVM Tests | `TokenStreamCoalescer` 32ms windowing, `MnnEngineManager` strict validation (rejects missing backend/blank dir), JNI thread serialization & `onTrimMemory` state machine, `arm64-v8a` 16KB-aligned CMake compilation | `./gradlew :packages:edge-mnn:testDebugUnitTest :packages:edge-mnn:assembleDebug` |
| `:app` Unit Tests & Debug APK | `MemoryAnalyzer` JSON extraction & Deponder invariants, `PartialJsonSummaryExtractor` typewriter streaming, `GraphRagRetriever` (FTS + 2-hop graph + RRF on the seed storyline), `QueryTokenizer` MATCH sanitization, `AnswerRepository` cloud → MNN → retrieval-only fallback, Robolectric `EngramDatabaseMigrationTest` (Room v4 → v5 FTS4 porter, no data loss), `DefaultCloudEndpointProvider` AES-GCM decryption, `CloudSessionMonitor` debounced Bearer verification, `SystemCalendarObserver` matching, `UnifiedInferenceRouter`, `MemoryRepository` tombstone sync, ViewModels, Compose + Nav3 APK packaging | `./gradlew testDebugUnitTest assembleDebug` |
| `server/` FastAPI Contract Tests | `/health`, `/healthz`, `/api/v1/auth/status`, `/api/v1/memories/sync`, `/api/v1/memories/delete`, `/api/v1/analyze/stream`, `/api/v1/answer/stream` (Vertex AI Gemini & OpenAI-compatible SSE, HTTP 401/422/429/503, HMAC token CLI, tenant isolation & quota refund) against `contracts/*.schema.json` | `server/.venv/bin/pytest server/tests` |

---

## 2. 16KB ELF Alignment Verification

After building `:packages:edge-mnn`, verify `LOAD` segment alignment of `libengram_mnn_jni.so` (and prebuilt `libMNN.so` / `libllm.so` / `libMNN_Express.so` when present):

```bash
llvm-objdump -p packages/edge-mnn/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libengram_mnn_jni.so | grep LOAD
# Expected alignment: align 2**14 (16384 bytes)
```

---

## 3. Real-Device MNN-LLM Benchmark Matrix

Measured on a release build after running `./scripts/build_mnn_android.sh` and deploying `qwen2.5-0.5b-instruct-mnn` onto an `arm64-v8a` device. Values are read from the `CaptureScreen` card telemetry badge and the `EngineBottomSheet` RSS readout. Cells marked `—` have not been measured yet.

| Device / SoC | Engine & Model | TTFT (ms) | Decode Speed (tok/s) | Model RSS Delta (MB) |
| :--- | :--- | :---: | :---: | :---: |
| Samsung Galaxy F52 5G (`SM-E5260`) / Snapdragon 750G | On-device MNN · `Qwen2.5-0.5B-Instruct-MNN (INT4)` | 1,626 – 1,659 | 14.3 – 20.1 | +290 |
| Samsung Galaxy F52 5G (`SM-E5260`) / Snapdragon 750G | Cloud SSE · Vertex AI `gemini-3.5-flash-lite` (`global`) | 899 – 967 | 103.9 – 116.5 ¹ | — |

¹ Cloud decode speed is estimated client-side from streamed character count (`≈ chars / 2` tokens) over wall-clock time, including network latency; on-device figures come from native MNN token counters.

> Cold load time, peak process RSS, and post-unload RSS are not yet recorded; follow the steps below to extend the matrix.

### Reproduction Steps
1. Toggle **Airplane Mode** on the test device and select **On-device** in `EngineBottomSheet`.
2. Download `Qwen2.5-0.5B Instruct · INT4` and tap **Load model** -> confirm `EngineStatusPill` turns green and record RSS increase (`modelFootprintBytes`).
3. Submit 3 notes in `CaptureScreen` and observe live 32ms-coalesced streaming inside each `PENDING` card; record `TTFT` and `tok/s` from each settled card's telemetry badge.
4. Tap **Free memory** (or background the app beyond `15s` / trigger `adb shell am send-trim-memory io.github.cinpecan.engram RUNNING_CRITICAL`) and record reclaimed RSS.
