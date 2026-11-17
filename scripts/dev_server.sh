#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$ROOT_DIR/server"
VENV_DIR="$SERVER_DIR/.venv"
PORT="${ENGRAM_PORT:-8787}"

if [[ ! -d "$VENV_DIR" ]]; then
  echo "[engram] Creating Python virtualenv at $VENV_DIR..."
  python3 -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install --upgrade pip
  "$VENV_DIR/bin/pip" install -e "$SERVER_DIR[dev]"
fi

cd "$SERVER_DIR"
echo "[engram] Starting Engram Gateway on http://0.0.0.0:$PORT"
exec "$VENV_DIR/bin/uvicorn" app.main:app --host 0.0.0.0 --port "$PORT" --reload
