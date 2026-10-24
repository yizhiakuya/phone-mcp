#!/usr/bin/env bash
# 中继与设备状态速查。全部走 HTTPS + 设备专属凭据。
#
#   bash scripts/status.sh              健康检查 + 设备列表
#   bash scripts/status.sh --online     只看在线设备
#   bash scripts/status.sh --health     只看手机端健康数据
#
# 凭据来源（按优先级）：
#   1. .secrets/current 软链或目录里的 control.token
#   2. 环境变量 PHONE_MCP_RELAY_TOKEN_FILE
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Windows 控制台默认 GBK，中文输出会炸管道；统一 UTF-8
export PYTHONIOENCODING=utf-8
PHCURL="$ROOT/scripts/phcurl"
RELAY="${PHONE_MCP_RELAY_URL:-https://relay.example.com:8900}"

# ---- 定位凭据与设备 ----
if [ -n "${PHONE_MCP_RELAY_TOKEN_FILE:-}" ]; then
    TOKEN_FILE="$PHONE_MCP_RELAY_TOKEN_FILE"
elif [ -f "$ROOT/.secrets/current/control.token" ]; then
    TOKEN_FILE="$ROOT/.secrets/current/control.token"
else
    # 取最新的配对目录
    TOKEN_FILE=$(ls -td "$ROOT"/.secrets/pairing-*/ 2>/dev/null | head -1)control.token
fi

if [ ! -f "$TOKEN_FILE" ]; then
    echo "找不到控制凭据。先配对：" >&2
    echo "  server/.venv/Scripts/python.exe -m phone_mcp.pairing \\" >&2
    echo "    --relay-url $RELAY --admin-token-file .secrets/admin/admin.token \\" >&2
    echo "    pair --out .secrets/phone1" >&2
    exit 1
fi
TOKEN=$(tr -d '\r\n' < "$TOKEN_FILE")

# 设备 ID 从配对目录的 mcp-env.json 读，避免手抄
ENV_JSON="$(dirname "$TOKEN_FILE")/mcp-env.json"
DEVICE="${PHONE_MCP_DEVICE_ID:-}"
if [ -z "$DEVICE" ] && [ -f "$ENV_JSON" ]; then
    DEVICE=$(python -c "import json,sys;print(json.load(open(sys.argv[1]))['PHONE_MCP_DEVICE_ID'])" "$ENV_JSON")
fi
if [ -z "$DEVICE" ]; then
    echo "无法确定设备 ID，设置 PHONE_MCP_DEVICE_ID 环境变量" >&2
    exit 1
fi

MODE="${1:---all}"

case "$MODE" in
  --online)
    bash "$PHCURL" -s -H "Authorization: Bearer $TOKEN" \
      "$RELAY/phonemcp/device/$DEVICE/health" | python -c "
import json,sys
d=json.load(sys.stdin)
print(f\"设备 {d.get('device')} · Android {d.get('sdk')}\")
print(f\"版本 {d.get('version_name')} ({d.get('version_code')})\")
print(f\"无障碍 {'已连接' if d.get('service_connected') else '未连接'}\")
diag=d.get('diag') or {}
print(f\"主线程超时 {diag.get('main_timeouts',0)} | 窗口可读 {diag.get('root_available')}\")
"
    ;;
  --health)
    bash "$PHCURL" -s -H "Authorization: Bearer $TOKEN" \
      "$RELAY/phonemcp/device/$DEVICE/health" | python -m json.tool
    ;;
  *)
    echo "=== 手机端 ==="
    bash "$PHCURL" -s -H "Authorization: Bearer $TOKEN" \
      "$RELAY/phonemcp/device/$DEVICE/health" | python -c "
import json,sys
d=json.load(sys.stdin)
print(f\"  设备   {d.get('device')} · Android {d.get('sdk')}\")
print(f\"  版本   {d.get('version_name')} ({d.get('version_code')})\")
print(f\"  无障碍 {'✓ 已连接' if d.get('service_connected') else '✗ 未连接'}\")
print(f\"  当前应用 {d.get('package') or '-'}\")
diag=d.get('diag') or {}
mt=diag.get('main_timeouts',0)
print(f\"  主线程超时 {mt}{'  ⚠ 持续增长需排查' if mt>0 else ''}\")
" 2>/dev/null || echo "  ✗ 设备不在线"
    echo
    echo "=== 中继 ==="
    if [ -f "$ROOT/.secrets/admin/admin.token" ]; then
        ADMIN=$(tr -d '\r\n' < "$ROOT/.secrets/admin/admin.token")
        bash "$PHCURL" -s -H "Authorization: Bearer $ADMIN" "$RELAY/phonemcp/health" | python -c "
import json,sys
d=json.load(sys.stdin)
print(f\"  在线设备 {d.get('count',0)} 台\")
for x in d.get('devices',[]):
    print(f\"    {x['device_id']}  在线 {x['online_seconds']}s  在途 {x.get('inflight',0)}\")
" 2>/dev/null || echo "  （无法读取，可能需要管理员凭据）"
    else
        echo "  （无管理员凭据，跳过）"
    fi
    ;;
esac
