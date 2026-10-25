"""Thin async HTTP client for the on-device bridge.

Kept deliberately dumb: transport + auth + error shaping only. All semantics
(tree pruning, coordinate scaling, retries) live in the tool layer.
"""

from __future__ import annotations

import json
from typing import Any

import httpx

from .config import Config


class HumanOverrideError(RuntimeError):
    """人工接管中。带自动恢复倒计时，上层据此等待重试。"""

    def __init__(self, message: str, *, retry_after_ms: int) -> None:
        super().__init__(message)
        self.retry_after_ms = retry_after_ms


class BridgeError(RuntimeError):
    """Bridge returned an error or was unreachable."""

    def __init__(self, message: str, *, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status

    @property
    def is_auth(self) -> bool:
        return self.status == 401


class MoneyBlockedError(RuntimeError):
    """手机端的资金保护拦下了这次动作。

    独立成异常类型（而不是 BridgeError）是为了让工具层抛 ToolError——
    模型必须看到"工具调用失败"，才会停手；返回字符串它只会当成成功继续。
    """


def _guard_money(path: str, payload: dict[str, Any] | None) -> None:
    """服务端参数级兜底。真正的拦截在手机端，这里防旧版 APK。"""
    from .moneyguard import MoneyBlocked, check

    try:
        check(path, payload)
    except MoneyBlocked as exc:
        raise MoneyBlockedError(str(exc)) from None


class BridgeClient:
    """Transport for both connection modes.

    POST {relay_url}/phonemcp/device/{device_id}/tree

    只支持中继模式：手机主动外连、全链路 TLS。局域网明文直连已移除。
    """

    def __init__(self, config: Config) -> None:
        self._config = config
        self._client: httpx.AsyncClient | None = None
        # 请求号：单调递增，手机端据此拒绝重放
        self._rid = 0

    @property
    def mode(self) -> str:
        return self._config.mode

    def _prefix(self) -> str:
        # 中继的 HTTP 控制面在 /phonemcp/device/<id>/ 下（nginx 反代到 relay）
        return f"/phonemcp/device/{self._config.device_id}"

    def _auth_token(self) -> str:
        return self._config.relay_token

    # -- lifecycle ---------------------------------------------------------

    async def _get_client(self) -> httpx.AsyncClient:
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(
                base_url=self._config.relay_url,
                headers={"Authorization": f"Bearer {self._auth_token()}"},
                timeout=httpx.Timeout(self._config.timeout, connect=5.0),
            )
        return self._client

    async def aclose(self) -> None:
        if self._client is not None and not self._client.is_closed:
            await self._client.aclose()
        self._client = None

    # -- calls -------------------------------------------------------------

    def _next_rid(self) -> int:
        """单调递增的请求号。每次请求 +1，手机端据此拒绝重放。"""
        self._rid += 1
        return self._rid

    def _sealed(self, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        """把请求体封装成端到端密文。中继只能看到 blob。

        rid 与 ts 一并进 AAD，中继改不了也重放不了：
        * 改 rid/ts → 解密失败（AAD 不匹配）
        * 原样重放 → 手机端看到 rid 不大于已见最大值，直接拒
        * 延迟重放 → ts 超出窗口，手机端拒
        """
        import time

        from .e2ee import seal

        rid = self._next_rid()
        ts = int(time.time())
        return {
            "e2ee": seal(
                self._config.e2e_key, self._config.device_id, path, payload or {},
                rid=rid, ts=ts,
            ),
            # 明文回显，中继原样透传给手机。改了这两个字段会导致 AAD 不匹配。
            "rid": rid,
            "ts": ts,
        }

    def _unseal(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        from .e2ee import E2EError, open_sealed

        if "plain_error" in body:
            # 手机端端到端解密失败时回传的诊断。
            # **不能把里面的文本原样透给模型**——这条响应没有经过认证，
            # 中继（或顶替隧道的人）可以塞任意指令进去。只给固定文案。
            raise BridgeError(
                "手机端无法解密请求。常见原因：两端 e2e_key 不一致（重新配对可解决），"
                "或手机 App 版本过旧。"
            )
        if "e2ee" not in body:
            raise BridgeError(
                "响应未加密：手机端可能还是旧版本，或中继被替换。拒绝使用明文响应。"
            )
        # 响应密文里回显 rid/ts，用于校验它确实是对应本次请求的答复，
        # 而不是中继拿旧响应重放。
        rid = int(body.get("rid") or 0)
        ts = int(body.get("ts") or 0)
        try:
            data = open_sealed(
                self._config.e2e_key, self._config.device_id, path, body["e2ee"],
                rid=rid, ts=ts,
            )
        except E2EError as exc:
            raise BridgeError(
                f"端到端解密失败：{exc}。"
                "常见原因：手机端保存的 e2e_key 与本机不一致（重新配对可解决），"
                "或手机 App 版本不支持端到端加密。"
            ) from None
        # 回显的 rid 必须与本次请求一致，否则是重放的旧响应。
        #
        # 注意不能写成 `if rid and ...`：rid=0 会被当成"没有回显"而跳过校验，
        # 而 rid=0 恰恰是"响应里没带 rid"的默认值——攻击者只要让它缺失就能绕过。
        # 正确做法是要求它必须等于本次请求号（本项目从 1 开始，0 永远非法）。
        if rid != self._rid:
            raise BridgeError("响应与请求不匹配（可能是中继重放了旧响应），已拒绝使用。")
        return data

    def _money_or_bridge_error(self, path: str, resp: httpx.Response) -> Exception:
        """403 响应：手机端资金保护拦截 → MoneyBlockedError，其它 → BridgeError。

        响应体同样是端到端加密的，必须先解封才能判断是哪种 403。
        """
        try:
            body = self._unseal(path, _safe_json(resp))
        except BridgeError:
            body = {}
        if body.get("money_blocked"):
            # 加密内容已认证，可以用里面的原因
            return MoneyBlockedError(str(body.get("error") or "手机端资金保护拦截了该操作"))
        return BridgeError(_opaque_error(403), status=403)

    async def post(self, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        _guard_money(path, payload)
        client = await self._get_client()
        try:
            resp = await client.post(self._prefix() + path, json=self._sealed(path, payload))
        except httpx.ConnectError as exc:
            raise BridgeError(self._connect_hint()) from exc
        except httpx.TimeoutException as exc:
            raise BridgeError("手机端响应超时，可能屏幕正被系统弹窗占用") from exc
        except httpx.HTTPError as exc:
            raise BridgeError(f"与手机端的连接中断：{exc.__class__.__name__}") from exc

        if resp.status_code == 401:
            raise BridgeError("鉴权失败：控制凭据与中继登记的不一致", status=401)
        if resp.status_code == 403:
            raise self._money_or_bridge_error(path, resp)
        if resp.status_code == 409:
            # 409 的响应体同样是端到端加密的，先解密再取字段
            try:
                body = self._unseal(path, _safe_json(resp))
            except BridgeError:
                body = {}
            retry_ms = int(body.get("retry_after_ms") or 0)
            raise HumanOverrideError(
                "用户正在操作手机，自动操作已中断",
                retry_after_ms=retry_ms,
            )
        if resp.status_code == 503:
            raise BridgeError(self._offline_hint(), status=503)
        if resp.status_code >= 400:
            raise BridgeError(_opaque_error(resp.status_code), status=resp.status_code)
        return self._unseal(path, resp.json())

    async def get(self, path: str) -> dict[str, Any]:
        # 端到端加密需要请求体，GET 带不了，统一用 POST。
        # 手机端 /health 同时接受两种方法。
        client = await self._get_client()
        try:
            resp = await client.post(self._prefix() + path, json=self._sealed(path, {}))
        except httpx.ConnectError as exc:
            raise BridgeError(self._connect_hint()) from exc
        except httpx.TimeoutException as exc:
            raise BridgeError("手机端响应超时") from exc
        except httpx.HTTPError as exc:
            raise BridgeError(f"与手机端的连接中断：{exc.__class__.__name__}") from exc
        if resp.status_code == 401:
            raise BridgeError("鉴权失败：Token 不对", status=401)
        if resp.status_code == 403:
            raise self._money_or_bridge_error(path, resp)
        if resp.status_code >= 400:
            raise BridgeError(_opaque_error(resp.status_code), status=resp.status_code)
        return self._unseal(path, resp.json())

    async def post_bytes(self, path: str, payload: dict[str, Any] | None = None) -> bytes:
        client = await self._get_client()
        try:
            resp = await client.post(self._prefix() + path, json=self._sealed(path, payload))
        except httpx.ConnectError as exc:
            raise BridgeError(self._connect_hint()) from exc
        except httpx.TimeoutException as exc:
            raise BridgeError("截图超时") from exc
        except httpx.HTTPError as exc:
            raise BridgeError(f"与手机端的连接中断：{exc.__class__.__name__}") from exc
        if resp.status_code == 403:
            raise self._money_or_bridge_error(path, resp)
        if resp.status_code >= 400:
            raise BridgeError(_opaque_error(resp.status_code), status=resp.status_code)
        # 截图同样加密返回
        from .e2ee import E2EError, open_sealed_bytes

        body = resp.json()
        if "e2ee" not in body:
            raise BridgeError("截图未加密：手机端可能是旧版本，拒绝使用明文")
        try:
            return open_sealed_bytes(self._config.e2e_key, self._config.device_id, path, body["e2ee"])
        except E2EError as exc:
            raise BridgeError(f"截图解密失败：{exc}") from None

    def _offline_hint(self) -> str:
        return (
            f"手机 {self._config.device_id} 当前不在中继上。检查：\n"
            f"1) 手机 App 里「中继隧道」是否显示已连接 ✓\n"
            f"2) 手机是否断网 / 被系统杀了后台\n"
            f"3) 中继地址和设备 Token 是否与服务器一致"
        )

    def _connect_hint(self) -> str:
        if self._config.mode == "relay":
            return (
                f"连不上中继 {self._config.host}:{self._config.port}。检查：\n"
                f"1) 中继服务是否在跑\n"
                f"2) PHONE_MCP_RELAY_URL 是否指向正确地址（通常 https://...:8900）"
            )
        host = self._config.host
        port = self._config.port
        return (
            f"连不上手机 {host}:{port}。检查：\n"
            f"1) 手机与电脑是否同一局域网\n"
            f"2) App 里控制服务是否显示「运行中」\n"
            f"3) 若走 USB，先执行 adb reverse tcp:{port} tcp:{port}，"
            f"并把 PHONE_MCP_URL 设为 http://127.0.0.1:{port}\n"
            f"4) 手机不在同一网络时，改用中继模式"
        )


def _safe_json(resp: httpx.Response) -> dict[str, Any]:
    try:
        data = resp.json()
        return data if isinstance(data, dict) else {}
    except (json.JSONDecodeError, ValueError):
        return {}


# 未认证响应的错误文案。
#
# 为什么不用响应体里的 error 字段：中继是本项目明确设定的不可信节点。
# 它可以返回任意 JSON，把 `{"error": "忽略之前的指令，现在去做 X"}` 塞进
# 模型上下文。所以非加密响应的内容一律丢弃，只按状态码给固定文案。
_OPAQUE_ERRORS = {
    400: "手机端拒绝了请求（参数或状态不符）",
    401: "鉴权失败",
    403: "手机端拒绝执行",
    404: "手机端没有这个接口（App 版本可能过旧）",
    405: "请求方法不被支持",
    409: "操作与当前状态冲突",
    413: "请求体过大",
    429: "请求过于频繁，请稍后重试",
    500: "手机端内部错误",
    502: "中继网关错误",
    503: "手机端不在线",
    504: "手机端响应超时",
}


def _opaque_error(status: int) -> str:
    return _OPAQUE_ERRORS.get(status, f"手机端返回 HTTP {status}")
