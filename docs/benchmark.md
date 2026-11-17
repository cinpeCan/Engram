# On-Device MNN & Verification Benchmarks

## 1. Automated Verification Summary

| Check Suite | Scope | Command |
| :--- | :--- | :--- |
| `:packages:edge-mnn` C++/JNI & JVM Tests | `TokenStreamCoalescer` 32ms windowing, `MnnEngineManager` JNI thread serialization & `onTrimMemory` state machine, `arm64-v8a` 16KB-aligned CMake compilation | `./gradlew :packages:edge-mnn:testDebugUnitTest :packages:edge-mnn:assembleDebug` |
| `:app` Unit Tests & Debug APK | `CjkBigramTokenizer`, `VectorMath`, `RrfHybridScorer`, `MemoryAnalyzer` Deponder invariants, `UnifiedInferenceRouter` offline/cloud fallback, Compose + Nav3 APK packaging | `./gradlew testDebugUnitTest assembleDebug` |
| `server/` FastAPI Contract Tests | `/health`, `/api/v1/models/manifest`, `/api/v1/chat/stream` SSE conformance against `contracts/*.schema.json` | `server/.venv/bin/pytest server/tests` |

---

## 2. 16KB ELF Alignment Verification

After building `:packages:edge-mnn`, verify `LOAD` segment alignment of `libengram_mnn_jni.so` (and prebuilt `libMNN.so` / `libllm.so` when present):

```bash
llvm-objdump -p packages/edge-mnn/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libengram_mnn_jni.so | grep LOAD
# Expected alignment: align 2**14 (16384 bytes)
```

---

## 3. Real-Device MNN-LLM Benchmark Matrix (Template)

After running `./scripts/build_mnn_android.sh` and deploying a quantized model (`qwen2.5-0.5b-instruct-mnn` or `qwen2.5-1.5b-instruct-mnn`) onto an `arm64-v8a` device, record hardware telemetry from the `AskScreen` telemetry footer and Android Studio Profiler:

| Device / SoC | Model & Quantization | Cold Load (ms) | TTFT (ms) | Decode Speed (tok/s) | Peak Native Heap (MB) | Post-Unload Heap (MB) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| *Target Device 1* | `Qwen2.5-0.5B-Instruct-MNN (INT4)` | *TBD* | *TBD* | *TBD* | *TBD* | *TBD* |
| *Target Device 2* | `Qwen2.5-1.5B-Instruct-MNN (INT4)` | *TBD* | *TBD* | *TBD* | *TBD* | *TBD* |

### Reproduction Steps
1. Toggle **Airplane Mode** on the test device and switch `AskScreen` to **纯离线 MNN**.
2. Open **EngineBottomSheet** -> confirm model load -> observe Native Heap increase.
3. Run 3 queries in `AskScreen` and record `TTFT` and `tok/s` from each turn's telemetry bar.
4. Tap **「立即释放引擎内存」** (or background the app beyond `15s` / trigger `adb shell am send-trim-memory io.github.cinpecan.engram RUNNING_CRITICAL`) and record reclaimed Native Heap.
