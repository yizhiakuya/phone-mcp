"""MCP tool surface.

Design rules that the tools encode:

* Every tool that needs a target accepts either a snapshot `node_id` (from
  `phone_ui`/`phone_find`) or a semantic locator (`text`/`desc`/`view_id`).
  Node ids are stable within one snapshot only, so the phone side relocates
  them by fingerprint and reports clearly when they went stale.
* `phone_observe` returns screenshot + tree together, because acting on a
  screenshot without the tree loses the semantic target, and acting on the
  tree without the screenshot misses canvas/WebView/game content.
* Coordinates are always device pixels in the *native* screen space.
  Screenshots report their own scale so the model can convert back.
"""

from __future__ import annotations

from typing import Annotated, Any

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.server.mcpserver.utilities.types import Image
from pydantic import Field

from .bridge import BridgeError, BridgeClient, HumanOverrideError, MoneyBlockedError
from .config import Config, ConfigError, load_config
from .render import render_tree

# 用户中断时返回给模型的固定信号。模型看到它就应当停止本轮操作。
USER_TOOK_OVER = (
    "⛔ 用户点了中断按钮，自动操作已停止。\n"
    "请立即结束当前任务，不要重试、不要继续操作任何工具，把控制权交给用户。"
)

# 资金保护拦截时返回的固定信号。
MONEY_BLOCKED = (
    "🚫 资金保护拦截：该操作涉及支付/转账/购买等资金行为，已被硬性拒绝。\n"
    "这条限制无法通过任何工具关闭，也不接受换说法绕过。\n"
    "请立刻停止这条任务线，告诉用户需要他本人在手机上完成。"
)

INSTRUCTIONS = """\
这些工具控制一台真实的 Android 手机（通过手机上的无障碍服务）。

工作方式：
1. 先 `phone_observe`（截图 + 无障碍树一次拿到），或只取 `phone_ui`。
2. 用树里的节点 id 或文本/描述做精确操作，比盲点坐标可靠。
3. 截图里才有的内容（游戏、Canvas、图片列表）用 `phone_tap` 按坐标点。
4. 每次操作后重新观察，不要假设界面没变。

**每一步都填 `step`**：一句中文短语，说明这一步在做什么，例如
「打开微信」「在搜索框输入『学超』」「点发送」。它会实时显示在手机底部的
状态条上，用户抬眼就能看到 AI 现在进行到哪一步、在干什么。不填也能用，
但用户只能看到一句笼统的「AI 正在操作」，出问题时无从判断。

`step` 只用于展示，不参与安全判定——写一句好听的描述并不能让被禁止的操作通过。

坐标：所有坐标都是手机原生像素。截图如果被缩放，返回文本里会给出缩放比。

人工中断：AI 操作时屏幕底部会出现一个中断条，用户点一下即中断。
动作类工具随后返回「⛔ 用户点了中断按钮」。看到这个信号就立刻停手，
结束任务，不要重试。

资金保护（硬性限制，无法关闭）：任何可能涉及钱的操作都会被拒绝——
支付类/银行/券商 App 一律不能操作，页面上出现「付款码 / 确认支付 / 提交订单」
这类标记时该页所有改动操作都会被拒，目标节点或其父容器带「支付 / 转账 / 下单」
等字样时也会被拒。命中时工具会抛错并返回「🚫 资金保护拦截」。
看到这个信号就停止这条任务线，告知用户需本人在手机上完成。不要换说法重试，
不要试图用坐标点击绕过。
"""

# 所有工具共用的 step 参数说明。集中一处，改文案不用逐个改。
_STEP_DESC = (
    "这一步在做什么（中文短语，如「打开微信」「点搜索框」「输入关键词」）。"
    "显示在手机底部状态条上，用户能实时看到 AI 的进度。只作展示，不参与安全判定。"
)

server = MCPServer(
    name="phone-mcp",
    title="Android Phone",
    version="1.0.0",
    instructions=INSTRUCTIONS,
)

