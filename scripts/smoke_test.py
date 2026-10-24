#!/usr/bin/env python3
"""对手机端做一轮功能冒烟测试。

用法：
    PHONE_MCP_RELAY_URL=... PHONE_MCP_RELAY_TOKEN=... PHONE_MCP_DEVICE_ID=... \
        python scripts/smoke_test.py
"""
from __future__ import annotations

import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "server"))

from phone_mcp import server as S  # noqa: E402

PASS, FAIL = "PASS", "FAIL"
results: list[tuple[str, str, str]] = []


def record(name: str, ok: bool, detail: str = "") -> None:
    results.append((PASS if ok else FAIL, name, detail))
    print(f"{PASS if ok else FAIL}  {name}" + (f"  ({detail})" if detail else ""))


def is_ok(reply: str) -> bool:
    """动作类工具成功时以「成功」开头，失败时以「失败：」/「手机端错误」开头。"""
    return reply.startswith("成功")


async def _first_clickable() -> str | None:
    """从当前界面的可交互节点里挑一个安全的做点击测试（跳过明显会跳走的入口）。"""
    ui = await S.phone_ui(interactive_only=True, max_lines=60)
    for line in ui.splitlines():
        if "] " not in line or "click" not in line:
            continue
        node_id = line.split("]")[0].split("[")[-1].strip()
        if node_id:
            return node_id
    return None


async def main() -> int:
    # ---- 基础 ----
    st = await S.phone_status()
    record("phone_status", "已连接" in st and "无障碍服务：已连接" in st)

    apps = await S.phone_apps()
    record("phone_apps", apps.startswith("共 ") and "个应用" in apps)

    # ---- 启动 + 观察 ----
    r = await S.phone_launch("com.android.settings")
    record("phone_launch", is_ok(r), r[:30])
    await asyncio.sleep(2.5)
    record("phone_wait", "稳定" in await S.phone_wait(timeout_ms=4000))

    ui = await S.phone_ui(interactive_only=True, max_lines=20)
    record("phone_ui", "快照" in ui)

    # ---- 连续截图（验证限速重试）----
    shots = 0
    for _ in range(3):
        out = await S.phone_observe(max_width=500, max_lines=3)
        if any(not isinstance(b, str) for b in out):
            shots += 1
    record("phone_observe x3", shots == 3, f"{shots}/3")

    # ---- 点击：用当前界面真实存在的可点节点，不假设具体页面 ----
    target = await _first_clickable()
    if target is None:
        record("phone_click", False, "当前界面没有可点节点")
    else:
        r = await S.phone_click(node_id=target)
        record("phone_click (node_id)", is_ok(r), r[:40])
        await asyncio.sleep(0.8)
        # 点进去之后退回，恢复现场
        await S.phone_key("back")
        await asyncio.sleep(0.8)

    # ---- 手势 ----
    record("phone_swipe", is_ok(await S.phone_swipe(640, 1800, 640, 900, 300)))
    await asyncio.sleep(0.5)
    record("phone_tap", is_ok(await S.phone_tap(640, 400)))
    await asyncio.sleep(0.5)
    record("phone_key back", is_ok(await S.phone_key("back")))

    await S._client.aclose()

    failed = [r for r in results if r[0] == FAIL]
    print(f"\n{len(results) - len(failed)}/{len(results)} 通过")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
