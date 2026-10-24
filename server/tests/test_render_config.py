"""Tests for tree rendering, config parsing, and the bridge error paths."""

from __future__ import annotations

import pytest

from phone_mcp.config import ConfigError, load_config
from phone_mcp.render import render_tree

SAMPLE = {
    "screen": {"w": 1080, "h": 2400},
    "package": "com.example.app",
    "window": "Main",
    "node_count": 4,
    "snapshot": 7,
    "root": {
        "i": "n0",
        "c": "FrameLayout",
        "b": [0, 0, 1080, 2400],
        "ch": [
            {
                "i": "n1",
                "c": "TextView",
                "t": "欢迎回来",
                "b": [40, 200, 600, 260],
            },
            {
                "i": "n2",
                "c": "Button",
                "t": "登录",
                "v": "com.example.app:id/btn_login",
                "b": [40, 400, 1040, 520],
                "f": ["click", "focused"],
            },
            {
                "i": "n3",
                "c": "EditText",
                "d": "密码",
                "b": [40, 600, 1040, 720],
                "f": ["edit", "password"],
            },
        ],
    },
}


def test_render_includes_header_and_nodes():
    out = render_tree(SAMPLE)
    assert "1080x2400" in out
    assert "com.example.app" in out
    assert "快照 v7" in out
    assert "[n2] Button" in out
    assert "btn_login" in out
    assert "click" in out
    assert "password" in out


def test_render_interactive_only_filters():
    out = render_tree(SAMPLE, interactive_only=True)
    assert "[n2]" in out
    assert "[n3]" in out
    assert "[n1]" not in out


def test_render_contains_filter():
    out = render_tree(SAMPLE, contains="登录")
    assert "[n2]" in out
    assert "[n3]" not in out


def test_render_contains_no_match():
    out = render_tree(SAMPLE, contains="不存在的文字")
    assert "没有匹配的节点" in out


def test_render_max_lines_truncates():
    out = render_tree(SAMPLE, max_lines=2)
    assert "已截断" in out


def test_render_empty():
    assert "空界面" in render_tree({"root": None})


def test_render_windows():
    payload = dict(SAMPLE)
    payload["windows"] = [
        {"type": "application", "package": "com.example.app", "title": "Main", "active": True},
        {"type": "ime", "package": "com.google.android.inputmethod", "title": "", "active": False},
    ]
    out = render_tree(payload)
    assert "窗口" in out
    assert "* [application]" in out
    assert "[ime]" in out


def test_config_requires_url(monkeypatch):
    monkeypatch.delenv("PHONE_MCP_URL", raising=False)
    monkeypatch.setenv("PHONE_MCP_TOKEN", "t")
    with pytest.raises(ConfigError, match="PHONE_MCP_URL"):
        load_config()


def test_config_requires_token(monkeypatch):
    monkeypatch.setenv("PHONE_MCP_URL", "http://1.2.3.4:8765")
    monkeypatch.delenv("PHONE_MCP_TOKEN", raising=False)
    with pytest.raises(ConfigError, match="PHONE_MCP_TOKEN"):
        load_config()


def test_config_normalizes_scheme(monkeypatch):
    monkeypatch.setenv("PHONE_MCP_URL", "192.168.1.9:8765/")
    monkeypatch.setenv("PHONE_MCP_TOKEN", "abc")
    cfg = load_config()
    assert cfg.base_url == "http://192.168.1.9:8765"
    assert cfg.host == "192.168.1.9"
    assert cfg.port == 8765


def test_config_default_port(monkeypatch):
    monkeypatch.setenv("PHONE_MCP_URL", "http://phone.local")
    monkeypatch.setenv("PHONE_MCP_TOKEN", "abc")
    assert load_config().port == 80


# ---- text/desc 互相兜底 ----

def test_find_all_text_falls_back_to_desc():
    """调用方传 text 但目标其实在 desc 字段时，仍应找到。"""
    from phone_mcp.server import _find_all
    tree = {
        "i": "n0", "c": "Frame",
        "ch": [{"i": "n1", "c": "ImageButton", "d": "搜索", "b": [0, 0, 10, 10], "f": ["click"]}],
    }
    got = _find_all(tree, text="搜索", desc=None, view_id=None, clickable_only=False)
    assert len(got) == 1
    assert got[0]["i"] == "n1"


def test_find_all_desc_falls_back_to_text():
    """调用方传 desc 但目标其实在 text 字段时，仍应找到。"""
    from phone_mcp.server import _find_all
    tree = {
        "i": "n0", "c": "Frame",
        "ch": [{"i": "n1", "c": "EditText", "t": "搜索应用", "b": [0, 0, 10, 10], "f": ["edit"]}],
    }
    got = _find_all(tree, text=None, desc="搜索应用", view_id=None, clickable_only=False)
    assert len(got) == 1
    assert got[0]["i"] == "n1"


def test_find_all_no_false_positive():
    """兜底不能让不相关的节点也被匹配上。"""
    from phone_mcp.server import _find_all
    tree = {
        "i": "n0", "c": "Frame",
        "ch": [
            {"i": "n1", "c": "TextView", "t": "设置", "b": [0, 0, 10, 10]},
            {"i": "n2", "c": "TextView", "d": "搜索", "b": [0, 10, 10, 20]},
        ],
    }
    got = _find_all(tree, text="搜索", desc=None, view_id=None, clickable_only=False)
    assert [n["i"] for n in got] == ["n2"]


def test_find_all_clickable_only():
    from phone_mcp.server import _find_all
    tree = {
        "i": "n0", "c": "Frame",
        "ch": [
            {"i": "n1", "c": "TextView", "t": "确定", "b": [0, 0, 10, 10]},
            {"i": "n2", "c": "Button", "t": "确定", "b": [0, 10, 10, 20], "f": ["click"]},
        ],
    }
    got = _find_all(tree, text="确定", desc=None, view_id=None, clickable_only=True)
    assert [n["i"] for n in got] == ["n2"]