_client: BridgeClient | None = None
_config: Config | None = None


def _get_config() -> Config:
    global _config
    if _config is None:
        _config = load_config()
    return _config


async def _get_client() -> BridgeClient:
    global _client
    if _client is None:
        _client = BridgeClient(_get_config())
    return _client


def _friendly(exc: Exception) -> str:
    if isinstance(exc, ConfigError):
        return f"配置错误：{exc}"
    if isinstance(exc, BridgeError):
        return f"手机端错误：{exc}"
    return f"出错：{exc}"


def _fail(exc: Exception) -> str:
    """统一的错误出口。

    资金保护必须抛 ToolError——返回文本会被模型当成"这个工具正常返回了"，
    然后它可能换个方式继续。读取类工具（observe/screenshot/ui）同样要抛：
    资金类 App 的界面本身就是敏感数据（余额、卡号、付款码）。
    """
    if isinstance(exc, MoneyBlockedError):
        raise ToolError(f"{MONEY_BLOCKED}\n原因：{exc}") from None
    return _friendly(exc)


def _looks_black(jpeg: bytes) -> bool:
    """截图几乎全黑 —— 多半是 FLAG_SECURE 页面（配置页/银行/支付）。"""
    try:
        import io

        from PIL import Image as PILImage

        with PILImage.open(io.BytesIO(jpeg)) as img:
            small = img.convert("L").resize((32, 32))
            # Pillow 12+ 推荐 tobytes()；getdata() 已弃用
            pixels = small.tobytes()
        return sum(pixels) / len(pixels) < 12
    except Exception:  # noqa: BLE001
        return False


def _with_step(payload: dict[str, Any], step: str | None) -> dict[str, Any]:
    """把步骤说明塞进请求体。手机端拿它更新底部状态条。

    只截断不做转义：手机端用 optString 读，超长只会让状态条难读。
    """
    if step:
        payload = {**payload, "step": step.strip()[:120]}
    return payload


def _scale_note(orig_w: int, out_w: int) -> str:
    if out_w >= orig_w:
        return f"截图 {orig_w}px 宽，与屏幕 1:1，坐标可直接使用。"
    ratio = orig_w / out_w
    return (
        f"截图已缩放到 {out_w}px 宽（屏幕 {orig_w}px，缩放比 {ratio:.2f}）。"
        f"截图坐标 × {ratio:.2f} = 屏幕坐标。"
    )


# ---------------------------------------------------------------- 观察


