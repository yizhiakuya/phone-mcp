"""Relay tests: real WebSocket phone client + real HTTP control client.

These run the actual aiohttp app in-process, so the phone-side and control-side
protocols are verified end to end without a phone or a server.
"""

from __future__ import annotations

import asyncio
import base64
import json
import os

import aiohttp
import pytest
from aiohttp import web

os.environ.setdefault("PHONE_MCP_DEVICE_TOKEN", "dev-secret")
os.environ.setdefault("PHONE_MCP_CONTROL_TOKEN", "ctl-secret")

import importlib

relay = importlib.import_module("relay_pkg.relay")


@pytest.fixture()
async def relay_server(tmp_path, monkeypatch):
    """起一个真实监听端口的 aiohttp 中继。"""
    monkeypatch.setattr(relay, "CONTROL_TOKEN", "ctl-secret")
    monkeypatch.setattr(relay, "REGISTRY_FILE", tmp_path / "registry.json")
    monkeypatch.setattr(relay, "REGISTRY", {
        "phone-abc123": {"device_hash": relay._hash_token("dev-secret"),
                         "control_hash": relay._hash_token("scoped-secret")}
    })
    monkeypatch.setattr(relay, "PAIRINGS", {})
    monkeypatch.setattr(relay, "DEVICES_LOCK", asyncio.Lock())
    # 每个用例从干净的失败计数开始，避免互相污染
    relay._AUTH_FAILURES.clear()
    monkeypatch.setattr(relay, "AUTH_FAIL_LIMIT", 3)
    monkeypatch.setattr(relay, "AUTH_LOCK_SECONDS", 60.0)
    app = relay.build_app()
    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = runner.addresses[0][1]
    try:
        yield f"http://127.0.0.1:{port}", f"ws://127.0.0.1:{port}"
    finally:
        await runner.cleanup()
        relay.DEVICES.clear()


class FakePhone:
    """模拟手机端：连上中继，按路径回固定结果。"""

    def __init__(self, ws_base: str, device_id: str, token: str) -> None:
        self.url = f"{ws_base}/tunnel?device={device_id}"
        self.headers = {"Authorization": f"Bearer {token}"}
        self.device_id = device_id
        self.session: aiohttp.ClientSession | None = None
        self.ws: aiohttp.ClientWebSocketResponse | None = None
        self.task: asyncio.Task | None = None
        self.received: list[dict] = []
        self.reply_override = None

    async def start(self) -> None:
        self.session = aiohttp.ClientSession()
        self.ws = await self.session.ws_connect(self.url, headers=self.headers)
        self.task = asyncio.create_task(self._loop())

    async def _loop(self) -> None:
        assert self.ws is not None
        async for msg in self.ws:
            if msg.type != aiohttp.WSMsgType.TEXT:
                continue
            data = json.loads(msg.data)
            self.received.append(data)
            if data.get("type") != "request":
                continue
            if self.reply_override is not None:
                await self.ws.send_str(json.dumps(self.reply_override(data)))
                continue
            await self.ws.send_str(
                json.dumps(
                    {
                        "type": "response",
                        "id": data["id"],
                        "status": 200,
                        "body": {"ok": True, "echo_path": data["path"], "echo": data["body"]},
                    }
                )
            )

    async def stop(self) -> None:
        if self.task:
            self.task.cancel()
        if self.ws:
            await self.ws.close()
        if self.session:
            await self.session.close()


@pytest.mark.asyncio
async def test_device_connects_and_forwards(relay_server):
    http_base, ws_base = relay_server
    phone = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await phone.start()
    await asyncio.sleep(0.1)

    async with aiohttp.ClientSession() as s:
        r = await s.post(
            f"{http_base}/device/phone-abc123/tree",
            json={"max_depth": 5},
            headers={"Authorization": "Bearer ctl-secret"},
        )
        assert r.status == 200
        body = await r.json()
        assert body["ok"] is True
        assert body["echo_path"] == "/tree"
        assert body["echo"] == {"max_depth": 5}

    await phone.stop()


@pytest.mark.asyncio
async def test_control_requires_token(relay_server):
    http_base, ws_base = relay_server
    phone = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await phone.start()
    await asyncio.sleep(0.1)

    async with aiohttp.ClientSession() as s:
        for headers in ({}, {"Authorization": "Bearer wrong"}, {"Authorization": "ctl-secret"}):
            r = await s.post(f"{http_base}/device/phone-abc123/tree", json={}, headers=headers)
            assert r.status == 401, headers

    await phone.stop()


