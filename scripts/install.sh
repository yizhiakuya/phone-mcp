#!/usr/bin/env bash
# 装 APK 到手机，并（可选）建立 USB 端口转发，让电脑端走 127.0.0.1
set -euo pipefail

ADB="${ADB:-adb}"
PORT="${PHONE_MCP_PORT:-8765}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/dist/phone-mcp.apk"

if [ ! -f "$APK" ]; then
    echo "找不到 $APK，先跑 scripts/build_apk.sh" >&2
    exit 1
fi

if ! "$ADB" get-state >/dev/null 2>&1; then
    echo "没有检测到设备。检查 USB 调试是否开启、是否已在手机上授权本机。" >&2
    "$ADB" devices -l
    exit 1
fi

echo "安装 $APK"
"$ADB" install -r "$APK"

echo "建立端口转发：设备 tcp:$PORT -> 本机 tcp:$PORT"
"$ADB" reverse "tcp:$PORT" "tcp:$PORT"

echo "启动 App"
"$ADB" shell monkey -p top.rainaki.phonemcp.debug -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
    "$ADB" shell monkey -p top.rainaki.phonemcp -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true

cat <<'EOF'

下一步（手机上）：
  1. 设置 → 无障碍 → Phone MCP 控制服务 → 开启
  2. 回到 App，点「复制配置」

电脑端若走 USB，env 用：
  PHONE_MCP_URL=http://127.0.0.1:8765
EOF
