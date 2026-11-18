#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MNN_DIR="$ROOT_DIR/third_party/MNN"
BUILD_DIR="$MNN_DIR/build_mnn_arm64"
OUT_JNILIBS="$ROOT_DIR/packages/edge-mnn/src/main/jniLibs/arm64-v8a"
OUT_INCLUDE="$ROOT_DIR/packages/edge-mnn/src/main/cpp/include"

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
if [[ -n "${ENGRAM_ANDROID_NDK:-}" && -d "${ENGRAM_ANDROID_NDK}" ]]; then
  ANDROID_NDK="$ENGRAM_ANDROID_NDK"
elif [[ -d "$ANDROID_SDK_ROOT/ndk/28.2.13676358" ]]; then
  ANDROID_NDK="$ANDROID_SDK_ROOT/ndk/28.2.13676358"
elif [[ -z "${ANDROID_NDK:-}" ]]; then
  ANDROID_NDK="$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi

if [[ -z "${ANDROID_NDK:-}" || ! -d "$ANDROID_NDK" ]]; then
  echo "[engram] ANDROID_NDK not found. Set ANDROID_NDK=/path/to/Android/sdk/ndk/<version>"
  exit 1
fi

CMAKE_BIN="${CMAKE_BIN:-$(command -v cmake || true)}"
if [[ -z "$CMAKE_BIN" && -x "$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake" ]]; then
  CMAKE_BIN="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/cmake"
fi
NINJA_BIN="${NINJA_BIN:-$(command -v ninja || true)}"
if [[ -z "$NINJA_BIN" && -x "$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja" ]]; then
  NINJA_BIN="$ANDROID_SDK_ROOT/cmake/3.22.1/bin/ninja"
fi

if [[ -z "$CMAKE_BIN" || -z "$NINJA_BIN" ]]; then
  echo "[engram] cmake or ninja not found (checked PATH and $ANDROID_SDK_ROOT/cmake/3.22.1/bin)"
  exit 1
fi

if [[ ! -d "$MNN_DIR/.git" ]]; then
  echo "[engram] Cloning alibaba/MNN into $MNN_DIR..."
  mkdir -p "$(dirname "$MNN_DIR")"
  git clone --depth 1 https://github.com/alibaba/MNN.git "$MNN_DIR"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"

echo "[engram] Configuring MNN + MNN-LLM arm64-v8a with NDK ($ANDROID_NDK)..."
"$CMAKE_BIN" -S "$MNN_DIR" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-29 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DMNN_BUILD_SHARED_LIBS=ON \
  -DMNN_SEP_BUILD=ON \
  -DMNN_LOW_MEMORY=ON \
  -DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON \
  -DMNN_BUILD_LLM=ON \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
  -DMNN_ARM82=ON \
  -DMNN_OPENCL=ON \
  -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"

NCPU="$(sysctl -n hw.ncpu 2>/dev/null || echo 8)"
echo "[engram] Building MNN shared libraries with $NCPU threads..."
"$NINJA_BIN" -C "$BUILD_DIR" -j"$NCPU" MNN MNN_Express llm

mkdir -p "$OUT_JNILIBS" "$OUT_INCLUDE/llm"
find "$BUILD_DIR" -maxdepth 5 -name "libMNN.so" -exec cp -f {} "$OUT_JNILIBS/" \;
find "$BUILD_DIR" -maxdepth 5 -name "libMNN_CL.so" -exec cp -f {} "$OUT_JNILIBS/" \;
find "$BUILD_DIR" -maxdepth 5 -name "libMNN_Express.so" -exec cp -f {} "$OUT_JNILIBS/" \;
find "$BUILD_DIR" -maxdepth 5 -name "libllm.so" -exec cp -f {} "$OUT_JNILIBS/" \;

STRIP_BIN="$(find "$ANDROID_NDK/toolchains/llvm/prebuilt" -name "llvm-strip" \( -type f -o -type l \) 2>/dev/null | head -n 1 || true)"
if [[ -n "$STRIP_BIN" ]]; then
  "$STRIP_BIN" --strip-unneeded "$OUT_JNILIBS"/*.so
fi

rm -rf "$OUT_INCLUDE/MNN"
cp -R "$MNN_DIR/include/MNN" "$OUT_INCLUDE/"
cp -R "$MNN_DIR/transformers/llm/engine/include/llm/"* "$OUT_INCLUDE/llm/"

echo "[engram] Native libraries ready in $OUT_JNILIBS:"
ls -lh "$OUT_JNILIBS"
