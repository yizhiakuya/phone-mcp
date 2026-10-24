#!/usr/bin/env bash
# 配对、列出、吊销设备；完整参数见 --help，不再打印或传参携带 Token。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PHONE_MCP_PYTHON:-$ROOT/server/.venv/Scripts/python.exe}"
export PYTHONPATH="$ROOT/server${PYTHONPATH:+:$PYTHONPATH}"
exec "$PYTHON" -m phone_mcp.pairing "$@"
