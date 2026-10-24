"""录制与回放测试。

重点不是「能不能录」，而是：
1. 失败/未生效的步骤不能被录进去
2. 回放遇到失败必须停，不能带着错位界面继续点
3. 用户中断 / 资金拦截要穿透回放，不能被吞掉
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from phone_mcp.recorder import Recorder, RecorderError, Step


class ReplayBridge(BaseHTTPRequestHandler):
    """假手机：可编程地控制每个请求的返回。"""

    e2e_key = ""
    device_id = "phone-test"
    seen: list[tuple[str, dict]] = []
    # path -> 依次返回的 payload 列表；用完后重复最后一个
    script: dict[str, list[dict]] = {}

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

        path = self._path_only()
        payload = open_sealed(self.e2e_key, self.device_id, path, raw["e2ee"])
        ReplayBridge.seen.append((path, payload))

        replies = ReplayBridge.script.get(path)
        if replies:
            reply = replies.pop(0) if len(replies) > 1 else replies[0]
            code = reply.pop("__status", 200) if isinstance(reply, dict) else 200
            return self._reply(code, reply)
        self._reply(200, {"ok": True})


@pytest.fixture()
def bridge(e2e_key):
    ReplayBridge.seen = []
    ReplayBridge.script = {}
    ReplayBridge.e2e_key = e2e_key
    srv = ThreadingHTTPServer(("127.0.0.1", 0), ReplayBridge)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{srv.server_address[1]}"
    finally:
        srv.shutdown(); srv.server_close()


@pytest.fixture()
def srv(bridge, monkeypatch, tmp_path):
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", bridge)
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "t")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", ReplayBridge.e2e_key)
    monkeypatch.setenv("PHONE_MCP_RECORDINGS_DIR", str(tmp_path))

    from phone_mcp import server as S
    S._config = None
    S._client = None
    # 每个用例用全新的 recorder，避免跨用例串味
    S._recorder_instance = Recorder(tmp_path)
    yield S
    S._config = None
    S._client = None
    S._recorder_instance = None


# ------------------------------------------------------------------ 纯逻辑


def test_safe_name_rejects_traversal(tmp_path):
    r = Recorder(tmp_path)
    for bad in ("../evil", "a/b", "a\\b", "", "   ", ".", ".."):
        with pytest.raises(RecorderError):
            r.start(bad)


def test_parse_target_extracts_locators(tmp_path):
    from phone_mcp.recorder import _parse_target

    got = _parse_target('text="发送" desc="确认" id=com.app:id/btn bounds=[0,0,10,10]')
    assert got == {"text": "发送", "desc": "确认", "view_id": "com.app:id/btn"}


def test_parse_target_ignores_empty(tmp_path):
    from phone_mcp.recorder import _parse_target

    assert _parse_target('text="" desc="" bounds=[0,0,1,1]') == {}
    assert _parse_target(None) == {}


def test_recorder_ignores_readonly_tools(tmp_path):
    r = Recorder(tmp_path)
    r.start("x")
    r.append("phone_observe", {})
    r.append("phone_ui", {})
    r.append("phone_click", {"text": "确定"})
    rec = r.stop()
    assert [s.tool for s in rec.steps] == ["phone_click"]


def test_suspended_recording_ignored(tmp_path):
    """回放期间不能把重放动作录进当前录制。"""
    r = Recorder(tmp_path)
    r.start("x")
    r.append("phone_click", {"text": "a"})
    with r.suspended():
        r.append("phone_click", {"text": "b"})
    r.append("phone_click", {"text": "c"})
    rec = r.stop()
    assert [s.args["text"] for s in rec.steps] == ["a", "c"]


def test_start_twice_errors(tmp_path):
    r = Recorder(tmp_path)
    r.start("x")
    with pytest.raises(RecorderError):
        r.start("y")


def test_overwrite_required(tmp_path):
    r = Recorder(tmp_path)
    r.start("x")
    r.append("phone_click", {"text": "a"})
    r.stop()
    with pytest.raises(RecorderError):
        r.start("x")
    r.start("x", overwrite=True)  # 显式覆盖才允许
    r.cancel()


def test_empty_recording_not_saved(tmp_path):
    r = Recorder(tmp_path)
    r.start("empty")
    r.stop()
    assert r.list() == []


def test_roundtrip_and_rename(tmp_path):
    r = Recorder(tmp_path)
    r.start("old")
    r.append("phone_click", {"text": "确定"}, target='text="确定" id=a:id/b')
    r.stop()
    r.rename("old", "new")
    rec = r.load("new")
    assert rec.name == "new"
    assert rec.steps[0].fallback == {"text": "确定", "view_id": "a:id/b"}
    assert [x["name"] for x in r.list()] == ["new"]


# ------------------------------------------------------------------ 录制链路


@pytest.mark.asyncio
async def test_record_captures_successful_actions(srv):
    await srv.phone_record(name="流程", action="start")
    await srv.phone_click(text="确定")
    await srv.phone_launch(package="com.tencent.mm")
    out = await srv.phone_record(name="流程", action="stop")
    assert "2 步" in out
    rec = srv._recorder().load("流程")
    assert [s.tool for s in rec.steps] == ["phone_click", "phone_launch"]


@pytest.mark.asyncio
async def test_record_skips_no_effect_steps(srv):
    """手势成功但界面没变化 = 这步没做成，录进去只会重演一次无效动作。"""
    ReplayBridge.script = {"/click": [{"ok": True, "no_effect": True}]}
    await srv.phone_record(name="流程", action="start")
    await srv.phone_click(text="确定")
    out = await srv.phone_record(name="流程", action="stop")
    assert "没有录到任何动作" in out


@pytest.mark.asyncio
async def test_record_skips_failed_steps(srv):
    ReplayBridge.script = {"/click": [{"ok": False, "error": "找不到"}]}
    await srv.phone_record(name="流程", action="start")
    await srv.phone_click(text="不存在")
    out = await srv.phone_record(name="流程", action="stop")
    assert "没有录到任何动作" in out


@pytest.mark.asyncio
async def test_record_captures_step_label(srv):
    await srv.phone_record(name="流程", action="start")
    await srv.phone_click(text="确定", step="点确定按钮")
    await srv.phone_record(name="流程", action="stop")
    rec = srv._recorder().load("流程")
    assert rec.steps[0].step == "点确定按钮"


# ------------------------------------------------------------------ 回放


def _seed(srv, steps: list[Step], name: str = "流程"):
    r = srv._recorder()
    r.start(name)
    for s in steps:
        r._active.steps.append(s)
    r.stop()


@pytest.mark.asyncio
async def test_replay_all_steps(srv):
    _seed(srv, [
        Step(tool="phone_launch", args={"package": "com.tencent.mm"}, step="打开微信"),
        Step(tool="phone_click", args={"text": "发送"}, step="点发送"),
    ])
    out = await srv.phone_replay(name="流程")
    assert "2 步全部成功" in out
    assert "打开微信" in out


@pytest.mark.asyncio
async def test_replay_stops_at_first_failure(srv):
    """关键行为：失败就停，绝不带着错位界面继续点。"""
    _seed(srv, [
        Step(tool="phone_click", args={"text": "第一步"}, step="第一步"),
        Step(tool="phone_click", args={"text": "会失败"}, step="第二步"),
        Step(tool="phone_click", args={"text": "不该执行"}, step="第三步"),
    ])
    # 第二次 click 失败
    ReplayBridge.script = {"/click": [{"ok": True}, {"ok": False, "error": "找不到节点"}]}
    out = await srv.phone_replay(name="流程")
    assert "中止" in out
    assert "第二步" in out
    assert "不该执行" not in out
    # 第三步确实没发出去
    clicks = [b for p, b in ReplayBridge.seen if p == "/click"]
    assert len(clicks) == 2


@pytest.mark.asyncio
async def test_replay_stops_on_no_effect_when_requested(srv):
    _seed(srv, [
        Step(tool="phone_click", args={"text": "a"}),
        Step(tool="phone_click", args={"text": "b"}),
    ])
    ReplayBridge.script = {"/click": [{"ok": True, "no_effect": True}]}
    out = await srv.phone_replay(name="流程", stop_on_warning=True)
    assert "中止" in out
    assert len([1 for p, _ in ReplayBridge.seen if p == "/click"]) == 1


@pytest.mark.asyncio
async def test_replay_can_ignore_no_effect(srv):
    _seed(srv, [
        Step(tool="phone_click", args={"text": "a"}),
        Step(tool="phone_click", args={"text": "b"}),
    ])
    ReplayBridge.script = {"/click": [{"ok": True, "no_effect": True}]}
    out = await srv.phone_replay(name="流程", stop_on_warning=False)
    assert "2 步全部成功" in out


@pytest.mark.asyncio
async def test_replay_replaces_node_id_with_fallback(srv):
    """node_id 只在单次快照内有效，回放时必须换成文本重新定位。"""
    _seed(srv, [
        Step(
            tool="phone_click",
            args={"node_id": "n12"},
            fallback={"text": "发送", "view_id": "com.app:id/btn"},
        ),
    ])
    await srv.phone_replay(name="流程")
    body = [b for p, b in ReplayBridge.seen if p == "/click"][0]
    assert "node_id" not in body
    assert body["text"] == "发送"
    assert body["view_id"] == "com.app:id/btn"


@pytest.mark.asyncio
async def test_replay_unknown_recording(srv):
    out = await srv.phone_replay(name="不存在")
    assert "录制错误" in out


@pytest.mark.asyncio
async def test_replay_does_not_record_itself(srv):
    """回放期间要暂停录制，否则 A 的步骤会被录进 B。"""
    _seed(srv, [Step(tool="phone_click", args={"text": "a"})], name="A")
    await srv.phone_record(name="B", action="start")
    await srv.phone_replay(name="A")
    out = await srv.phone_record(name="B", action="stop")
    assert "没有录到任何动作" in out


@pytest.mark.asyncio
async def test_replay_propagates_money_block(srv):
    """资金拦截必须穿透回放——不能被当成普通失败吞掉。"""
    _seed(srv, [Step(tool="phone_click", args={"text": "确认支付"})])
    with pytest.raises(Exception) as info:
        await srv.phone_replay(name="流程")
    assert "资金保护" in str(info.value)


@pytest.mark.asyncio
async def test_replay_propagates_user_stop(srv):
    """用户中断同样要穿透。"""
    _seed(srv, [Step(tool="phone_click", args={"text": "确定"})])
    ReplayBridge.script = {
        "/click": [{"__status": 409, "ok": False, "user_operating": True,
                    "retry_after_ms": 8000, "error": "用户已中断"}]
    }
    with pytest.raises(Exception) as info:
        await srv.phone_replay(name="流程")
    assert "中断" in str(info.value)
