#!/usr/bin/env python
"""从配对目录读出凭据，请求手机 /health 并解封，打印可读状态。

`scripts/status.sh` 原来直接 `json.load` 中继返回的响应体——端到端加密之后
那是 `{"e2ee": "..."}`，所有字段都成了 None。这里走同一套 e2ee 解密。

用法：
    python scripts/device_status.py [配对目录]
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "server"))


def find_pairing_dir(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit)
    current = ROOT / ".secrets" / "current"
    if (current / "control.token").exists():
        return current
    candidates = sorted(
        (p for p in (ROOT / ".secrets").glob("pairing-*") if (p / "control.token").exists()),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise SystemExit("找不到配对目录，先跑 phone_mcp.pairing pair")
    return candidates[0]


def main() -> int:
    import httpx

    from phone_mcp.e2ee import open_sealed, seal

    pdir = find_pairing_dir(sys.argv[1] if len(sys.argv) > 1 else None)
    env = json.loads((pdir / "mcp-env.json").read_text(encoding="utf-8"))
    relay = env["PHONE_MCP_RELAY_URL"]
    device = env["PHONE_MCP_DEVICE_ID"]
    token = (pdir / "control.token").read_text(encoding="utf-8").strip()
    key = (pdir / "e2e.key").read_text(encoding="utf-8").strip()

    path = "/health"
    # 端到端加密需要请求体，所以 /health 也用 POST 发一个密封的空对象
    resp = httpx.post(
        f"{relay}/phonemcp/device/{device}{path}",
        headers={"Authorization": f"Bearer {token}"},
        json={"e2ee": seal(key, device, path, {})},
        timeout=10.0,
    )
    if resp.status_code != 200:
        print(f"HTTP {resp.status_code}: {resp.text[:200]}", file=sys.stderr)
        return 1
    body = resp.json()
    if "e2ee" not in body:
        print(f"响应未加密：{body}", file=sys.stderr)
        return 1
    data = open_sealed(key, device, path, body["e2ee"])

    guard = data.get("money_guard")
    print(f"设备   {data.get('device')} · Android {data.get('sdk')}")
    print(f"版本   {data.get('version_name')} ({data.get('version_code')})")
    print(f"无障碍 {'✓ 已连接' if data.get('service_connected') else '✗ 未连接'}")
    print(f"当前应用 {data.get('package') or '-'}")
    diag = data.get("diag") or {}
    mt = diag.get("main_timeouts", 0)
    print(f"主线程超时 {mt}{'  ⚠ 持续增长需排查' if mt else ''}")
    if guard:
        print(
            f"资金保护 ✓ 禁用包 {guard.get('blocked_packages')} · "
            f"目标词 {guard.get('target_keywords')} · 整屏词 {guard.get('screen_keywords')}"
        )
    else:
        print("资金保护 ✗ 手机端未上报（APK 是旧版，需升级）")
    if data.get("stopped"):
        print(f"⏸ 用户已中断（剩余 {int(data.get('stop_remaining_ms', 0) / 1000)}s）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
