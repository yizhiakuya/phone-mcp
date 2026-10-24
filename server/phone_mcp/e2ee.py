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
* **AAD 绑定设备与路径**：把 device_id + path 放进附加认证数据，
  防止中继把给 A 设备的指令重放到 B 设备、或改路径（如把 /screenshot 改成 /click）。
"""

from __future__ import annotations

import base64
import json
import os
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

NONCE_BYTES = 12
KEY_BYTES = 32


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


def _aad(device_id: str, path: str) -> bytes:
    """附加认证数据：绑定设备与路径，防重放/改路径。"""
    return f"{device_id}\n{path}".encode("utf-8")


def seal(key: str, device_id: str, path: str, payload: dict[str, Any]) -> str:
    """加密一个请求体，返回 base64url 字符串。"""
    aes = AESGCM(_decode_key(key))
    nonce = os.urandom(NONCE_BYTES)
    plaintext = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    ct = aes.encrypt(nonce, plaintext, _aad(device_id, path))
    return base64.urlsafe_b64encode(nonce + ct).decode().rstrip("=")


def open_sealed(key: str, device_id: str, path: str, blob: str) -> dict[str, Any]:
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
        plaintext = aes.decrypt(nonce, ct, _aad(device_id, path))
    except Exception:
        # 密钥不对、密文被篡改、AAD 不匹配都走这里。
        # 不区分具体原因，避免给攻击者反馈。
        raise E2EError("解密失败：密钥不匹配或数据被篡改") from None
    try:
        data = json.loads(plaintext)
    except json.JSONDecodeError:
        raise E2EError("明文不是合法 JSON") from None
    if not isinstance(data, dict):
        raise E2EError("明文必须是 JSON 对象")
    return data


def seal_bytes(key: str, device_id: str, path: str, payload: bytes) -> str:
    """加密二进制（截图）。"""
    aes = AESGCM(_decode_key(key))
    nonce = os.urandom(NONCE_BYTES)
    ct = aes.encrypt(nonce, payload, _aad(device_id, path))
    return base64.urlsafe_b64encode(nonce + ct).decode().rstrip("=")


def open_sealed_bytes(key: str, device_id: str, path: str, blob: str) -> bytes:
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
        return aes.decrypt(nonce, ct, _aad(device_id, path))
    except Exception:
        raise E2EError("解密失败：密钥不匹配或数据被篡改") from None