@pytest.mark.asyncio
async def test_device_requires_token(relay_server):
    _, ws_base = relay_server
    async with aiohttp.ClientSession() as s:
        for headers in ({}, {"Authorization": "Bearer wrong"}):
            with pytest.raises(aiohttp.WSServerHandshakeError) as info:
                await s.ws_connect(
                    f"{ws_base}/tunnel?device=phone-x", headers=headers
                )
            assert info.value.status == 401


@pytest.mark.asyncio
async def test_device_token_cannot_control(relay_server):
    """设备 token 泄漏也不能下发指令——这是两把钥匙分离的核心意义。"""
    http_base, ws_base = relay_server
    phone = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await phone.start()
    await asyncio.sleep(0.1)

    async with aiohttp.ClientSession() as s:
        r = await s.post(
            f"{http_base}/device/phone-abc123/tree",
            json={},
            headers={"Authorization": "Bearer dev-secret"},
        )
        assert r.status == 401

    await phone.stop()


@pytest.mark.asyncio
async def test_control_token_cannot_register_device(relay_server):
    """控制 token 也不能伪装成手机注册通道。"""
    _, ws_base = relay_server
    async with aiohttp.ClientSession() as s:
        with pytest.raises(aiohttp.WSServerHandshakeError) as info:
            await s.ws_connect(
                f"{ws_base}/tunnel?device=phone-x",
                headers={"Authorization": "Bearer ctl-secret"},
            )
        assert info.value.status == 401


@pytest.mark.asyncio
async def test_bad_device_id_rejected(relay_server):
    _, ws_base = relay_server
    async with aiohttp.ClientSession() as s:
        for bad in ("../etc", "a b", "x" * 100, ""):
            with pytest.raises(aiohttp.WSServerHandshakeError) as info:
                await s.ws_connect(
                    f"{ws_base}/tunnel?device={bad}",
                    headers={"Authorization": "Bearer dev-secret"},
                )
            assert info.value.status == 400


@pytest.mark.asyncio
async def test_offline_device_returns_503(relay_server):
    http_base, _ = relay_server
    async with aiohttp.ClientSession() as s:
        r = await s.post(
            f"{http_base}/device/phone-missing/tree",
            json={},
            headers={"Authorization": "Bearer ctl-secret"},
        )
        assert r.status == 503
        body = await r.json()
        assert body["online"] is False


@pytest.mark.asyncio
async def test_binary_screenshot_passthrough(relay_server):
    http_base, ws_base = relay_server
    phone = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await phone.start()
    await asyncio.sleep(0.1)

    jpeg = b"\xff\xd8\xff\xe0fakejpeg"
    phone.reply_override = lambda data: {
        "type": "response",
        "id": data["id"],
        "status": 200,
        "content_type": "image/jpeg",
        "body_b64": base64.b64encode(jpeg).decode(),
    }

    async with aiohttp.ClientSession() as s:
        r = await s.post(
            f"{http_base}/device/phone-abc123/screenshot",
            json={"max_width": 900},
            headers={"Authorization": "Bearer ctl-secret"},
        )
        assert r.status == 200
        assert r.content_type == "image/jpeg"
        assert await r.read() == jpeg

    await phone.stop()


@pytest.mark.asyncio
async def test_health_lists_devices(relay_server):
    http_base, ws_base = relay_server
    phone = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await phone.start()
    await asyncio.sleep(0.1)

    async with aiohttp.ClientSession() as s:
        r = await s.get(f"{http_base}/health", headers={"Authorization": "Bearer ctl-secret"})
        data = await r.json()
        assert data["count"] == 1
        assert data["devices"][0]["device_id"] == "phone-abc123"

        r2 = await s.get(f"{http_base}/health")
        assert r2.status == 401

    await phone.stop()


@pytest.mark.asyncio
async def test_reconnect_replaces_old_connection(relay_server):
    http_base, ws_base = relay_server
    first = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await first.start()
    await asyncio.sleep(0.1)

    second = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await second.start()
    await asyncio.sleep(0.2)

    async with aiohttp.ClientSession() as s:
        r = await s.post(
            f"{http_base}/device/phone-abc123/tree",
            json={"x": 1},
            headers={"Authorization": "Bearer ctl-secret"},
        )
        assert r.status == 200

    assert len(second.received) == 1
    assert len(first.received) == 0

    await second.stop()
    await first.stop()


