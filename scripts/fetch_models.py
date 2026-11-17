#!/usr/bin/env python3
"""Fetch MNN quantized model files from Engram gateway manifest or HuggingFace/ModelScope."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import urllib.request
from pathlib import Path

DEFAULT_MANIFEST_URL = "http://127.0.0.1:8787/api/v1/models/manifest"
ANDROID_MODEL_ROOT = "/sdcard/Android/data/io.github.cinpecan.engram/files/models"


def download_file(url: str, dest: Path) -> str:
    dest.parent.mkdir(parents=True, exist_ok=True)
    sha256 = hashlib.sha256()
    print(f"Downloading {url} -> {dest}")
    with urllib.request.urlopen(url, timeout=120) as resp, dest.open("wb") as fh:
        while True:
            chunk = resp.read(1024 * 256)
            if not chunk:
                break
            fh.write(chunk)
            sha256.update(chunk)
    return sha256.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description="Download quantized MNN model bundles for Engram.")
    parser.add_argument("--model-id", default="qwen2.5-0.5b-instruct-mnn", help="Model ID from manifest")
    parser.add_argument("--manifest-url", default=DEFAULT_MANIFEST_URL, help="Gateway manifest URL")
    parser.add_argument("--out-dir", default="models", help="Local target directory")
    parser.add_argument("--adb-push", action="store_true", help="Push downloaded model folder via adb")
    args = parser.parse_args()

    with urllib.request.urlopen(args.manifest_url, timeout=15) as resp:
        manifest = json.loads(resp.read().decode("utf-8"))

    target = next((m for m in manifest["models"] if m["id"] == args.model_id), None)
    if target is None:
        available = [m["id"] for m in manifest["models"]]
        raise SystemExit(f"Model '{args.model_id}' not found. Available: {available}")

    model_dir = Path(args.out_dir) / args.model_id
    for f in target["files"]:
        digest = download_file(f["url"], model_dir / f["name"])
        expected = f["sha256"]
        if expected != "0" * 64 and digest != expected:
            raise SystemExit(f"SHA-256 mismatch for {f['name']}: got {digest}, expected {expected}")

    print(f"Model ready in {model_dir}")
    if args.adb_push:
        remote_dir = f"{ANDROID_MODEL_ROOT}/{args.model_id}"
        subprocess.run(["adb", "shell", "mkdir", "-p", remote_dir], check=True)
        subprocess.run(["adb", "push", str(model_dir) + "/.", remote_dir], check=True)
        print(f"Pushed to device: {remote_dir}")


if __name__ == "__main__":
    main()
