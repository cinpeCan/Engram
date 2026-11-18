#!/usr/bin/env python3
"""Fetch MNN quantized model files directly from ModelScope (with HuggingFace fallback)."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import urllib.request
from pathlib import Path

MODEL_ID = "qwen2.5-0.5b-instruct-mnn"
MODELSCOPE_BASE = "https://modelscope.cn/models/MNN/Qwen2.5-0.5B-Instruct-MNN/resolve/master"
HUGGINGFACE_BASE = "https://huggingface.co/taobao-mnn/Qwen2.5-0.5B-Instruct-MNN/resolve/main"
ANDROID_MODEL_ROOT = "/sdcard/Android/data/io.github.cinpecan.engram/files/models"

MODEL_FILES: list[tuple[str, str | None]] = [
    ("config.json", None),
    ("llm_config.json", None),
    ("tokenizer.mtok", "c57d63b4ec886c35ba195879a6a6827b29f6bc48635d49dd256ade69f170ca99"),
    ("llm.mnn", "40f8f278e67d47d1b76ccef17e77510729bc0df551bdbaca350d4e6f69e8c0ea"),
    ("embeddings_bf16.bin", "4e96b0df6d274768cbb7e72404011853d23349999b658dc2f4dfb3c431ea223f"),
    ("llm.mnn.weight", "8a31f5a708797bf5bcaac009dc8a6a7b724ec2cabe93fe4687833d255cf443d5"),
]


def download_file(url: str, dest: Path) -> str:
    dest.parent.mkdir(parents=True, exist_ok=True)
    sha256 = hashlib.sha256()
    print(f"Downloading {url} -> {dest}")
    with urllib.request.urlopen(url, timeout=180) as resp, dest.open("wb") as fh:
        while True:
            chunk = resp.read(1024 * 256)
            if not chunk:
                break
            fh.write(chunk)
            sha256.update(chunk)
    return sha256.hexdigest()


def verify_digest(name: str, digest: str, expected: str | None) -> None:
    if expected is not None and digest != expected:
        raise SystemExit(f"SHA-256 mismatch for {name}: got {digest}, expected {expected}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Download quantized Qwen2.5-0.5B MNN bundle directly.")
    parser.add_argument("--source", choices=["modelscope", "huggingface"], default="modelscope")
    parser.add_argument("--out-dir", default="models", help="Local target directory")
    parser.add_argument("--adb-push", action="store_true", help="Push downloaded model folder via adb")
    args = parser.parse_args()

    primary = MODELSCOPE_BASE if args.source == "modelscope" else HUGGINGFACE_BASE
    secondary = HUGGINGFACE_BASE if args.source == "modelscope" else MODELSCOPE_BASE
    model_dir = Path(args.out_dir) / MODEL_ID

    for name, expected_sha in MODEL_FILES:
        dest = model_dir / name
        try:
            digest = download_file(f"{primary}/{name}", dest)
        except Exception as err:
            print(f"Primary failed ({err}), retrying fallback host for {name}...")
            digest = download_file(f"{secondary}/{name}", dest)
        verify_digest(name, digest, expected_sha)

    print(f"Model ready in {model_dir}")
    if args.adb_push:
        remote_dir = f"{ANDROID_MODEL_ROOT}/{MODEL_ID}"
        subprocess.run(["adb", "shell", "mkdir", "-p", remote_dir], check=True)
        subprocess.run(["adb", "push", str(model_dir) + "/.", remote_dir], check=True)
        print(f"Pushed to device: {remote_dir}")


if __name__ == "__main__":
    main()
