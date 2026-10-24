#!/usr/bin/env python3
"""
Phone MCP 隧道中继。

手机在 NAT / 移动网络后面，无法被电脑直接连。所以反过来：手机启动后主动向本中继
建立一条长连接（WebSocket），中继把这台手机的通道登记在内存里。电脑上的 MCP server
通过 HTTP 打到中继，中继把请求塞进对应手机的 WS 通道，等手机回结果，再原样返回。

    phone --wss--> relay <--https-- mcp server

## 鉴权设计

管理员凭据用于创建五分钟有效的一次性配对码、列出和撤销设备。
手机扫码后兑换独立设备凭据，电脑得到仅能控制该设备的控制凭据。
注册表只保存高熵凭据的 SHA-256 摘要，原子写入私有目录。
吊销同时断开现有通道；旧版共享设备 Token 不再受理。
中继只监听 127.0.0.1，HTTPS/WSS 由前置反向代理提供。
"""

from __future__ import annotations

import asyncio
import base64
import hashlib
import json
import logging
import os
import re
import secrets
import time
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

from aiohttp import WSMsgType, web

LOG = logging.getLogger("relay")

CONTROL_TOKEN = os.environ.get("PHONE_MCP_CONTROL_TOKEN", "").strip()
REGISTRY_FILE = Path(os.environ.get("PHONE_MCP_REGISTRY_FILE", "/var/lib/phonemcp-relay/registry.json"))
REGISTRY: dict[str, dict[str, str]] = {}
PAIRINGS: dict[str, dict[str, Any]] = {}
PAIRING_TTL = 300

MAX_PENDING_PER_DEVICE = 32
MAX_DEVICES = 16
REQUEST_TIMEOUT = float(os.environ.get("PHONE_MCP_RELAY_TIMEOUT", "60"))
DEVICE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")

# ---- 鉴权失败锁定 ----
# 目的：Token 一旦泄漏，攻击者也不能无限次尝试。256 位随机数本身穷举不了，
# 但真实泄漏往往来自日志/截图，攻击者会拿着近似值反复试。
AUTH_FAIL_LIMIT = int(os.environ.get("PHONE_MCP_AUTH_FAIL_LIMIT", "20"))
AUTH_FAIL_WINDOW = float(os.environ.get("PHONE_MCP_AUTH_FAIL_WINDOW", "300"))    # 统计窗口
AUTH_LOCK_SECONDS = float(os.environ.get("PHONE_MCP_AUTH_LOCK_SECONDS", "900"))  # 锁定时长

# key = 来源 IP 或 device_id，value = [失败次数, 窗口起点, 锁定截止]
_AUTH_FAILURES: dict[str, list[float]] = {}


def _auth_key(request: web.Request | None, device_id: str | None) -> str:
    """失败计数键：按「来源 IP + 目标设备」组合。

    不能只用 device_id：那样攻击者和手机自己会共用同一个计数，
    手机正常重连失败几次就把自己锁死了。带上 IP 就能区分来源。
    """
    remote = None
    if request is not None:
        # 中继只听 127.0.0.1，真实来源在 nginx 透传的头里。
        # 这两个头由本机 nginx 设置，外部无法伪造（nginx 会覆盖）。
        remote = (request.headers.get("X-Real-IP")
                  or request.headers.get("X-Forwarded-For", "").split(",")[0].strip()
                  or request.remote)
    scope = f"dev:{device_id}" if device_id else "admin"
    return f"{remote or 'unknown'}|{scope}"


def _auth_locked(key: str, now: float | None = None) -> float:
    """返回剩余锁定秒数；0 表示未锁定。"""
    now = time.monotonic() if now is None else now
    entry = _AUTH_FAILURES.get(key)
    if not entry:
        return 0.0
    locked_until = entry[2]
    return max(0.0, locked_until - now)


def _record_auth_failure(key: str) -> None:
    now = time.monotonic()
    entry = _AUTH_FAILURES.get(key)
    if entry is None or now - entry[1] > AUTH_FAIL_WINDOW:
        entry = [0.0, now, 0.0]
        _AUTH_FAILURES[key] = entry
    entry[0] += 1
    if entry[0] >= AUTH_FAIL_LIMIT:
        entry[2] = now + AUTH_LOCK_SECONDS
        entry[0] = 0.0
        entry[1] = now
        LOG.warning("鉴权失败过多，锁定 %s 共 %.0fs", key, AUTH_LOCK_SECONDS)


def _clear_auth_failures(key: str) -> None:
    _AUTH_FAILURES.pop(key, None)


def _prune_auth_failures() -> None:
    """定期清理过期条目，避免无限增长。"""
    now = time.monotonic()
    for key in list(_AUTH_FAILURES):
        entry = _AUTH_FAILURES[key]
        if entry[2] and entry[2] < now:
            _AUTH_FAILURES.pop(key, None)
        elif not entry[2] and now - entry[1] > AUTH_FAIL_WINDOW * 2:
            _AUTH_FAILURES.pop(key, None)


