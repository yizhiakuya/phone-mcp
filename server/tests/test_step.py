"""步骤说明（step）透传测试。

`step` 只作展示：模型填一句中文，手机端显示在底部状态条上。
它不参与任何安全判定，所以测试只验证"有没有正确传到请求体里"。
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest


class StepBridge(BaseHTTPRequestHandler):
    e2e_key = ""
    device_id = "phone-test"
    seen: list[tuple[str, dict]] = []

    def log_message(self, *a): pass

    def _path_only(self):
        return self.path.rstrip("/").replace("/phonemcp/device/phone-test", "")

    def _reply(self, code, payload):
        from phone_mcp.e2ee import seal
        body = json.dumps(
            {"e2ee": seal(self.e2e_key, self.device_id, self._path_only(), payload)}
        ).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = json.loads(self.rfile.read(n)) if n else {}
        from phone_mcp.e2ee import open_sealed

        payload = open_sealed(self.e2e_key, self.device_id, self._path_only(), raw["e2ee"])
        StepBridge.seen.append((self._path_only(), payload))
        if self._path_only() == "/screenshot":
            # 截图走二进制密封，这里只回一个极小的 JPEG 占位
            self._reply(200, {"ok": True})
            return
        if self._path_only() == "/tree":
            return self._reply(200, {
                "root": {"i": "n1", "c": "android.widget.FrameLayout", "ch": []},
                "screen": {"w": 1080, "h": 2400},
            })
        if self._path_only() == "/health":
            return self._reply(200, {
                "ok": True, "service_connected": True, "sdk": 34, "device": "P",
                "screen": {"w": 1080, "h": 2400}, "package": "com.x",
            })
        self._reply(200, {"ok": True})


@pytest.fixture()
def step_url(e2e_key):
    StepBridge.seen = []
    StepBridge.e2e_key = e2e_key
    srv = ThreadingHTTPServer(("127.0.0.1", 0), StepBridge)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown(); srv.server_close()


@pytest.fixture()
def srv(step_url, monkeypatch):
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", step_url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", StepBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None
    yield S
    # 客户端在事件循环里创建，这里不主动关：pytest-asyncio 每个用例一个 loop，
    # 跨 loop 调 aclose 会炸。进程结束由 httpx 自己回收。
    S._config = None
    S._client = None


def _last(path: str) -> dict:
    for p, body in reversed(StepBridge.seen):
        if p == path:
            return body
    raise AssertionError(f"没有发到 {path}，实际：{[p for p, _ in StepBridge.seen]}")


@pytest.mark.asyncio
async def test_click_carries_step(srv):
    await srv.phone_click(text="发送", step="点发送按钮")
    assert _last("/click")["step"] == "点发送按钮"


@pytest.mark.asyncio
async def test_tap_carries_step(srv):
    await srv.phone_tap(x=100, y=200, step="点视频封面")
    assert _last("/tap")["step"] == "点视频封面"


@pytest.mark.asyncio
async def test_swipe_and_key_carry_step(srv):
    await srv.phone_swipe(x1=1, y1=2, x2=3, y2=4, step="往下翻")
    assert _last("/swipe")["step"] == "往下翻"
    await srv.phone_key("back", step="返回上一页")
    assert _last("/key")["step"] == "返回上一页"


@pytest.mark.asyncio
async def test_launch_and_set_text_carry_step(srv):
    await srv.phone_launch("com.tencent.mm", step="打开微信")
    assert _last("/launch")["step"] == "打开微信"
    await srv.phone_set_text("学超", node_id="n3", step="输入收件人")
    assert _last("/set_text")["step"] == "输入收件人"


@pytest.mark.asyncio
async def test_observe_and_ui_carry_step(srv):
    try:
        await srv.phone_ui(step="看看当前是什么页面")
    except Exception:
        pass  # 假截图不是真 JPEG，这里只关心请求体
    assert _last("/tree")["step"] == "看看当前是什么页面"


@pytest.mark.asyncio
async def test_step_omitted_when_not_given(srv):
    """不填 step 时不要凭空塞一个空字段——手机端靠"没有"来判断是否更新文案。"""
    await srv.phone_click(text="确定")
    assert "step" not in _last("/click")


@pytest.mark.asyncio
async def test_step_is_truncated(srv):
    await srv.phone_click(text="确定", step="很" * 500)
    assert len(_last("/click")["step"]) == 120
