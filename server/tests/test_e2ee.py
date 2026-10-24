"""端到端加密：正确性 + 防篡改 + 防重放。"""

from __future__ import annotations

import pytest

from phone_mcp import e2ee

DEV = "phone-abc123"
PATH = "/tree"


def test_roundtrip():
    key = e2ee.generate_key()
    payload = {"max_depth": 30, "text": "中文内容"}
    sealed = e2ee.seal(key, DEV, PATH, payload)
    assert e2ee.open_sealed(key, DEV, PATH, sealed) == payload


def test_roundtrip_binary():
    key = e2ee.generate_key()
    data = b"\xff\xd8\xff\xe0" + b"x" * 5000
    sealed = e2ee.seal_bytes(key, DEV, PATH, data)
    assert e2ee.open_sealed_bytes(key, DEV, PATH, sealed) == data


def test_wrong_key_rejected():
    sealed = e2ee.seal(e2ee.generate_key(), DEV, PATH, {"a": 1})
    with pytest.raises(e2ee.E2EError, match="解密失败"):
        e2ee.open_sealed(e2ee.generate_key(), DEV, PATH, sealed)


def test_tampered_ciphertext_rejected():
    key = e2ee.generate_key()
    sealed = e2ee.seal(key, DEV, PATH, {"a": 1})
    # 改中间一个字符（保持 base64 长度不变）
    mid = len(sealed) // 2
    flipped = "B" if sealed[mid] != "B" else "C"
    tampered = sealed[:mid] + flipped + sealed[mid + 1:]
    with pytest.raises(e2ee.E2EError):
        e2ee.open_sealed(key, DEV, PATH, tampered)


def test_replay_to_other_device_rejected():
    """把给 A 设备的密文重放到 B 设备必须失败（AAD 绑定 device_id）。"""
    key = e2ee.generate_key()
    sealed = e2ee.seal(key, DEV, PATH, {"cmd": "click"})
    with pytest.raises(e2ee.E2EError):
        e2ee.open_sealed(key, "phone-other", PATH, sealed)


def test_path_swap_rejected():
    """把 /screenshot 的密文当 /click 用必须失败（AAD 绑定 path）。"""
    key = e2ee.generate_key()
    sealed = e2ee.seal(key, DEV, "/screenshot", {"max_width": 900})
    with pytest.raises(e2ee.E2EError):
        e2ee.open_sealed(key, DEV, "/click", sealed)


def test_nonce_never_reused():
    """同样明文两次加密，密文必须不同（随机 nonce）。"""
    key = e2ee.generate_key()
    a = e2ee.seal(key, DEV, PATH, {"same": True})
    b = e2ee.seal(key, DEV, PATH, {"same": True})
    assert a != b


def test_key_format_validation():
    with pytest.raises(e2ee.E2EError):
        e2ee.open_sealed("short", DEV, PATH, "AAAA")
    with pytest.raises(e2ee.E2EError):
        e2ee.seal("not-a-valid-key", DEV, PATH, {})


def test_generated_key_is_256_bit():
    import base64
    key = e2ee.generate_key()
    raw = base64.urlsafe_b64decode(key + "=" * (-len(key) % 4))
    assert len(raw) == 32