@dataclass
class Device:
    """一台已连接手机。"""

    device_id: str
    ws: web.WebSocketResponse
    connected_at: float = field(default_factory=time.time)
    remote: str = ""
    pending: dict[str, asyncio.Future[dict[str, Any]]] = field(default_factory=dict)
    counter: int = 0
    session_id: str = field(default_factory=lambda: secrets.token_hex(8))

    def next_id(self) -> str:
        self.counter += 1
        return f"{self.device_id}-{self.session_id}-{self.counter}"

    @property
    def online_seconds(self) -> int:
        return int(time.time() - self.connected_at)


DEVICES: dict[str, Device] = {}
DEVICES_LOCK = asyncio.Lock()


# ---------------------------------------------------------------- 鉴权


def _bearer(request: web.Request) -> str:
    header = request.headers.get("Authorization", "")
    if not header.startswith("Bearer "):
        return ""
    return header[len("Bearer ") :].strip()


def _check_device_token(device_id: str, presented: str) -> bool:
    return _matches_token(presented, REGISTRY.get(device_id, {}).get("device_hash", ""))


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _matches_token(presented: str, expected_hash: str) -> bool:
    return bool(presented and expected_hash) and secrets.compare_digest(_hash_token(presented), expected_hash)


def _check_control_token(request: web.Request) -> bool:
    presented = _bearer(request)
    if not presented or not CONTROL_TOKEN:
        return False
    return _matches_token(presented, _hash_token(CONTROL_TOKEN))


def _require_control(request: web.Request, device_id: str | None = None) -> None:
    key = _auth_key(request, device_id)
    locked = _auth_locked(key)
    if locked > 0:
        # 锁定期间连"token 对不对"都不判断，直接拒绝，避免把锁定变成探测信号
        raise web.HTTPTooManyRequests(
            text="too many failed attempts",
            headers={"Retry-After": str(int(locked) + 1)},
        )

    scoped = device_id is not None and _matches_token(
        _bearer(request), REGISTRY.get(device_id, {}).get("control_hash", "")
    )
    if scoped or _check_control_token(request):
        _clear_auth_failures(key)
        return

    _record_auth_failure(key)
    # 故意不区分"没带"和"带错"，减少信息泄露
    raise web.HTTPUnauthorized(text="unauthorized")


