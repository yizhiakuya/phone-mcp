"""OCR 工具测试（服务端部分）。

手机端的 ML Kit 识别没法在这里测，这里验证：
1. 请求参数正确透传
2. 返回的坐标格式对（模型要能用它直接 tap）
3. 资金保护对 OCR 目标同样生效
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest


class OcrBridge(BaseHTTPRequestHandler):
    _rid = 0
    _ts = 0
    e2e_key = ""
    device_id = "phone-test"
    seen: list[tuple[str, dict]] = []
    ocr_reply: dict = {}
    ocr_click_reply: dict = {}

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
        OcrBridge.seen.append((path, payload))

        if path == "/ocr":
            return self._reply(200, OcrBridge.ocr_reply)
        if path == "/ocr_click":
            return self._reply(200, OcrBridge.ocr_click_reply)
        self._reply(200, {"ok": True})


@pytest.fixture()
def ocr_url(e2e_key):
    OcrBridge.seen = []
    OcrBridge.e2e_key = e2e_key
    OcrBridge.ocr_reply = {
        "ok": True, "count": 2,
        "lines": [
            {"t": "开始游戏", "b": [400, 1200, 880, 1300]},
            {"t": "设置", "b": [400, 1400, 880, 1500]},
        ],
        "screen": {"w": 1280, "h": 2772},
    }
    OcrBridge.ocr_click_reply = {"ok": True, "target": 'OCR:"开始游戏"', "hits": 1}
    srv = ThreadingHTTPServer(("127.0.0.1", 0), OcrBridge)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown(); srv.server_close()


@pytest.fixture()
def srv(ocr_url, monkeypatch):
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", ocr_url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", OcrBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None
    yield S
    S._config = None
    S._client = None


def _last(path: str) -> dict:
    for p, body in reversed(OcrBridge.seen):
        if p == path:
            return body
    raise AssertionError(f"没有发到 {path}")


# ------------------------------------------------------------------ ocr


@pytest.mark.asyncio
async def test_ocr_lists_lines_with_centers(srv):
    out = await srv.phone_ocr()
    assert "2 行文字" in out
    assert "开始游戏" in out
    # 中心点 = ((400+880)/2, (1200+1300)/2) = (640, 1250)
    assert "(640, 1250)" in out
    assert "设置" in out


@pytest.mark.asyncio
async def test_ocr_contains_filter(srv):
    out = await srv.phone_ocr(contains="游戏")
    assert "开始游戏" in out
    assert "设置" not in out


@pytest.mark.asyncio
async def test_ocr_empty_result(srv):
    OcrBridge.ocr_reply = {"ok": True, "count": 0, "lines": [], "screen": {"w": 1280, "h": 2772}}
    out = await srv.phone_ocr()
    assert "没有识别到文字" in out


@pytest.mark.asyncio
async def test_ocr_default_full_resolution(srv):
    """默认不缩小——缩小会降低识别率，且坐标换算容易出错。"""
    await srv.phone_ocr()
    assert _last("/ocr")["max_width"] == 0


# ------------------------------------------------------------------ ocr_click


@pytest.mark.asyncio
async def test_ocr_click_success(srv):
    out = await srv.phone_ocr_click(text="开始游戏")
    assert "成功" in out
    assert "开始游戏" in out
    assert _last("/ocr_click")["text"] == "开始游戏"


@pytest.mark.asyncio
async def test_ocr_click_reports_multiple_hits(srv):
    OcrBridge.ocr_click_reply = {"ok": True, "target": 'OCR:"确定"', "hits": 3}
    out = await srv.phone_ocr_click(text="确定", index=1)
    assert "共 3 处匹配" in out
    assert _last("/ocr_click")["index"] == 1


@pytest.mark.asyncio
async def test_ocr_click_passes_index(srv):
    await srv.phone_ocr_click(text="确定", index=2)
    assert _last("/ocr_click")["index"] == 2


# ------------------------------------------------------------------ 资金保护


@pytest.mark.asyncio
async def test_ocr_click_blocks_money_text(srv):
    """OCR 目标是「确认支付」时必须被服务端兜底拦住。"""
    with pytest.raises(Exception) as info:
        await srv.phone_ocr_click(text="确认支付")
    assert "资金保护" in str(info.value)


@pytest.mark.asyncio
async def test_ocr_click_blocks_before_reaching_phone(srv):
    with pytest.raises(Exception):
        await srv.phone_ocr_click(text="立即购买")
    assert not any(p == "/ocr_click" for p, _ in OcrBridge.seen)


@pytest.mark.asyncio
async def test_ocr_click_allows_ordinary_text(srv):
    out = await srv.phone_ocr_click(text="开始游戏")
    assert "成功" in out
