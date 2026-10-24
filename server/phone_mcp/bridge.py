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

    def _sealed(self, path: str, payload: dict[str, Any] | None) -> dict[str, Any]:
        """把请求体封装成端到端密文。中继只能看到 blob。"""
        from .e2ee import seal

        return {
            "e2ee": seal(self._config.e2e_key, self._config.device_id, path, payload or {}),
        }

    def _unseal(self, path: str, body: dict[str, Any]) -> dict[str, Any]:
        from .e2ee import E2EError, open_sealed

        if "plain_error" in body:
            # 手机端端到端解密失败时回传的诊断（不含敏感数据）
            raise BridgeError(f"手机端解密失败：{body['plain_error'].get('error', '')}")
        if "e2ee" not in body:
            raise BridgeError(
                "响应未加密：手机端可能还是旧版本，或中继被替换。拒绝使用明文响应。"
            )
        try:
            return open_sealed(self._config.e2e_key, self._config.device_id, path, body["e2ee"])
        except E2EError as exc:
            raise BridgeError(
                f"端到端解密失败：{exc}。"
                "常见原因：手机端保存的 e2e_key 与本机不一致（重新配对可解决），"
                "或手机 App 版本不支持端到端加密。"
            ) from None

    def _money_or_bridge_error(self, path: str, resp: httpx.Response) -> Exception:
        """403 响应：手机端资金保护拦截 → MoneyBlockedError，其它 → BridgeError。

        响应体同样是端到端加密的，必须先解封才能判断是哪种 403。
        """
        try:
            body = self._unseal(path, _safe_json(resp))
        except BridgeError:
            body = {}
        if body.get("money_blocked"):
            return MoneyBlockedError(str(body.get("error") or "手机端资金保护拦截了该操作"))
        return BridgeError(_extract_error(resp), status=403)

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
            raise BridgeError(_extract_error(resp), status=resp.status_code)
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
            raise BridgeError(_extract_error(resp), status=resp.status_code)
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
            raise BridgeError(_extract_error(resp), status=resp.status_code)
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


def _extract_error(resp: httpx.Response) -> str:
    try:
        data = resp.json()
    except (json.JSONDecodeError, ValueError):
        return f"HTTP {resp.status_code}: {resp.text[:200]}"
    if isinstance(data, dict) and data.get("error"):
        return str(data["error"])
    return f"HTTP {resp.status_code}: {json.dumps(data, ensure_ascii=False)[:200]}"
