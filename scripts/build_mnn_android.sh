#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MNN_DIR="$ROOT_DIR/third_party/MNN"
OUT_JNILIBS="$ROOT_DIR/packages/edge-mnn/src/main/jniLibs/arm64-v8a"
OUT_INCLUDE="$ROOT_DIR/packages/edge-mnn/src/main/cpp/include"

if [[ -z "${ANDROID_NDK:-}" ]]; then
  echo "[engram] Please export ANDROID_NDK=/path/to/Android/sdk/ndk/<version>"
  exit 1
fi

if [[ ! -d "$MNN_DIR" ]]; then
  echo "[engram] Cloning alibaba/MNN into $MNN_DIR..."
  mkdir -p "$(dirname "$MNN_DIR")"
  git clone --depth 1 https://github.com/alibaba/MNN.git "$MNN_DIR"
fi

BUILD_DIR="$MNN_DIR/project/android/build_64"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

echo "[engram] Building MNN + MNN-LLM arm64-v8a with 16KB flexible page size..."
../build_64.sh \
  "-DMNN_LOW_MEMORY=true \
   -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true \
   -DMNN_BUILD_LLM=true \
   -DMNN_SUPPORT_TRANSFORMER_FUSE=true \
   -DMNN_OPENCL=true \
   -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
   -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"

mkdir -p "$OUT_JNILIBS" "$OUT_INCLUDE/llm"
find "$BUILD_DIR" -maxdepth 4 -name "libMNN.so" -exec cp {} "$OUT_JNILIBS/" \;
find "$BUILD_DIR" -maxdepth 4 -name "libllm.so" -exec cp {} "$OUT_JNILIBS/" \;
find "$BUILD_DIR" -maxdepth 4 -name "libMNN_Express.so" -exec cp {} "$OUT_JNILIBS/" \;
cp -R "$MNN_DIR/include/MNN" "$OUT_INCLUDE/"
cp -R "$MNN_DIR/transformers/llm/engine/include/llm/"* "$OUT_INCLUDE/llm/"

echo "[engram] Done. Native libs placed under $OUT_JNILIBS"
