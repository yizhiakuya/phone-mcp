"""端到端加密：中继只转发密文，看不到指令与截图。

密钥分发
--------
配对二维码里带一把 256 位随机密钥（`e2e_key`）。手机扫码后保存，电脑端在
生成二维码时也保存同一把。这把密钥**从不发给中继**，中继也没有任何途径获得它。

于是：

    电脑 ──[AES-GCM 密文]──► 中继 ──[原样转发]──► 手机
                              ↑
                        只有密文，无密钥

密码学选择
----------
* **AES-256-GCM**：AEAD，机密性 + 完整性一体。中继若篡改任何字节，接收方解密失败。
* **每次请求随机 nonce**：GCM 的 nonce 绝不能重用，否则会泄露明文异或值。
  这里用 12 字节随机数，碰撞概率在 2^-96 量级，实际不可达。
* **AAD 绑定设备、路径、请求 id 与时间戳**：
  - device_id —— 防止把给 A 设备的指令重放到 B 设备
  - path —— 防止改路径（如把 /screenshot 改成 /click）
  - rid —— 单调递增请求号，接收方拒绝旧值，防重放
  - ts —— Unix 秒，接收方拒绝窗口外的请求，防延迟重放

为什么需要 rid/ts
-----------------
只绑定 device_id + path 时，中继（本项目明确设定的**不可信节点**）可以把抓到的
任意一条 `/click` 密文原样再发一次。手机端解密会成功，因为它确实是合法密文——
AAD 完全匹配。结果是同一次点击被无限重放。

加了 rid/ts 之后：
* rid 单调递增，手机端记住见过的最大值，旧请求直接拒
* ts 只在 ±[REPLAY_WINDOW_SEC] 内有效，过期密文即使 rid 更大也拒

重放响应同样有害（模型看到过期的界面树），所以响应密文里也回显 rid，
电脑端校验是否与请求匹配。
"""

from __future__ import annotations

import base64
import json
import os
import time
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

NONCE_BYTES = 12
KEY_BYTES = 32

# 请求时间戳的有效窗口（秒）。手机与电脑的时钟可能有偏差，给足余量。
REPLAY_WINDOW_SEC = 120


class E2EError(RuntimeError):
    """解密失败：密钥不对，或密文被篡改/重放。"""


def generate_key() -> str:
    """生成 32 字节随机密钥，base64url 无填充。"""
    return base64.urlsafe_b64encode(os.urandom(KEY_BYTES)).decode().rstrip("=")


def _decode_key(key: str) -> bytes:
    padded = key + "=" * (-len(key) % 4)
    try:
        raw = base64.urlsafe_b64decode(padded)
    except Exception:
        raise E2EError("端到端密钥格式无效") from None
    if len(raw) != KEY_BYTES:
        raise E2EError("端到端密钥长度不对")
    return raw


def _aad(device_id: str, path: str, rid: int = 0, ts: int = 0) -> bytes:
    """附加认证数据：绑定设备、路径、请求号与时间戳。

    rid/ts 为 0 时保持向后兼容（老版本手机端不带这两个字段），
    但服务端默认总是带上——见 bridge.py。
    """
    return f"{device_id}\n{path}\n{rid}\n{ts}".encode("utf-8")


def seal(
    key: str,
    device_id: str,
    path: str,
    payload: dict[str, Any],
    *,
    rid: int = 0,
    ts: int | None = None,
) -> str:
    """加密一个请求体，返回 base64url 字符串。"""
    aes = AESGCM(_decode_key(key))
    nonce = os.urandom(NONCE_BYTES)
    # ts 默认 0（不绑定时间），由 bridge 层显式传当前时间。
    # 这样 seal/open 的默认参数对称，底层加解密本身不关心时钟。
    ts = 0 if ts is None else int(ts)
    plaintext = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ct = aes.encrypt(nonce, plaintext, _aad(device_id, path, rid, ts))
    return base64.urlsafe_b64encode(nonce + ct).decode().rstrip("=")


def open_sealed(
    key: str,
    device_id: str,
    path: str,
    blob: str,
    *,
    rid: int = 0,
    ts: int = 0,
) -> dict[str, Any]:
    """解密请求体。失败抛 E2EError。"""
    padded = blob + "=" * (-len(blob) % 4)
    try:
        raw = base64.urlsafe_b64decode(padded)
    except Exception:
        raise E2EError("密文格式无效") from None
    if len(raw) <= NONCE_BYTES:
        raise E2EError("密文长度不足")

    nonce, ct = raw[:NONCE_BYTES], raw[NONCE_BYTES:]
    aes = AESGCM(_decode_key(key))
    try:
        plaintext = aes.decrypt(nonce, ct, _aad(device_id, path, rid, ts))
    except Exception:
        # 密钥不对、密文被篡改、AAD 不匹配（含 rid/ts 被改）都走这里。
        # 不区分具体原因，避免给攻击者反馈。
        raise E2EError("解密失败：密钥不匹配或数据被篡改") from None
    try:
        data = json.loads(plaintext)
    except json.JSONDecodeError:
        raise E2EError("明文不是合法 JSON") from None
    if not isinstance(data, dict):
        raise E2EError("明文必须是 JSON 对象")
    return data


def seal_bytes(
    key: str,
    device_id: str,
    path: str,
    payload: bytes,
    *,
    rid: int = 0,
    ts: int | None = None,
) -> str:
    """加密二进制（截图）。"""
    aes = AESGCM(_decode_key(key))
    nonce = os.urandom(NONCE_BYTES)
    ts = 0 if ts is None else int(ts)
    ct = aes.encrypt(nonce, payload, _aad(device_id, path, rid, ts))
    return base64.urlsafe_b64encode(nonce + ct).decode().rstrip("=")


def open_sealed_bytes(
    key: str,
    device_id: str,
    path: str,
    blob: str,
    *,
    rid: int = 0,
    ts: int = 0,
) -> bytes:
    padded = blob + "=" * (-len(blob) % 4)
    try:
        raw = base64.urlsafe_b64decode(padded)
    except Exception:
        raise E2EError("密文格式无效") from None
    if len(raw) <= NONCE_BYTES:
        raise E2EError("密文长度不足")
    nonce, ct = raw[:NONCE_BYTES], raw[NONCE_BYTES:]
    aes = AESGCM(_decode_key(key))
    try:
        return aes.decrypt(nonce, ct, _aad(device_id, path, rid, ts))
    except Exception:
        raise E2EError("解密失败：密钥不匹配或数据被篡改") from None


def is_fresh(ts: int, now: int | None = None) -> bool:
    """时间戳是否在有效窗口内。"""
    now = int(time.time()) if now is None else int(now)
    return abs(now - int(ts)) <= REPLAY_WINDOW_SEC
