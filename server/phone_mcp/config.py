"""Configuration for the phone bridge connection.

Two connection modes, chosen by which env vars are present:

  relay mode   PHONE_MCP_RELAY_URL + PHONE_MCP_RELAY_TOKEN + PHONE_MCP_DEVICE_ID
               The phone dials out to a relay on a public server, so it works
               on 4G / any network. Preferred.
  direct mode  PHONE_MCP_URL + PHONE_MCP_TOKEN
               Plain LAN HTTP to the phone's own control server.

Relay mode wins when both are configured.
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit


class ConfigError(RuntimeError):
    """Raised when required configuration is missing or malformed."""


@dataclass(frozen=True)
class Config:
    # direct mode
    base_url: str = ""
    token: str = field(default="", repr=False)
    # relay mode
    relay_url: str = ""
    relay_token: str = field(default="", repr=False)
    device_id: str = ""
    e2e_key: str = field(default="", repr=False)
    timeout: float = 30.0

    @property
    def mode(self) -> str:
        return "relay" if self.relay_url else "direct"

    @property
    def host(self) -> str:
        return _host_of(self.base_url or self.relay_url)

    @property
    def port(self) -> int:
        return _port_of(self.base_url or self.relay_url)


def _host_of(url: str) -> str:
    without_scheme = url.split("://", 1)[-1]
    host_part = without_scheme.split("/", 1)[0]
    return host_part.rsplit(":", 1)[0] if ":" in host_part else host_part


def _port_of(url: str) -> int:
    without_scheme = url.split("://", 1)[-1]
    host_part = without_scheme.split("/", 1)[0]
    if ":" not in host_part:
        return 443 if url.startswith("https") else 80
    return int(host_part.rsplit(":", 1)[1])


def _normalize_url(raw: str, *, relay: bool = False) -> str:
    raw = raw.strip().rstrip("/")
    if not raw:
        raise ConfigError("URL 为空")
    if "://" not in raw:
        raw = ("https://" if relay else "http://") + raw
    try:
        parsed = urlsplit(raw)
        if parsed.port == 0:
            raise ValueError()
        valid = parsed.hostname and parsed.scheme in ("http", "https")
    except ValueError:
        raise ConfigError("连接地址无效") from None
    if not valid or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment:
        raise ConfigError("连接地址必须是 HTTP(S) 地址，且不能包含凭据、查询参数或片段")
    if relay and parsed.path not in ("", "/"):
        raise ConfigError("中继地址不能带路径，例如 https://host:8900")
    if relay and parsed.scheme != "https":
        # 只放行本机回环：测试用的假中继跑在 127.0.0.1，没有 TLS。
        # 任何非本机地址仍然强制 HTTPS，避免误连到明文入口。
        if parsed.hostname not in ("127.0.0.1", "localhost", "::1"):
            raise ConfigError("中继地址必须是 HTTPS 源地址，例如 https://host:8900")
    return raw


def _token(name: str) -> str:
    path = os.environ.get(name + "_FILE", "").strip()
    if path:
        try:
            return Path(path).read_text(encoding="utf-8").strip()
        except OSError:
            raise ConfigError(f"无法读取 {name}_FILE 指定的凭据文件") from None
    return os.environ.get(name, "").strip()


def load_config() -> Config:
    timeout = float(os.environ.get("PHONE_MCP_TIMEOUT", "30"))

    relay_url = os.environ.get("PHONE_MCP_RELAY_URL", "").strip()
    if relay_url:
        relay_token = _token("PHONE_MCP_RELAY_TOKEN")
        device_id = os.environ.get("PHONE_MCP_DEVICE_ID", "").strip()
        if not relay_token:
            raise ConfigError(
                "缺少 PHONE_MCP_RELAY_TOKEN（中继的控制 token，不是手机的设备 token）"
            )
        if not device_id:
            raise ConfigError(
                "缺少 PHONE_MCP_DEVICE_ID（手机 App 首页显示的设备 ID，如 phone-a1b2c3）"
            )
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", device_id):
            raise ConfigError("PHONE_MCP_DEVICE_ID 格式无效")
        e2e_key = _token("PHONE_MCP_E2E_KEY")
        if not e2e_key:
            raise ConfigError(
                "缺少 PHONE_MCP_E2E_KEY 或 PHONE_MCP_E2E_KEY_FILE。"
                "端到端密钥在配对时生成，写在 .secrets/pairing-*/e2e.key，"
                "对应的 mcp-env.json 里已经包含该路径。"
            )
        return Config(
            relay_url=_normalize_url(relay_url, relay=True),
            relay_token=relay_token,
            device_id=device_id,
            e2e_key=e2e_key,
            timeout=timeout,
        )

    url = os.environ.get("PHONE_MCP_URL", "").strip()
    token = _token("PHONE_MCP_TOKEN")
    if not url:
        raise ConfigError(
            "未配置连接方式。二选一：\n"
            "  中继：PHONE_MCP_RELAY_URL + PHONE_MCP_RELAY_TOKEN + PHONE_MCP_DEVICE_ID\n"
            "  直连：PHONE_MCP_URL + PHONE_MCP_TOKEN"
        )
    if not token:
        raise ConfigError("缺少 PHONE_MCP_TOKEN。请从手机 App 首页复制")
    return Config(base_url=_normalize_url(url), token=token, timeout=timeout)
