"""截图黑屏检测：FLAG_SECURE 页面要给出明确提示，而不是让模型对着黑图猜。"""

from __future__ import annotations

import io

from PIL import Image as PILImage

from phone_mcp.server import _looks_black


def _jpeg(color: tuple[int, int, int]) -> bytes:
    img = PILImage.new("RGB", (64, 64), color)
    buf = io.BytesIO()
    img.save(buf, format="JPEG")
    return buf.getvalue()


def test_detects_black_screenshot():
    assert _looks_black(_jpeg((0, 0, 0))) is True


def test_detects_near_black():
    """压缩后纯黑不会是 0，阈值要留余量。"""
    assert _looks_black(_jpeg((3, 3, 3))) is True


def test_normal_screenshot_not_flagged():
    assert _looks_black(_jpeg((240, 240, 240))) is False


def test_colorful_screenshot_not_flagged():
    assert _looks_black(_jpeg((47, 111, 235))) is False


def test_garbage_input_does_not_crash():
    """非图片数据不能让工具崩掉。"""
    assert _looks_black(b"not an image") is False
