import asyncio
import json
from pathlib import Path

import aiohttp
import pytest

from phone_mcp.config import ConfigError, load_config
from test_relay import FakePhone, relay, relay_server

ADMIN = {"Authorization": "Bearer ctl-secret"}


async def create_pairing(session, base):
    response = await session.post(base + "/pairings", headers=ADMIN,
                                  json={"relay_url": "https://example.com:8900"})
    assert response.status == 200
    assert response.headers["Cache-Control"] == "no-store"
    return await response.json()


@pytest.mark.asyncio
async def test_qr_one_use_credentials_persist_and_are_scoped(relay_server, monkeypatch):
    base, ws_base = relay_server
    # 本用例会连续做 6 次越权尝试来验证作用域隔离，需要放宽爆破阈值，
    # 否则会被锁定机制拦成 429（锁定本身另有专门用例覆盖）。
    monkeypatch.setattr(relay, "AUTH_FAIL_LIMIT", 100)
    async with aiohttp.ClientSession() as session:
        created = await create_pairing(session, base)
        qr = created["qr"]
        assert set(qr) == {"type", "version", "relay_url", "code"}
        response = await session.post(base + "/pair", json={"code": qr["code"]})
        assert response.status == 200
        paired = await response.json()
        assert paired["device_id"] == created["device_id"]
        assert (await session.post(base + "/pair", json={"code": qr["code"]})).status == 401
        saved = relay.REGISTRY_FILE.read_text()
        assert paired["device_token"] not in saved
        assert created["control_token"] not in saved
        assert qr["code"] not in saved
        # 模拟进程重启后从磁盘恢复，仍能连接。
        relay.REGISTRY.clear()
        relay.REGISTRY.update(json.loads(saved))
        phone = FakePhone(ws_base, paired["device_id"], paired["device_token"])
        await phone.start()
        try:
            scoped = {"Authorization": "Bearer " + created["control_token"]}
            assert (await session.post(base + f"/device/{phone.device_id}/tree", headers=scoped)).status == 200
            assert (await session.post(base + "/device/phone-abc123/tree", headers=scoped)).status == 401
            for path in ("/devices", "/health"):
                assert (await session.get(base + path, headers=scoped)).status == 401
            assert (await session.post(base + "/pairings", headers=scoped, json={})).status == 401
            assert (await session.delete(base + f"/devices/{phone.device_id}", headers=scoped)).status == 401
            device_headers = {"Authorization": "Bearer " + paired["device_token"]}
            assert (await session.post(base + f"/device/{phone.device_id}/tree", headers=device_headers)).status == 401
        finally:
            await phone.stop()


@pytest.mark.asyncio
async def test_expired_and_concurrent_redemption(relay_server):
    base, _ = relay_server
    async with aiohttp.ClientSession() as session:
        first = await create_pairing(session, base)
        code = first["qr"]["code"]
        relay.PAIRINGS[relay._hash_token(code)]["expires"] = 0
        assert (await session.post(base + "/pair", json={"code": code})).status == 401
        second = await create_pairing(session, base)
        responses = await asyncio.gather(*[
            session.post(base + "/pair", json={"code": second["qr"]["code"]}) for _ in range(2)
        ])
        assert sorted(r.status for r in responses) == [200, 401]


@pytest.mark.asyncio
async def test_revoke_closes_socket_fails_pending_and_survives_reload(relay_server):
    base, ws_base = relay_server
    phone = FakePhone(ws_base, "phone-abc123", "dev-secret")
    await phone.start()
    phone.reply_override = lambda data: {"type": "noop"}
    try:
        async with aiohttp.ClientSession() as session:
            task = asyncio.create_task(session.post(base + "/device/phone-abc123/click", headers=ADMIN))
            for _ in range(100):
                if relay.DEVICES[phone.device_id].pending:
                    break
                await asyncio.sleep(0.01)
            assert relay.DEVICES[phone.device_id].pending
            response = await session.delete(base + "/devices/phone-abc123", headers=ADMIN)
            assert response.status == 200
            assert (await asyncio.wait_for(task, 2)).status == 503
            assert "phone-abc123" not in relay.DEVICES
            relay.REGISTRY.clear()
            relay.REGISTRY.update(json.loads(relay.REGISTRY_FILE.read_text()))
            assert not relay._check_device_token("phone-abc123", "dev-secret")
            assert (await session.post(base + "/device/phone-abc123/tree",
                                      headers={"Authorization": "Bearer scoped-secret"})).status == 401
            with pytest.raises(aiohttp.WSServerHandshakeError) as error:
                await session.ws_connect(ws_base + "/tunnel?device=phone-abc123",
                                         headers={"Authorization": "Bearer dev-secret"})
            assert error.value.status == 401
    finally:
        await phone.stop()