@pytest.mark.asyncio
async def test_disconnect_fails_inflight_request(relay_server):
    http_base, ws_base = relay_server
    phone = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await phone.start()
    await asyncio.sleep(0.1)

    # 让手机不回复，请求挂起；然后断开手机
    phone.reply_override = lambda data: {"type": "noop"}
    async with aiohttp.ClientSession() as s:
        task = asyncio.create_task(
            s.post(
                f"{http_base}/device/phone-abc123/tree",
                json={},
                headers={"Authorization": "Bearer ctl-secret"},
            )
        )
        await asyncio.sleep(0.2)
        await phone.stop()
        r = await asyncio.wait_for(task, timeout=5)
        assert r.status == 503

    await phone.stop()


# ---- 鉴权失败锁定 ----

@pytest.mark.asyncio
async def test_repeated_bad_tokens_trigger_lockout(relay_server):
    """连续失败达到阈值后，即使拿对 token 也被拒（防止无限试探）。"""
    http_base, _ = relay_server
    async with aiohttp.ClientSession() as s:
        # 前 AUTH_FAIL_LIMIT 次：401
        for _ in range(3):
            r = await s.post(
                f"{http_base}/device/phone-abc123/tree",
                json={},
                headers={"Authorization": "Bearer wrong"},
            )
            assert r.status == 401

        # 达到阈值后再试：429，且换正确 token 也一样
        r = await s.post(
            f"{http_base}/device/phone-abc123/tree",
            json={},
            headers={"Authorization": "Bearer wrong"},
        )
        assert r.status == 429
        assert "Retry-After" in r.headers

        r = await s.post(
            f"{http_base}/device/phone-abc123/tree",
            json={},
            headers={"Authorization": "Bearer scoped-secret"},
        )
        assert r.status == 429, "锁定期间正确凭据也应被拒"


@pytest.mark.asyncio
async def test_successful_auth_resets_failure_count(relay_server):
    """成功鉴权应清零计数，避免正常用户偶尔输错被锁。"""
    http_base, _ = relay_server
    async with aiohttp.ClientSession() as s:
        for _ in range(2):
            await s.post(
                f"{http_base}/device/phone-abc123/tree",
                json={},
                headers={"Authorization": "Bearer wrong"},
            )
        # 成功一次
        r = await s.post(
            f"{http_base}/device/phone-abc123/tree",
            json={},
            headers={"Authorization": "Bearer scoped-secret"},
        )
        assert r.status in (200, 503)   # 503 = 设备不在线，但鉴权已过
        assert relay._auth_locked("dev:phone-abc123") == 0

        # 计数已清零，再失败 2 次仍不应锁定
        for _ in range(2):
            await s.post(
                f"{http_base}/device/phone-abc123/tree",
                json={},
                headers={"Authorization": "Bearer wrong"},
            )
        assert relay._auth_locked("dev:phone-abc123") == 0


@pytest.mark.asyncio
async def test_lockout_isolated_per_device(relay_server, tmp_path):
    """一台设备被锁，不影响其它设备。"""
    http_base, _ = relay_server
    relay.REGISTRY["phone-other"] = {
        "device_hash": relay._hash_token("other-dev"),
        "control_hash": relay._hash_token("other-ctl"),
    }
    async with aiohttp.ClientSession() as s:
        for _ in range(4):
            await s.post(
                f"{http_base}/device/phone-abc123/tree",
                json={},
                headers={"Authorization": "Bearer wrong"},
            )
        # phone-abc123 已锁
        r = await s.post(
            f"{http_base}/device/phone-abc123/tree",
            json={},
            headers={"Authorization": "Bearer scoped-secret"},
        )
        assert r.status == 429
        # 另一台设备不受影响
        r = await s.post(
            f"{http_base}/device/phone-other/tree",
            json={},
            headers={"Authorization": "Bearer other-ctl"},
        )
        assert r.status != 429


@pytest.mark.asyncio
async def test_pairing_code_bruteforce_locked(relay_server):
    """配对码爆破同样会被锁定。"""
    http_base, _ = relay_server
    async with aiohttp.ClientSession() as s:
        for _ in range(3):
            r = await s.post(f"{http_base}/pair", json={"code": "guess"})
            assert r.status == 401
        r = await s.post(f"{http_base}/pair", json={"code": "guess"})
        assert r.status == 429
