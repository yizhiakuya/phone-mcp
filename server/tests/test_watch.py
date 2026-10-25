"""事件监听与语义等待（phone_watch / phone_wait_until）测试。"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest


class EventBridge(BaseHTTPRequestHandler):
    _rid = 0
    _ts = 0
    """假手机：按预设脚本返回 watch / wait_until 的结果。"""

    e2e_key = ""
    device_id = "phone-test"
    seen: list[tuple[str, dict]] = []
    watch_reply: dict = {}
    wait_reply: dict = {}

    def log_message(self, *a): pass

    def _path_only(self):
        return self.path.rstrip("/").replace("/phonemcp/device/phone-test", "")

    def _reply(self, code, payload):
        from phone_mcp.e2ee import seal
        body = json.dumps(
            {"e2ee": seal(self.e2e_key, self.device_id, self._path_only(), payload,
                          rid=self._rid, ts=self._ts),
             "rid": self._rid, "ts": self._ts}
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

        path = self._path_only()
        self._rid = int(raw.get("rid") or 0)
        self._ts = int(raw.get("ts") or 0)
        payload = open_sealed(self.e2e_key, self.device_id, path, raw["e2ee"],
                                rid=int(raw.get("rid") or 0), ts=int(raw.get("ts") or 0))
        EventBridge.seen.append((path, payload))

        if path == "/watch":
            return self._reply(200, EventBridge.watch_reply)
        if path == "/wait_until":
            return self._reply(200, EventBridge.wait_reply)
        self._reply(200, {"ok": True})


@pytest.fixture()
def ev_url(e2e_key):
    EventBridge.seen = []
    EventBridge.e2e_key = e2e_key
    EventBridge.watch_reply = {"matched": False, "recent_count": 0, "recent": []}
    EventBridge.wait_reply = {"satisfied": True}
    srv = ThreadingHTTPServer(("127.0.0.1", 0), EventBridge)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown(); srv.server_close()


@pytest.fixture()
def srv(ev_url, monkeypatch):
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", ev_url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", EventBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None
    yield S
    S._config = None
    S._client = None


def _last(path: str) -> dict:
    for p, body in reversed(EventBridge.seen):
        if p == path:
            return body
    raise AssertionError(f"没有发到 {path}")


# ------------------------------------------------------------------ watch


@pytest.mark.asyncio
async def test_watch_returns_matched_event(srv):
    EventBridge.watch_reply = {
        "matched": True,
        "event": {
            "id": 42, "type": "notification",
            "package": "com.tencent.mm", "text": "学超: 在吗", "at": 1,
        },
    }
    out = await srv.phone_watch(package="com.tencent.mm", timeout_ms=5000)
    assert "notification" in out
    assert "com.tencent.mm" in out
    assert "学超: 在吗" in out


@pytest.mark.asyncio
async def test_watch_passes_filters(srv):
    await srv.phone_watch(package="com.tencent.mm", text="在吗", types=["notification"], timeout_ms=8000)
    body = _last("/watch")
    assert body["package"] == "com.tencent.mm"
    assert body["text"] == "在吗"
    assert body["types"] == ["notification"]
    assert body["timeout_ms"] == 8000


@pytest.mark.asyncio
async def test_watch_omits_empty_filters(srv):
    """没给过滤条件时不要凭空塞空字段——手机端靠「有没有」区分。"""
    await srv.phone_watch(timeout_ms=3000)
    body = _last("/watch")
    assert "package" not in body
    assert "text" not in body
    assert "types" not in body


@pytest.mark.asyncio
async def test_watch_timeout_reports_other_events(srv):
    """超时不是失败：要把这段时间发生的事告诉模型，便于判断。"""
    EventBridge.watch_reply = {
        "matched": False,
        "recent_count": 2,
        "recent": [
            {"type": "window", "package": "com.tencent.mm", "text": ""},
            {"type": "click", "package": "com.tencent.mm", "text": "发送"},
        ],
    }
    out = await srv.phone_watch(timeout_ms=30000)
    assert "没有匹配的事件" in out
    assert "window" in out
    assert "发送" in out


@pytest.mark.asyncio
async def test_watch_timeout_with_no_events(srv):
    out = await srv.phone_watch(timeout_ms=10000)
    assert "没有发生任何匹配的事件" in out


# ------------------------------------------------------------------ wait_until


@pytest.mark.asyncio
async def test_wait_until_satisfied(srv):
    EventBridge.wait_reply = {"satisfied": True, "text": "发送成功", "disappear": False}
    out = await srv.phone_wait_until(text="发送成功", timeout_ms=5000)
    assert "已满足" in out
    assert "发送成功" in out
    assert _last("/wait_until")["text"] == "发送成功"


@pytest.mark.asyncio
async def test_wait_until_timeout_tells_model_to_reobserve(srv):
    EventBridge.wait_reply = {"satisfied": False, "text": "发送成功", "disappear": False}
    out = await srv.phone_wait_until(text="发送成功", timeout_ms=2000)
    assert "超时" in out
    assert "phone_observe" in out


@pytest.mark.asyncio
async def test_wait_until_disappear(srv):
    EventBridge.wait_reply = {"satisfied": True, "text": "加载中", "disappear": True}
    out = await srv.phone_wait_until(text="加载中", disappear=True)
    assert "已消失" in out
    assert _last("/wait_until")["disappear"] is True


@pytest.mark.asyncio
async def test_wait_until_requires_text(srv):
    """text 是必填的（Field 无默认值），这里验证工具签名确实强制。"""
    import inspect

    sig = inspect.signature(srv.phone_wait_until)
    assert sig.parameters["text"].default is inspect.Parameter.empty
