"""End-to-end tests against a fake bridge, so the MCP layer is verified without a phone.

The fake implements the same HTTP contract as the Android app. If the two ever
drift, these tests are where it shows up.
"""

from __future__ import annotations

import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import pytest

from phone_mcp.bridge import BridgeClient, BridgeError
from phone_mcp.config import Config

PNG_1PX = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
)

TREE = {
    "screen": {"w": 1080, "h": 2400},
    "package": "com.example.app",
    "node_count": 2,
    "snapshot": 1,
    "root": {
        "i": "n0",
        "c": "FrameLayout",
        "b": [0, 0, 1080, 2400],
        "ch": [{"i": "n1", "c": "Button", "t": "确定", "b": [10, 10, 100, 60], "f": ["click"]}],
    },
}


class FakeBridge(BaseHTTPRequestHandler):
    _rid = 0
    _ts = 0
    token = "secret"
    e2e_key = ""          # 由 fixture 注入
    device_id = "phone-test"
    calls: list[tuple[str, dict[str, Any]]] = []

    def log_message(self, *args: Any) -> None:  # silence
        pass

    def _auth_ok(self) -> bool:
        if self.path.rstrip("/") == "/phonemcp/device/phone-test/health":
            return True
        return self.headers.get("Authorization") == f"Bearer {self.token}"

    def _path_only(self) -> str:
        """去掉中继前缀，得到手机端看到的路径（AAD 用）。"""
        return self.path.rstrip("/").replace("/phonemcp/device/phone-test", "")

    def _unseal(self, body: dict[str, Any]) -> dict[str, Any]:
        from phone_mcp.e2ee import open_sealed
        return open_sealed(self.e2e_key, self.device_id, self._path_only(), body["e2ee"],
                            rid=int(body.get("rid") or 0), ts=int(body.get("ts") or 0))

    def _sealed_json(self, status: int, payload: dict[str, Any]) -> None:
        from phone_mcp.e2ee import seal
        blob = seal(self.e2e_key, self.device_id, self._path_only(), payload,
                    rid=self._rid, ts=self._ts)
        self._send_json(status, {"e2ee": blob, "rid": self._rid, "ts": self._ts})

    def _send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        return json.loads(raw) if raw else {}

    def do_GET(self) -> None:  # noqa: N802
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "未授权"})
        if self.path.rstrip("/") == "/phonemcp/device/phone-test/health":
            return self._sealed_json(
                200,
                {
                    "ok": True,
                    "service_connected": True,
                    "sdk": 34,
                    "device": "Test Phone",
                    "screen": {"w": 1080, "h": 2400},
                    "package": "com.example.app",
                },
            )
        self._send_json(404, {"ok": False, "error": "未知接口"})

    def do_POST(self) -> None:  # noqa: N802
        # 先读完请求体再判鉴权，否则客户端还在写 body 时服务端就关连接，会变成 ReadError
        body = self._read_json()
        self._rid = int(body.get("rid") or 0)
        self._ts = int(body.get("ts") or 0)
        if not self._auth_ok():
            return self._send_json(401, {"ok": False, "error": "未授权"})
        path = self.path.rstrip("/")
        try:
            body = self._unseal(body)
        except Exception:
            return self._send_json(400, {"ok": False, "error": "端到端解密失败"})
        FakeBridge.calls.append((path, body))

        if path == "/phonemcp/device/phone-test/screenshot":
            from phone_mcp.e2ee import seal_bytes
            blob = seal_bytes(self.e2e_key, self.device_id, "/screenshot", PNG_1PX)
            return self._send_json(200, {"e2ee": blob})
        if path == "/phonemcp/device/phone-test/tree":
            return self._sealed_json(200, TREE)
        if path == "/phonemcp/device/phone-test/click":
            return self._sealed_json(200, {"ok": True, "target": "Button 确定"})
        if path == "/phonemcp/device/phone-test/set_text":
            return self._sealed_json(200, {"ok": True, "value": body.get("value")})
        if path == "/phonemcp/device/phone-test/health":
            return self._sealed_json(200, {
                "ok": True, "service_connected": True, "sdk": 34,
                "device": "Test Phone", "screen": {"w": 1080, "h": 2400},
                "package": "com.example.app",
            })
        if path == "/phonemcp/device/phone-test/key":
            return self._sealed_json(200, {"ok": True})
        if path == "/phonemcp/device/phone-test/wait_idle":
            return self._sealed_json(200, {"stable": True})
        if path == "/phonemcp/device/phone-test/apps":
            return self._sealed_json(200, {"apps": [{"package": "com.example.app", "label": "示例"}]})
        self._send_json(404, {"ok": False, "error": f"未知接口 {path}"})


@pytest.fixture()
def bridge_url(e2e_key: str) -> str:
    FakeBridge.calls = []
    FakeBridge.e2e_key = e2e_key
    srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeBridge)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown()
        srv.server_close()


@pytest.fixture()
def client(bridge_url: str, e2e_key: str) -> BridgeClient:
    return BridgeClient(Config(relay_url=bridge_url, relay_token="secret",
                               device_id="phone-test", e2e_key=e2e_key, timeout=5))


@pytest.mark.asyncio
async def test_health(client: BridgeClient):
    data = await client.get("/health")
    assert data["service_connected"] is True
    assert data["screen"]["w"] == 1080


@pytest.mark.asyncio
async def test_tree(client: BridgeClient):
    data = await client.post("/tree", {"max_depth": 30})
    assert data["root"]["ch"][0]["t"] == "确定"


@pytest.mark.asyncio
async def test_screenshot_bytes(client: BridgeClient):
    shot = await client.post_bytes("/screenshot", {"max_width": 900})
    assert shot[:2] == b"\xff\xd8" or shot[:4] == b"\x89PNG"


@pytest.mark.asyncio
async def test_bad_token_raises_auth_error(bridge_url: str, e2e_key: str):
    bad = BridgeClient(Config(relay_url=bridge_url, relay_token="wrong",
                              device_id="phone-test", e2e_key=e2e_key, timeout=5))
    with pytest.raises(BridgeError) as info:
        await bad.post("/tree", {})
    assert info.value.is_auth
    assert "鉴权失败" in str(info.value)


@pytest.mark.asyncio
async def test_connect_error_has_actionable_hint():
    dead = BridgeClient(Config(relay_url="http://127.0.0.1:1", relay_token="x",
                               device_id="phone-test", e2e_key="k" * 43, timeout=2))
    with pytest.raises(BridgeError) as info:
        await dead.post("/tree", {})
    msg = str(info.value)
    # 直连已移除，提示应指向中继而不是 adb
    assert "中继" in msg


@pytest.mark.asyncio
async def test_unknown_endpoint_gives_opaque_message(client: BridgeClient):
    """未认证响应的正文不能被透传给模型——中继可以塞任意指令进去。

    所以只按状态码给固定文案，不显示服务端原始 error 字段。
    """
    with pytest.raises(BridgeError) as info:
        await client.post("/nope", {})
    msg = str(info.value)
    assert "404" not in msg or "接口" in msg
    assert "未知接口" not in msg  # 不再透传服务端文本