@server.tool(
    name="phone_observe",
    description=(
        "一次拿到当前屏幕的截图 + 无障碍树。这是最常用的入口：先看再动手。\n"
        "返回两张内容：图片（给视觉判断）和文本树（给精确操作，含节点 id）。"
    ),
)
async def phone_observe(
    max_width: Annotated[int, Field(description="截图最大宽度像素，0=原图。宽屏手机建议 900 省 token", ge=0, le=4000)] = 900,
    max_depth: Annotated[int, Field(description="无障碍树最大深度", ge=1, le=200)] = 25,
    max_lines: Annotated[int, Field(description="树最多显示行数，超出截断", ge=10, le=3000)] = 400,
    interactive_only: Annotated[bool, Field(description="只列出可点击/可输入/可滚动节点，长列表页面用这个")] = False,
    windows: Annotated[bool, Field(description="附带窗口列表（弹窗、输入法、系统窗）")] = False,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> list[Image | str]:
    client = await _get_client()
    try:
        health = await client.get("/health")
        if not health.get("service_connected"):
            return [
                "无障碍服务未连接：请让用户在手机 App 里打开「设置 → 无障碍 → Phone MCP 控制服务」。"
            ]

        shot = await client.post_bytes(
            "/screenshot", _with_step({"max_width": max_width, "quality": 80}, step)
        )
        tree_payload = await client.post(
            "/tree",
            _with_step(
                {"max_depth": max_depth, "include_invisible": False, "windows": windows},
                step,
            ),
        )
        screen = tree_payload.get("screen") or {}
        orig_w = int(screen.get("w") or 0)
        out_w = orig_w if max_width <= 0 else min(orig_w, max_width)
        text = _scale_note(orig_w, out_w) + "\n\n" + render_tree(
            tree_payload,
            interactive_only=interactive_only,
            max_lines=max_lines,
        )
        return [Image(data=shot, format="jpeg"), text]
    except Exception as exc:  # noqa: BLE001 - surfaced to the model as text
        return [_fail(exc)]


@server.tool(
    name="phone_screenshot",
    description="只截屏。需要看纯视觉内容（游戏、图片、视频画面）而树没帮助时用。",
)
async def phone_screenshot(
    max_width: Annotated[int, Field(description="截图最大宽度像素，0=原图", ge=0, le=4000)] = 900,
    quality: Annotated[int, Field(description="JPEG 质量", ge=30, le=100)] = 80,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> list[Image | str]:
    client = await _get_client()
    try:
        info = await client.get("/health")
        screen = info.get("screen") or {}
        orig_w = int(screen.get("w") or 0)
        shot = await client.post_bytes(
            "/screenshot", _with_step({"max_width": max_width, "quality": quality}, step)
        )
        out_w = orig_w if max_width <= 0 else min(orig_w, max_width)
        note = _scale_note(orig_w, out_w)
        if _looks_black(shot):
            note += (
                "\n\n⚠ 截图几乎全黑：当前页面启用了 FLAG_SECURE 防截屏"
                "（Phone MCP 配置页、银行/支付类 App 常见）。"
                "这类页面请改用 phone_ui 读无障碍树。"
            )
        return [Image(data=shot, format="jpeg"), note]
    except Exception as exc:  # noqa: BLE001
        return [_fail(exc)]


@server.tool(
    name="phone_ui",
    description=(
        "只取无障碍树（不截图），省 token。支持按文本过滤和只列可交互节点。\n"
        "返回每行格式：[节点id] 类名 \"文本\" desc=\"描述\" viewId [左,上,右,下] 标记"
    ),
)
async def phone_ui(
    max_depth: Annotated[int, Field(description="最大深度", ge=1, le=200)] = 30,
    max_lines: Annotated[int, Field(description="最多行数", ge=10, le=5000)] = 600,
    interactive_only: Annotated[bool, Field(description="只列可交互节点")] = False,
    contains: Annotated[str | None, Field(description="只列文本/描述/viewId 含该关键字的节点")] = None,
    include_invisible: Annotated[bool, Field(description="包含不可见节点")] = False,
    windows: Annotated[bool, Field(description="附带窗口列表")] = False,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    client = await _get_client()
    try:
        payload = await client.post(
            "/tree",
            _with_step(
                {
                    "max_depth": max_depth,
                    "include_invisible": include_invisible,
                    "windows": windows,
                },
                step,
            ),
        )
        return render_tree(
            payload,
            interactive_only=interactive_only,
            contains=contains,
            max_lines=max_lines,
        )
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)


@server.tool(
    name="phone_find",
    description=(
        "在当前界面按文本/描述/viewId 查找节点，返回所有匹配项的节点 id、坐标和状态。\n"
        "适合「列表里点第 N 个同名按钮」这类场景：先看有几个候选再决定点哪个。"
    ),
)
async def phone_find(
    text: Annotated[str | None, Field(description="节点文本（精确匹配优先）")] = None,
    desc: Annotated[str | None, Field(description="contentDescription")] = None,
    view_id: Annotated[str | None, Field(description="资源 id，可只写后半段如 btn_ok")] = None,
    clickable_only: Annotated[bool, Field(description="只返回可点击的")] = False,
) -> str:
    if not any([text, desc, view_id]):
        return "至少提供 text / desc / view_id 之一。"
    client = await _get_client()
    try:
        payload = await client.post(
            "/tree", {"max_depth": 60, "include_invisible": False}
        )
        matches = _find_all(payload.get("root") or {}, text, desc, view_id, clickable_only)
        if not matches:
            return "没有匹配的节点。可以先用 phone_ui 看看当前界面上有什么。"
        lines = [f"找到 {len(matches)} 个匹配："]
        for m in matches:
            lines.append(
                f"[{m['i']}] {m.get('c', '')} "
                f"{('\"' + m['t'] + '\" ') if m.get('t') else ''}"
                f"{('desc=\"' + m['d'] + '\" ') if m.get('d') else ''}"
                f"{m.get('v', '')} b={m.get('b')} f={','.join(m.get('f') or [])}"
            )
        return "\n".join(lines)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)


def _find_all(
    node: dict[str, Any],
    text: str | None,
    desc: str | None,
    view_id: str | None,
    clickable_only: bool,
    limit: int = 30,
) -> list[dict[str, Any]]:
    """按 text/desc/view_id 子串匹配。

    text 与 contentDescription 在无障碍树里是两个字段，但调用方常常分不清某个
    字符串属于哪个。这里让它们互相兜底：只传了其中一个时，另一个也参与匹配。
    """
    out: list[dict[str, Any]] = []

    def walk(n: dict[str, Any]) -> None:
        if len(out) >= limit:
            return
        flags = set(n.get("f") or [])
        if not (clickable_only and "click" not in flags):
            ok = True
            if text is not None:
                # text 未命中时，允许用 desc 顶替（反之亦然）
                if text not in (n.get("t") or "") and text not in (n.get("d") or ""):
                    ok = False
            if desc is not None:
                if desc not in (n.get("d") or "") and desc not in (n.get("t") or ""):
                    ok = False
            if view_id is not None and view_id not in (n.get("v") or ""):
                ok = False
            if ok and any([text, desc, view_id]):
                out.append(n)
        for child in n.get("ch") or []:
            walk(child)

    walk(node)
    return out


# ---------------------------------------------------------------- 点击类


@server.tool(
    name="phone_click",
    description=(
        "点击。优先用 node_id（最准），其次 text/desc/view_id。\n"
        "目标本身不可点时会自动向上找可点的父节点；整条链路都不可点则用坐标手势兜底。"
    ),
)
async def phone_click(
    node_id: Annotated[str | None, Field(description="phone_ui/phone_observe 返回的节点 id，如 n12")] = None,
    text: Annotated[str | None, Field(description="节点文本")] = None,
    desc: Annotated[str | None, Field(description="contentDescription")] = None,
    view_id: Annotated[str | None, Field(description="资源 id")] = None,
    clickable_only: Annotated[bool, Field(description="只匹配可点击节点")] = False,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    body: dict[str, Any] = {}
    if node_id:
        body["node_id"] = node_id
    if text:
        body["text"] = text
    if desc:
        body["desc"] = desc
    if view_id:
        body["view_id"] = view_id
    body["clickable_only"] = clickable_only
    if not any([node_id, text, desc, view_id]):
        return "需要 node_id 或 text/desc/view_id 之一。"
    return await _action("/click", _with_step(body, step))


@server.tool(
    name="phone_long_click",
    description="长按节点（node_id 或 text/desc/view_id）。",
)
async def phone_long_click(
    node_id: Annotated[str | None, Field(description="节点 id")] = None,
    text: Annotated[str | None, Field(description="节点文本")] = None,
    desc: Annotated[str | None, Field(description="contentDescription")] = None,
    view_id: Annotated[str | None, Field(description="资源 id")] = None,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    body = {k: v for k, v in {"node_id": node_id, "text": text, "desc": desc, "view_id": view_id}.items() if v}
    if not body:
        return "需要 node_id 或 text/desc/view_id 之一。"
    return await _action("/long_click", _with_step(body, step))


@server.tool(
    name="phone_tap",
    description="按屏幕坐标点击（原生像素）。用于截图里看到但树里没有的内容。",
)
async def phone_tap(
    x: Annotated[float, Field(description="屏幕 x 坐标（原生像素）")],
    y: Annotated[float, Field(description="屏幕 y 坐标（原生像素）")],
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    return await _action("/tap", _with_step({"x": x, "y": y}, step))


@server.tool(
    name="phone_swipe",
    description="从 (x1,y1) 滑到 (x2,y2)，原生像素。可控制时长。",
)
async def phone_swipe(
    x1: Annotated[float, Field(description="起点 x")],
    y1: Annotated[float, Field(description="起点 y")],
    x2: Annotated[float, Field(description="终点 x")],
    y2: Annotated[float, Field(description="终点 y")],
    duration_ms: Annotated[int, Field(description="滑动时长毫秒", ge=50, le=5000)] = 300,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    return await _action(
        "/swipe",
        _with_step(
            {"x1": x1, "y1": y1, "x2": x2, "y2": y2, "duration_ms": duration_ms}, step
        ),
    )


@server.tool(
    name="phone_scroll",
    description=(
        "滚动列表。有 node_id 时在对应可滚动容器上滚动，否则在屏幕中部滑动。\n"
        "direction: up/down/left/right 表示内容前进方向（up = 看下面的内容）。"
    ),
)
async def phone_scroll(
    direction: Annotated[str, Field(description="up/down/left/right")] = "up",
    node_id: Annotated[str | None, Field(description="可滚动容器的节点 id")] = None,
    amount: Annotated[float, Field(description="滚动幅度 0.1–0.9（屏高的比例）", ge=0.1, le=0.9)] = 0.6,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    body: dict[str, Any] = {"direction": direction, "amount": amount}
    if node_id:
        body["node_id"] = node_id
    return await _action("/scroll", _with_step(body, step))


@server.tool(
    name="phone_set_text",
    description=(
        "向输入框写入文本。会先聚焦再 setText，失败时自动退化为剪贴板粘贴。\n"
        "对自定义输入框（Flutter/游戏）粘贴是唯一可行路径。"
    ),
)
async def phone_set_text(
    value: Annotated[str, Field(description="要输入的文本")],
    node_id: Annotated[str | None, Field(description="输入框节点 id")] = None,
    text: Annotated[str | None, Field(description="用当前文本定位输入框")] = None,
    desc: Annotated[str | None, Field(description="用描述定位输入框")] = None,
    view_id: Annotated[str | None, Field(description="用资源 id 定位")] = None,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    body: dict[str, Any] = {"value": value}
    for key, val in {"node_id": node_id, "text": text, "desc": desc, "view_id": view_id}.items():
        if val:
            body[key] = val
    if not any(k in body for k in ("node_id", "text", "desc", "view_id")):
        return "需要指定目标输入框：node_id 或 text/desc/view_id。"
    return await _action("/set_text", _with_step(body, step))


@server.tool(
    name="phone_key",
    description=(
        "系统按键：back / home / recents / notifications / quick_settings / "
        "power_dialog / lock_screen / take_screenshot。"
    ),
)
async def phone_key(
    action: Annotated[str, Field(description="按键名")],
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    return await _action("/key", _with_step({"action": action}, step))


@server.tool(
    name="phone_wait",
    description="等待界面稳定（连续一段时间树不变）。点击后等加载完成时用。",
)
async def phone_wait(
    timeout_ms: Annotated[int, Field(description="最长等待毫秒", ge=200, le=30000)] = 5000,
    quiet_ms: Annotated[int, Field(description="需要保持不变的时长毫秒", ge=100, le=5000)] = 400,
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    client = await _get_client()
    try:
        result = await client.post(
            "/wait_idle",
            _with_step({"timeout_ms": timeout_ms, "quiet_ms": quiet_ms}, step),
        )
        return "界面已稳定。" if result.get("stable") else "等待超时，界面可能仍在变化。"
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)


# ---------------------------------------------------------------- 应用


@server.tool(name="phone_apps", description="列出手机上可启动的应用（包名 + 名称）。")
async def phone_apps() -> str:
    client = await _get_client()
    try:
        data = await client.post("/apps")
        apps = data.get("apps") or []
        lines = [f"共 {len(apps)} 个应用："]
        for a in apps:
            lines.append(f"{a.get('label')}  {a.get('package')}")
        return "\n".join(lines)
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)


@server.tool(name="phone_launch", description="按包名启动应用。")
async def phone_launch(
    package: Annotated[str, Field(description="应用包名，如 com.tencent.mm")],
    step: Annotated[str | None, Field(description=_STEP_DESC)] = None,
) -> str:
    return await _action("/launch", _with_step({"package": package}, step))


# ---------------------------------------------------------------- 状态


@server.tool(name="phone_status", description="检查手机连接与无障碍服务状态。")
async def phone_status() -> str:
    client = await _get_client()
    cfg = _get_config()
    try:
        # 中继模式下 /health 是手机的，中继自身的设备列表另查
        data = await client.get("/health")
        screen = data.get("screen") or {}
        mode = "中继" if cfg.mode == "relay" else "局域网直连"
        target = cfg.device_id if cfg.mode == "relay" else cfg.host
        text = (
            f"连接正常 ✓（{mode} → {target}）\n"
            f"设备：{data.get('device')}  Android {data.get('sdk')}\n"
            f"屏幕：{screen.get('w')}x{screen.get('h')}\n"
            f"无障碍服务：{'已连接' if data.get('service_connected') else '未连接'}\n"
            f"当前应用：{data.get('package') or '(未知)'}"
        )
        if data.get("stopped"):
            left = int((data.get("stop_remaining_ms") or 0) / 1000) + 1
            text += f"\n\n⏸ 用户已中断自动操作（{left} 秒后放行）"
        guard = data.get("money_guard")
        if guard:
            text += (
                f"\n\n🛡 资金保护已启用：{guard.get('blocked_packages')} 个禁用包、"
                f"{guard.get('target_keywords')} 个目标词、{guard.get('screen_keywords')} 个整屏词"
            )
        else:
            text += "\n\n⚠ 手机端未上报资金保护策略：APK 可能是旧版，升级后才能获得完整拦截"
        return text
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)


# ---------------------------------------------------------------- 内部


async def _action(path: str, body: dict[str, Any]) -> str:
    """执行动作。

    用户接管手机时**不重试、不等待**——直接返回明确的信号，让模型知道
    "人正在操作，本轮该停手了"。是否继续由模型自己判断。
    """
    client = await _get_client()
    try:
        result = await client.post(path, body)
    except MoneyBlockedError as exc:
        # 同 HumanOverride：必须抛异常。返回字符串模型会当成"做完了"继续往下。
        raise ToolError(f"{MONEY_BLOCKED}\n原因：{exc}") from None
    except HumanOverrideError:
        # 必须抛异常而不是返回字符串：返回值会被模型当成"工具正常执行完了"，
        # 它会继续往下做。只有工具调用失败，模型才会停下来重新规划。
        raise ToolError(USER_TOOK_OVER) from None
    except Exception as exc:  # noqa: BLE001
        return _fail(exc)
    if result.get("ok"):
        target = result.get("target")
        line = f"成功。{('目标：' + target) if target else ''}".strip()
        # 点了但界面没变：手势执行了，可什么都没发生。
        # 不说清楚，模型会以为做成了继续往下走。
        if result.get("no_effect"):
            line += (
                "\n⚠ 界面没有任何变化。可能点在了空白处、目标被遮挡，或页面本来就不响应。"
                "请重新 phone_observe 确认当前界面，别假设这一步成功了。"
            )
        if result.get("warning"):
            line += f"\n⚠ {result['warning']}"
        if result.get("hit"):
            line += f"\n落点上的控件：「{result['hit']}」"
        return line
    return f"失败：{result.get('error', '未知原因')}"
