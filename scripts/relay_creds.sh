#!/usr/bin/env bash
# 通过 SSH 把管理员凭据保存到新建私有目录；绝不输出到终端。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${PHONE_MCP_RELAY_HOST:-}"
OUT="${1:?用法: relay_creds.sh <新建私有目录>}"
PYTHON="${PHONE_MCP_PYTHON:-$ROOT/server/.venv/Scripts/python.exe}"
export PYTHONPATH="$ROOT/server${PYTHONPATH:+:$PYTHONPATH}"
"$PYTHON" - "$HOST" "$OUT" <<'PY'
import os, shlex, subprocess, sys
from pathlib import Path
from phone_mcp.pairing import _private_directory
folder = Path(sys.argv[2]).resolve()
_private_directory(folder)
code = "from pathlib import Path; s=Path('/opt/phonemcp-relay/relay.env').read_text(); print(next(line.split('=',1)[1] for line in s.splitlines() if line.startswith('PHONE_MCP_CONTROL_TOKEN=')))"
result = subprocess.run(['ssh', '-o', 'BatchMode=yes', sys.argv[1], 'python3 -c ' + shlex.quote(code)], check=True, capture_output=True, text=True)
token = result.stdout.strip()
if not token or '\n' in token:
    raise SystemExit('服务器未返回有效凭据')
target = folder / 'admin.token'
with target.open('x', encoding='utf-8') as out:
    out.write(token)
if os.name != 'nt':
    target.chmod(0o600)
print('管理员凭据已保存到私有文件：', target)
PY
