"""人工接管（human override）行为测试。"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from phone_mcp.bridge import BridgeClient, BridgeError, HumanOverrideError
from phone_mcp.config import Config


class OverrideBridge(BaseHTTPRequestHandler):
    paused = False
    seen: list[tuple[str, dict]] = []
    e2e_key = ""
    device_id = "phone-test"

    def log_message(self, *a): pass

    def _path_only(self):
        return self.path.rstrip("/").replace("/phonemcp/device/phone-test", "")

    def _unseal(self, body):
        from phone_mcp.e2ee import open_sealed
        return open_sealed(self.e2e_key, self.device_id, self._path_only(), body["e2ee"])

    def _reply(self, code, payload):
        from phone_mcp.e2ee import seal
        payload = {"e2ee": seal(self.e2e_key, self.device_id, self._path_only(), payload)}
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n)) if n else {}

    def do_GET(self):
        self._reply(200, {
            "ok": True, "service_connected": True, "sdk": 34, "device": "P",
            "screen": {"w": 1080, "h": 2400}, "package": "com.x",
            "stopped": OverrideBridge.paused,
            "stop_remaining_ms": 8000 if OverrideBridge.paused else 0,
        })

    def do_POST(self):
        raw = self._read()
        path = self.path.rstrip("/")
        try:
            body = self._unseal(raw)
        except Exception:
            return self._reply(400, {"ok": False, "error": "解密失败"})
        OverrideBridge.seen.append((path, body))

        if path == "/phonemcp/device/phone-test/health":
            return self._reply(200, {
                "ok": True, "service_connected": True, "sdk": 34, "device": "P",
                "screen": {"w": 1080, "h": 2400}, "package": "com.x",
                "stopped": OverrideBridge.paused,
                "stop_remaining_ms": 8000 if OverrideBridge.paused else 0,
            })
        if path == "/phonemcp/device/phone-test/override":
            action = body.get("action", "status")
            if action == "resume":
                OverrideBridge.paused = False
            elif action == "pause":
                OverrideBridge.paused = True
            return self._reply(200, {"user_operating": OverrideBridge.paused, "retry_after_ms": 1500})

        # 动作类：暂停中返回 409
        if OverrideBridge.paused:
            return self._reply(409, {"ok": False, "error": "用户正在操作手机", "user_operating": True, "retry_after_ms": 1500})
        self._reply(200, {"ok": True})


@pytest.fixture()
def url(e2e_key):
    OverrideBridge.paused = False
    OverrideBridge.seen = []
    OverrideBridge.e2e_key = e2e_key
    srv = ThreadingHTTPServer(("127.0.0.1", 0), OverrideBridge)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown(); srv.server_close()


def _client(u, key):
    return BridgeClient(Config(relay_url=u, relay_token="t", device_id="phone-test",
                               e2e_key=key, timeout=5))


@pytest.mark.asyncio
async def test_action_blocked_while_user_operating(url):
    c = _client(url, OverrideBridge.e2e_key)
    OverrideBridge.paused = True
    with pytest.raises(HumanOverrideError) as info:
        await c.post("/click", {"text": "确定"})
    assert info.value.retry_after_ms == 1500
    assert "用户正在操作" in str(info.value)
    await c.aclose()


@pytest.mark.asyncio
async def test_action_allowed_when_user_idle(url):
    """用户没在操作时，动作正常执行——没有"模式"需要切换。"""
    c = _client(url, OverrideBridge.e2e_key)
    OverrideBridge.paused = False
    data = await c.post("/click", {"text": "确定"})
    assert data["ok"] is True
    await c.aclose()


@pytest.mark.asyncio
async def test_read_only_still_works_while_paused(url):
    """人接管时仍要能看屏幕状态，否则无法判断该怎么恢复。"""
    c = _client(url, OverrideBridge.e2e_key)
    OverrideBridge.paused = True
    data = await c.get("/health")
    assert data["stopped"] is True
    await c.aclose()


@pytest.mark.asyncio
async def test_status_shows_stopped(url, monkeypatch):
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", OverrideBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None

    OverrideBridge.paused = True
    st = await S.phone_status()
    assert "中断" in st

    await S._client.aclose()
    S._config = None
    S._client = None


@pytest.mark.asyncio
async def test_action_returns_stop_signal(url, monkeypatch):
    """用户接管时，动作工具必须返回"停手"信号，而不是错误或重试。"""
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", OverrideBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None

    OverrideBridge.paused = True
    # 必须抛异常：返回值会被模型当成"正常完成"而继续执行
    with pytest.raises(Exception) as info:
        await S.phone_click(node_id="n1")
    msg = str(info.value)
    assert "中断" in msg
    assert "不要重试" in msg

    await S._client.aclose()
    S._config = None
    S._client = None
