"""资金保护（服务端兜底层）测试。

真正的拦截在手机端 MoneyGuard.kt，这里只验证服务端兜底不会漏、也不会误伤。
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from phone_mcp.bridge import BridgeClient, MoneyBlockedError
from phone_mcp.config import Config
from phone_mcp.moneyguard import BLOCKED_PACKAGES, MONEY_KEYWORDS, MoneyBlocked, check


# ------------------------------------------------------------------ 纯函数


@pytest.mark.parametrize(
    "payload",
    [
        {"text": "确认支付"},
        {"text": "立即购买"},
        {"desc": "去支付"},
        {"view_id": "btn_pay_confirm"},
        {"node_id": "n12", "text": "提交订单"},
        {"package": "com.eg.android.AlipayGphone"},
    ],
)
def test_blocks_money_targets(payload):
    with pytest.raises(MoneyBlocked):
        check("/click", payload)


def test_blocks_launch_of_payment_app():
    with pytest.raises(MoneyBlocked):
        check("/launch", {"package": "com.icbc"})
    with pytest.raises(MoneyBlocked):
        check("/launch", {"package": "com.eg.android.AlipayGphone"})


def test_allows_ordinary_targets():
    for payload in (
        {"text": "发送"},
        {"text": "搜索"},
        {"node_id": "n3"},
        {"text": "设置"},
        {"package": "com.tencent.mm"},
    ):
        check("/click", payload)  # 不抛即通过


def test_value_field_is_not_keyword_scanned():
    """在聊天框里打「转账」两个字是正常需求，不能拦。"""
    check("/set_text", {"node_id": "n7", "value": "我明天把转账的事办了"})


def test_coordinates_are_not_keyword_scanned():
    """坐标点击没有可查文本，服务端不该瞎猜。"""
    check("/tap", {"x": 100.0, "y": 200.0})
    check("/swipe", {"x1": 1, "y1": 2, "x2": 3, "y2": 4, "duration_ms": 300})


def test_empty_payload_ok():
    check("/health", None)
    check("/health", {})


def test_policy_tables_are_not_empty():
    assert len(BLOCKED_PACKAGES) >= 20
    assert "支付宝" not in " ".join(MONEY_KEYWORDS)  # 词表里只放动作词
    assert "确认支付" in MONEY_KEYWORDS


# ------------------------------------------------------------------ 传输层


class GuardBridge(BaseHTTPRequestHandler):
    """假手机：解出请求体后按需回 403 money_blocked。"""

    e2e_key = ""
    device_id = "phone-test"
    block = False
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
        GuardBridge.seen.append((self._path_only(), payload))
        if GuardBridge.block:
            return self._reply(403, {
                "ok": False,
                "money_blocked": True,
                "error": "「支付宝」是资金类应用，已禁止远程操作",
            })
        self._reply(200, {"ok": True})


@pytest.fixture()
def guard_url(e2e_key):
    GuardBridge.block = False
    GuardBridge.seen = []
    GuardBridge.e2e_key = e2e_key
    srv = ThreadingHTTPServer(("127.0.0.1", 0), GuardBridge)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown(); srv.server_close()


def _client(url, key):
    return BridgeClient(
        Config(relay_url=url, relay_token="t", device_id="phone-test", e2e_key=key, timeout=5)
    )


@pytest.mark.asyncio
async def test_request_never_leaves_when_locally_blocked(guard_url):
    """本地就能判定的，不该白跑一趟中继。"""
    c = _client(guard_url, GuardBridge.e2e_key)
    with pytest.raises(MoneyBlockedError):
        await c.post("/click", {"text": "确认支付"})
    assert GuardBridge.seen == []
    await c.aclose()


@pytest.mark.asyncio
async def test_phone_side_block_surfaces_as_money_error(guard_url):
    """手机端 403 + money_blocked 必须变成 MoneyBlockedError，不能吞成普通错误。"""
    c = _client(guard_url, GuardBridge.e2e_key)
    GuardBridge.block = True
    with pytest.raises(MoneyBlockedError) as info:
        await c.post("/click", {"node_id": "n5"})
    assert "支付宝" in str(info.value)
    await c.aclose()


@pytest.mark.asyncio
async def test_normal_action_passes_through(guard_url):
    c = _client(guard_url, GuardBridge.e2e_key)
    data = await c.post("/click", {"text": "发送"})
    assert data["ok"] is True
    assert len(GuardBridge.seen) == 1
    await c.aclose()


# ------------------------------------------------------------------ 工具层


@pytest.mark.asyncio
async def test_tool_raises_tool_error(guard_url, monkeypatch):
    """工具层必须抛异常——返回字符串模型会当成成功继续做。"""
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", guard_url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", GuardBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None

    with pytest.raises(Exception) as info:
        await S.phone_click(text="确认支付")
    msg = str(info.value)
    assert "资金保护" in msg
    assert "不要" in msg

    await S._client.aclose()
    S._config = None
    S._client = None


@pytest.mark.asyncio
async def test_read_tools_also_raise(guard_url, monkeypatch):
    """读取类工具也要抛 ToolError：余额/卡号/付款码都在界面里。"""
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", guard_url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", GuardBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None
    GuardBridge.block = True

    with pytest.raises(Exception) as info:
        await S.phone_ui()
    assert "资金保护" in str(info.value)

    await S._client.aclose()
    S._config = None
    S._client = None
    GuardBridge.block = False


@pytest.mark.asyncio
async def test_launch_payment_app_blocked_at_tool_layer(guard_url, monkeypatch):
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", guard_url)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", GuardBridge.e2e_key)

    from phone_mcp import server as S
    S._config = None
    S._client = None

    with pytest.raises(Exception) as info:
        await S.phone_launch(package="com.unionpay")
    assert "资金保护" in str(info.value)
    assert GuardBridge.seen == []

    await S._client.aclose()
    S._config = None
    S._client = None