def _save_registry(registry: dict[str, dict[str, str]]) -> None:
    REGISTRY_FILE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, name = tempfile.mkstemp(dir=REGISTRY_FILE.parent, prefix=".registry-")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as out:
            json.dump(registry, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(name, REGISTRY_FILE)
    finally:
        if os.path.exists(name):
            os.unlink(name)


async def create_pairing_handler(request: web.Request) -> web.Response:
    _require_control(request)
    try:
        body = await request.json()
        origin = body["relay_url"].rstrip("/")
        url = urlsplit(origin)
        valid = (url.scheme == "https" and url.hostname and url.port != 0
                 and not url.path and not url.query and not url.fragment and url.username is None)
    except (ValueError, KeyError, TypeError, AttributeError):
        valid = False
    if not valid:
        raise web.HTTPBadRequest(text="relay_url must be an HTTPS origin")
    now = time.monotonic()
    for key, value in list(PAIRINGS.items()):
        if value["expires"] <= now:
            PAIRINGS.pop(key)
    if len(PAIRINGS) >= 16:
        raise web.HTTPTooManyRequests(text="too many pending pairings")
    code = secrets.token_urlsafe(32)
    controller = secrets.token_urlsafe(32)
    device_id = "phone-" + secrets.token_hex(6)
    PAIRINGS[_hash_token(code)] = {
        "expires": now + PAIRING_TTL, "device_id": device_id,
        "control_hash": _hash_token(controller),
    }
    return web.json_response({
        "qr": {"type": "phone-mcp-pair", "version": 1, "relay_url": origin, "code": code},
        "device_id": device_id, "control_token": controller, "expires_in": PAIRING_TTL,
    }, headers={"Cache-Control": "no-store"})


async def pair_handler(request: web.Request) -> web.Response:
    try:
        body = await request.json()
        code = body["code"]
        if not isinstance(code, str) or len(code) > 128:
            raise ValueError()
    except (ValueError, KeyError, TypeError):
        raise web.HTTPBadRequest(text="invalid pairing request") from None
    # 校验、落盘、消费之间不 await，避免一个配对码被并发兑换两次。
    key = _hash_token(code)
    pairing = PAIRINGS.get(key)
    if pairing is None or pairing["expires"] <= time.monotonic():
        PAIRINGS.pop(key, None)
        # 配对码爆破同样计数，防止有人穷举有效码
        fail_key = _auth_key(request, None)
        if _auth_locked(fail_key) > 0:
            raise web.HTTPTooManyRequests(text="too many failed attempts")
        _record_auth_failure(fail_key)
        raise web.HTTPUnauthorized(text="pairing expired or already used")
    device_id = pairing["device_id"]
    token = secrets.token_urlsafe(32)
    updated = dict(REGISTRY)
    updated[device_id] = {"device_hash": _hash_token(token), "control_hash": pairing["control_hash"]}
    _save_registry(updated)
    REGISTRY.clear()
    REGISTRY.update(updated)
    PAIRINGS.pop(key)
    return web.json_response({"device_id": device_id, "device_token": token},
                             headers={"Cache-Control": "no-store"})


async def revoke_handler(request: web.Request) -> web.Response:
    _require_control(request)
    device_id = request.match_info["device"]
    updated = dict(REGISTRY)
    updated.pop(device_id, None)
    _save_registry(updated)
    REGISTRY.clear()
    REGISTRY.update(updated)
    for key, pairing in list(PAIRINGS.items()):
        if pairing["device_id"] == device_id:
            PAIRINGS.pop(key)
    async with DEVICES_LOCK:
        dev = DEVICES.pop(device_id, None)
    if dev is not None:
        for fut in list(dev.pending.values()):
            if not fut.done():
                fut.set_exception(RuntimeError("设备凭据已撤销"))
        dev.pending.clear()
        await dev.ws.close(code=4003, message=b"revoked")
    return web.json_response({"ok": True})


# ---------------------------------------------------------------- 手机侧


async def tunnel_handler(request: web.Request) -> web.WebSocketResponse:
    device_id = request.query.get("device", "").strip()

    if not DEVICE_ID_RE.match(device_id):
        raise web.HTTPBadRequest(text="bad device id")
    if not _check_device_token(device_id, _bearer(request)):
        LOG.warning("设备鉴权失败 device=%s remote=%s", device_id, request.remote)
        raise web.HTTPUnauthorized(text="unauthorized")

    async with DEVICES_LOCK:
        if device_id not in DEVICES and len(DEVICES) >= MAX_DEVICES:
            raise web.HTTPServiceUnavailable(text="too many devices")

    # 心跳 90s：必须长于客户端 ping(60s)，否则中继会误判客户端失联
    ws = web.WebSocketResponse(heartbeat=90, max_msg_size=16 * 1024 * 1024)
    await ws.prepare(request)

    async with DEVICES_LOCK:
        # 握手期间可能发生吊销；登记前再次检查。
        if not _check_device_token(device_id, _bearer(request)):
            await ws.close(code=4003, message=b"revoked")
            return ws
        old = DEVICES.get(device_id)
        if old is not None:
            LOG.info("设备 %s 重连，替换旧连接", device_id)
            for fut in list(old.pending.values()):
                if not fut.done():
                    fut.set_exception(RuntimeError("设备连接已替换"))
            old.pending.clear()
            await old.ws.close(code=4000, message=b"replaced")
        DEVICES[device_id] = Device(
            device_id=device_id, ws=ws, remote=request.remote or ""
        )
    LOG.info("设备上线 device=%s remote=%s", device_id, request.remote)

    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                await _handle_phone_message(device_id, msg.data)
            elif msg.type == WSMsgType.ERROR:
                LOG.warning("设备 %s WS 错误: %s", device_id, ws.exception())
    finally:
        async with DEVICES_LOCK:
            dev = DEVICES.get(device_id)
            if dev is not None and dev.ws is ws:
                # 先摘掉通道再收集在途请求，避免新请求挤进来
                DEVICES.pop(device_id, None)
                orphaned = list(dev.pending.values())
                dev.pending.clear()
            else:
                # 替换或吊销时已结束旧请求，不影响新连接。
                orphaned = []
        for fut in orphaned:
            if not fut.done():
                fut.set_exception(RuntimeError("设备已断开"))
        LOG.info("设备下线 device=%s", device_id)
    return ws


async def _handle_phone_message(device_id: str, raw: str) -> None:
    try:
        msg = json.loads(raw)
    except json.JSONDecodeError:
        LOG.warning("设备 %s 发来非 JSON 消息", device_id)
        return

    if msg.get("type") != "response":
        return

    async with DEVICES_LOCK:
        dev = DEVICES.get(device_id)
    if dev is None:
        return

    fut = dev.pending.pop(str(msg.get("id")), None)
    if fut is None or fut.done():
        return
    fut.set_result(msg)


# ---------------------------------------------------------------- MCP 侧


async def health_handler(request: web.Request) -> web.Response:
    _require_control(request)
    return web.json_response(
        {
            "ok": True,
            "count": len(DEVICES),
            "devices": [
                {
                    "device_id": d.device_id,
                    "remote": d.remote,
                    "online_seconds": d.online_seconds,
                    "inflight": len(d.pending),
                }
                for d in DEVICES.values()
            ],
        }
    )


async def devices_handler(request: web.Request) -> web.Response:
    _require_control(request)
    return web.json_response(
        {
            "devices": [
                {
                    "device_id": device_id,
                    "online": device_id in DEVICES,
                }
                for device_id in REGISTRY
            ]
        }
    )


async def forward_handler(request: web.Request) -> web.Response:
    device_id = request.match_info["device"]
    _require_control(request, device_id)
    path = "/" + request.match_info["path"].lstrip("/")

    async with DEVICES_LOCK:
        dev = DEVICES.get(device_id)
    if dev is None:
        return web.json_response(
            {"ok": False, "error": f"设备 {device_id} 不在线", "online": False},
            status=503,
        )
    if len(dev.pending) >= MAX_PENDING_PER_DEVICE:
        return web.json_response(
            {"ok": False, "error": "该设备请求积压过多"}, status=429
        )

    raw = await request.read()
    # 读取慢请求体时可能已被吊销，发送到手机前必须重新授权。
    _require_control(request, device_id)
    if DEVICES.get(device_id) is not dev:
        return web.json_response({"ok": False, "error": "设备连接已失效"}, status=503)
    try:
        body = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        return web.json_response({"ok": False, "error": "body 不是 JSON"}, status=400)

    req_id = dev.next_id()
    fut: asyncio.Future[dict[str, Any]] = asyncio.get_running_loop().create_future()
    dev.pending[req_id] = fut

    try:
        await dev.ws.send_str(
            json.dumps({"type": "request", "id": req_id, "path": path, "body": body})
        )
        reply = await asyncio.wait_for(fut, timeout=REQUEST_TIMEOUT)
    except asyncio.TimeoutError:
        return web.json_response(
            {"ok": False, "error": f"手机 {REQUEST_TIMEOUT:.0f}s 内无响应"}, status=504
        )
    except (ConnectionResetError, RuntimeError) as exc:
        return web.json_response({"ok": False, "error": str(exc)}, status=503)
    finally:
        dev.pending.pop(req_id, None)

    status = int(reply.get("status", 200))

    # 中继不解释响应内容——端到端加密后它只该原样转发。
    # 旧实现在这里读 reply["body"]，但加密响应用的字段是 "e2ee"，
    # 于是被吞成空对象 {}，调用方永远看不到真正的错误。
    if reply.get("body_b64"):
        data = base64.b64decode(reply["body_b64"])
        return web.Response(
            body=data,
            status=status,
            content_type=reply.get("content_type", "application/octet-stream"),
        )

    # 加密响应（e2ee）与明文诊断（plain_error）必须原样透传——
    # 中继无权解读，也不该改写。旧实现只取 reply["body"]，会把它们吞成 {}。
    if "e2ee" in reply or "plain_error" in reply:
        passthrough = {
            k: v for k, v in reply.items()
            if k not in ("type", "id", "status", "content_type", "body_b64")
        }
        return web.json_response(passthrough, status=status)

    # 明文响应：保持旧契约，直接返回 body 内容
    return web.json_response(reply.get("body") or {}, status=status)


# ---------------------------------------------------------------- 装配


def build_app() -> web.Application:
    app = web.Application(client_max_size=1024 * 1024)
    app.router.add_post("/pairings", create_pairing_handler)
    app.router.add_post("/pair", pair_handler)
    app.router.add_delete("/devices/{device}", revoke_handler)
    app.router.add_get("/health", health_handler)
    app.router.add_get("/devices", devices_handler)
    app.router.add_get("/tunnel", tunnel_handler)
    app.router.add_route("*", "/device/{device}/{path:.*}", forward_handler)
    return app


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    if not CONTROL_TOKEN:
        LOG.error("必须设置 PHONE_MCP_CONTROL_TOKEN")
        raise SystemExit(1)
    if REGISTRY_FILE.exists():
        REGISTRY.update(json.loads(REGISTRY_FILE.read_text(encoding="utf-8")))
    if os.environ.get("PHONE_MCP_DEVICE_TOKEN") or os.environ.get("PHONE_MCP_DEVICE_TOKENS"):
        LOG.warning("旧设备 Token 配置已禁用，请通过扫码配对登记设备")

    port = int(os.environ.get("PHONE_MCP_RELAY_PORT", "8903"))
    LOG.info(
        "中继监听 127.0.0.1:%s（设备凭据：%s）",
        port,
        f"已配对 {len(REGISTRY)} 台",
    )
    web.run_app(build_app(), host="127.0.0.1", port=port, print=None)


if __name__ == "__main__":
    main()