@pytest.mark.asyncio
async def test_failed_persistence_does_not_issue_credentials(relay_server, monkeypatch):
    base, _ = relay_server
    def fail(_):
        raise OSError("disk unavailable")
    monkeypatch.setattr(relay, "_save_registry", fail)
    async with aiohttp.ClientSession() as session:
        created = await create_pairing(session, base)
        response = await session.post(base + "/pair", json={"code": created["qr"]["code"]})
        assert response.status == 500
        assert created["device_id"] not in relay.REGISTRY
        assert relay._hash_token(created["qr"]["code"]) in relay.PAIRINGS


@pytest.mark.asyncio
async def test_invalid_pairing_inputs_and_admin_boundary(relay_server):
    base, _ = relay_server
    async with aiohttp.ClientSession() as session:
        assert (await session.post(base + "/pairings", json={})).status == 401
        for origin in ("http://example.com", "https://u:p@example.com", "https://example.com?token=x",
                       "https://example.com/path", "https://example.com:99999"):
            assert (await session.post(base + "/pairings", headers=ADMIN, json={"relay_url": origin})).status == 400
        for body in ([], {}, {"code": None}, {"code": "x" * 129}):
            assert (await session.post(base + "/pair", json=body)).status == 400
        assert not relay._check_device_token("unpaired", "dev-secret")


@pytest.mark.parametrize("url", ["http://example.com", "https://u:p@example.com", "https://example.com?token=x",
                                  "https://example.com/path", "https://example.com:99999"])
def test_config_rejects_insecure_relay(monkeypatch, url):
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", url)
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "secret")
    with pytest.raises(ConfigError):
        load_config()


def test_config_reads_private_token_file(monkeypatch, tmp_path):
    token = tmp_path / "token"
    token.write_text("file-secret\n")
    monkeypatch.setenv("PHONE_MCP_RELAY_URL", "https://example.com")
    monkeypatch.setenv("PHONE_MCP_DEVICE_ID", "phone-test")
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN", "old-secret")
    monkeypatch.setenv("PHONE_MCP_RELAY_TOKEN_FILE", str(token))
    monkeypatch.setenv("PHONE_MCP_E2E_KEY", "k" * 43)
    assert load_config().relay_token == "file-secret"
    assert "file-secret" not in repr(load_config())
    token.unlink()
    with pytest.raises(ConfigError, match="凭据文件"):
        load_config()


def test_deployed_relay_matches_tested_copy():
    root = Path(__file__).resolve().parents[2]
    assert (root / "relay/relay.py").read_bytes() == (root / "server/relay_pkg/relay.py").read_bytes()


def test_connection_request_ids_do_not_collide():
    first = relay.Device(device_id="phone-test", ws=None)
    second = relay.Device(device_id="phone-test", ws=None)
    assert first.next_id() != second.next_id()


@pytest.mark.asyncio
async def test_revoke_pending_pairing(relay_server):
    base, _ = relay_server
    async with aiohttp.ClientSession() as session:
        created = await create_pairing(session, base)
        response = await session.delete(base + "/devices/" + created["device_id"], headers=ADMIN)
        assert response.status == 200
        response = await session.post(base + "/pair", json={"code": created["qr"]["code"]})
        assert response.status == 401


def test_pairing_artifacts_do_not_embed_long_term_secret_in_qr(tmp_path, monkeypatch):
    import qrcode
    from phone_mcp.pairing import save_pairing
    captured = []
    make = qrcode.make
    def capture(payload):
        captured.append(json.loads(payload))
        return make(payload)
    monkeypatch.setattr(qrcode, "make", capture)
    result = {"qr": {"type": "phone-mcp-pair", "version": 1,
                     "relay_url": "https://example.com", "code": "one-use-code"},
              "device_id": "phone-test", "control_token": "controller-secret"}
    save_pairing(result, "https://example.com", tmp_path)
    # 二维码里额外带端到端密钥（这是它唯一一次离开本机，不进中继）
    assert len(captured) == 1
    qr = captured[0]
    for k, v in result["qr"].items():
        assert qr[k] == v
    assert len(qr["e2e_key"]) >= 40
    # 但长期凭据绝不能出现在二维码里
    assert result["control_token"] not in json.dumps(qr)
    assert "controller-secret" not in (tmp_path / "mcp-env.json").read_text()
    assert (tmp_path / "control.token").read_text() == "controller-secret"
    assert (tmp_path / "pairing.png").read_bytes().startswith(b"\x89PNG")
