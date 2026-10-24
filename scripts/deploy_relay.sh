#!/usr/bin/env bash
# 把中继部署/更新到目标主机。首次部署会自动生成凭据。
#
# 需要设置 PHONE_MCP_RELAY_HOST（形如 user@host）。没有内置默认值——
# 个人服务器地址不该写进版本库。
set -euo pipefail

HOST="${PHONE_MCP_RELAY_HOST:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [ -z "$HOST" ]; then
    echo "未设置 PHONE_MCP_RELAY_HOST（形如 user@host）" >&2
    exit 1
fi

ssh -o BatchMode=yes "$HOST" "mkdir -p /opt/phonemcp-relay /var/log/phonemcp-relay"

echo "==> 同步 relay.py"
scp -o BatchMode=yes "$ROOT/relay/relay.py" "$HOST:/opt/phonemcp-relay/relay.py"
scp -o BatchMode=yes "$ROOT/relay/phonemcp-relay.service" "$HOST:/etc/systemd/system/"

echo "==> 确保凭据存在"
ssh -o BatchMode=yes "$HOST" 'bash -s' <<'REMOTE'
set -e
ENV=/opt/phonemcp-relay/relay.env
mkdir -p /opt/phonemcp-relay /var/log/phonemcp-relay
if [ ! -f "$ENV" ]; then
  echo "首次部署，生成凭据"
  umask 077
  cat > "$ENV" <<EOF
PHONE_MCP_CONTROL_TOKEN=$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')
PHONE_MCP_RELAY_PORT=8903
PHONE_MCP_RELAY_TIMEOUT=60
EOF
  chmod 600 "$ENV"
fi
chmod 600 "$ENV"
systemctl daemon-reload
systemctl enable phonemcp-relay >/dev/null 2>&1 || true
systemctl restart phonemcp-relay
sleep 2
systemctl is-active phonemcp-relay
REMOTE

echo "==> 状态"
ssh -o BatchMode=yes "$HOST" 'journalctl -u phonemcp-relay -n 5 --no-pager; systemctl is-active phonemcp-relay'
