"""MCP layer in relay mode: verify it hits /phonemcp/device/<id>/... and uses the control token."""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from phone_mcp.bridge import BridgeClient, BridgeError
from phone_mcp.config import Config

TREE = {"screen": {"w": 1080, "h": 2400}, "package": "com.x", "node_count": 1,
        "snapshot": 1, "root": {"i": "n0", "c": "FrameLayout", "ch": []}}


class FakeRelay(BaseHTTPRequestHandler):
    seen: list[tuple[str, str | None]] = []
    require_token: str | None = None
    e2e_key = "k" * 43
    device_id = "phone-abc"

    def log_message(self, *a): pass

    def _path_only(self):
        return self.path.rstrip("/").replace("/phonemcp/device/phone-abc", "")

    def _send_raw(self, code, payload):
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _reply(self, code, payload):
        from phone_mcp.e2ee import seal
        try:
            payload = {"e2ee": seal(self.e2e_key, self.device_id, self._path_only(), payload)}
        except Exception:
            pass
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        FakeRelay.seen.append((self.path, self.headers.get("Authorization")))
        if self.path.endswith("/health"):
            return self._reply(200, {"ok": True, "service_connected": True, "sdk": 34,
                                     "device": "P", "screen": {"w": 1080, "h": 2400}, "package": "com.x"})
        self._reply(404, {"ok": False, "error": "nope"})

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        self._raw = json.loads(raw) if raw else {}
        FakeRelay.seen.append((self.path, self.headers.get("Authorization")))
        if FakeRelay.require_token is not None:
            if self.headers.get("Authorization") != f"Bearer {FakeRelay.require_token}":
                return self._send_raw(401, {"ok": False, "error": "unauthorized"})
        if self.path == "/phonemcp/device/phone-abc/tree":
            return self._reply(200, TREE)
        if self.path == "/phonemcp/device/phone-abc/click":
            return self._reply(200, {"ok": True})
        if self.path == "/phonemcp/device/phone-abc/health":
            return self._reply(200, {
                "ok": True, "service_connected": True, "sdk": 34,
                "device": "P", "screen": {"w": 1080, "h": 2400}, "package": "com.x",
            })
        self._reply(404, {"ok": False, "error": "unknown " + self.path})


@pytest.fixture()
def relay_url():
    FakeRelay.seen = []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeRelay)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown(); srv.server_close()


def _client(url):
    return BridgeClient(Config(relay_url=url, relay_token="ctl", device_id="phone-abc",
                               e2e_key=FakeRelay.e2e_key, timeout=5))


@pytest.mark.asyncio
async def test_relay_mode_prefix_and_token(relay_url):
    c = _client(relay_url)
    data = await c.post("/tree", {"max_depth": 5})
    assert data["package"] == "com.x"
    path, auth = FakeRelay.seen[-1]
    assert path == "/phonemcp/device/phone-abc/tree"
    assert auth == "Bearer ctl"       # 控制 token，不是设备 token
    await c.aclose()


@pytest.mark.asyncio
async def test_relay_mode_health(relay_url):
    c = _client(relay_url)
    data = await c.get("/health")
    assert data["service_connected"] is True
    assert FakeRelay.seen[-1][0] == "/phonemcp/device/phone-abc/health"
    await c.aclose()


@pytest.mark.asyncio
async def test_relay_mode_401_message(relay_url):
    """鉴权失败要给出可操作的错误，且不泄露内部细节。"""
    bad = BridgeClient(Config(relay_url=relay_url, relay_token="wrong", device_id="phone-abc",
                              e2e_key=FakeRelay.e2e_key, timeout=5))
    # 假中继对错误凭据返回 401
    FakeRelay.require_token = "ctl"
    try:
        with pytest.raises(BridgeError) as info:
            await bad.post("/tree", {})
        assert info.value.status == 401
        assert "鉴权" in str(info.value)
    finally:
        FakeRelay.require_token = None
    await bad.aclose()


def test_config_relay_requires_all(monkeypatch):
    from phone_mcp.config import ConfigError, load_config
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", "https://h:8900")
    monkeypatch.delenv("PHONE_MCP_RELAY_TOKEN", raising=False)
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-x")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", "k" * 43)
    with pytest.raises(ConfigError, match="PHONE_MCP_RELAY_TOKEN"):
        load_config()

    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.delenv("PHONE_MCP_DEVICE_ID", raising=False)
    with pytest.raises(ConfigError, match="PHONE_MCP_DEVICE_ID"):
        load_config()

    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-x")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", "k" * 43)
    cfg = load_config()
    assert cfg.mode == "relay"
    assert cfg.host == "h"
    assert cfg.port == 8900


def test_config_relay_wins_over_direct(monkeypatch):
    from phone_mcp.config import load_config
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", "https://h:8900")
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-x")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", "k" * 43)
    monkeypatch.setenv("PHONE_MCP_URL", "http://192.168.1.2:8765")
    monkeypatch.setenv("PHONE_MCP_TOKEN", "direct")
    assert load_config().mode == "relay"
