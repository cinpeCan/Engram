# On-Device MNN & Verification Benchmarks

## 1. Automated Verification Summary

| Check Suite | Scope | Command |
| :--- | :--- | :--- |
| `:packages:edge-mnn` C++/JNI & JVM Tests | `TokenStreamCoalescer` 32ms windowing, `MnnEngineManager` strict validation (rejects missing backend/blank dir), JNI thread serialization & `onTrimMemory` state machine, `arm64-v8a` 16KB-aligned CMake compilation | `./gradlew :packages:edge-mnn:testDebugUnitTest :packages:edge-mnn:assembleDebug` |
| `:app` Unit Tests & Debug APK | `MemoryAnalyzer` JSON extraction & Deponder invariants, `UnifiedInferenceRouter` honest MNN/SSE/fallback routing, `EngramNavigator`, `CaptureViewModel`, `GraphViewModel`, `EngineViewModel`, Compose + Nav3 APK packaging | `./gradlew testDebugUnitTest assembleDebug` |
| `server/` FastAPI Contract Tests | `/health`, `/api/v1/memories/sync`, `/api/v1/analyze/stream` HTTP 503 unconfigured & OpenAI SSE conformance against `contracts/*.schema.json` | `server/.venv/bin/pytest server/tests` |

---

## 2. 16KB ELF Alignment Verification

After building `:packages:edge-mnn`, verify `LOAD` segment alignment of `libengram_mnn_jni.so` (and prebuilt `libMNN.so` / `libllm.so` / `libMNN_Express.so` when present):

```bash
llvm-objdump -p packages/edge-mnn/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libengram_mnn_jni.so | grep LOAD
# Expected alignment: align 2**14 (16384 bytes)
```

---

## 3. Real-Device MNN-LLM Benchmark Matrix (Template)

After running `./scripts/build_mnn_android.sh` and deploying `qwen2.5-0.5b-instruct-mnn` onto an `arm64-v8a` device, record hardware telemetry from the `CaptureScreen` card badge and `EngineBottomSheet` RSS readout:

| Device / SoC | Model & Quantization | Cold Load (ms) | TTFT (ms) | Decode Speed (tok/s) | Peak Process RSS (MB) | Post-Unload RSS (MB) |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| *Target Device 1* | `Qwen2.5-0.5B-Instruct-MNN (INT4)` | *TBD* | *TBD* | *TBD* | *TBD* | *TBD* |

### Reproduction Steps
1. Toggle **Airplane Mode** on the test device and select **端侧离线** in `EngineBottomSheet`.
2. Download `Qwen2.5-0.5B Instruct · INT4` and tap **加载模型** -> confirm `EngineStatusPill` turns green and record RSS increase (`modelFootprintBytes`).
3. Submit 3 notes in `CaptureScreen` and observe live 32ms-coalesced streaming inside each `PENDING` card; record `TTFT` and `tok/s` from each settled card's telemetry badge.
4. Tap **「释放内存」** (or background the app beyond `15s` / trigger `adb shell am send-trim-memory io.github.cinpecan.engram RUNNING_CRITICAL`) and record reclaimed RSS.
