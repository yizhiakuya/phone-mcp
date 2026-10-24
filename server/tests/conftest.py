import os

import pytest


@pytest.fixture(autouse=True)
def isolated_environment(monkeypatch):
    """本地假服务测试不继承真实手机配置或系统代理。"""
    for key in list(os.environ):
        if key.startswith("PHONE_MCP_") or key.lower().endswith("_proxy"):
            monkeypatch.delenv(key)
    monkeypatch.setenv("NO_PROXY", "*")


@pytest.fixture(autouse=True)
def clean_auth_failures():
    """鉴权失败计数是进程级全局状态，用例之间必须隔离。

    否则前一个用例故意制造的失败会把后一个用例锁住——
    这不是测试瑕疵，正好说明锁定是全局生效的。
    """
    try:
        import relay_pkg.relay as relay
    except ImportError:
        yield
        return
    relay._AUTH_FAILURES.clear()
    yield
    relay._AUTH_FAILURES.clear()


@pytest.fixture()
def e2e_key() -> str:
    """测试用固定端到端密钥。真实部署里它由配对时随机生成。"""
    from phone_mcp.e2ee import generate_key
    return generate_key()


def relay_config(url: str, e2e_key: str, *, token: str = "t", device_id: str = "phone-test"):
    """构造一个已配好端到端密钥的中继配置。"""
    from phone_mcp.config import Config
    return Config(relay_url=url, relay_token=token, device_id=device_id, e2e_key=e2e_key)
