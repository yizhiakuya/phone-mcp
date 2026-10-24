"""Render the bridge's JSON tree into a compact, model-friendly text form.

Raw JSON costs 3-5x the tokens of a line-per-node format, and models read the
indented form more reliably. Node ids stay stable within one snapshot, which is
what every action tool takes.
"""

from __future__ import annotations

from typing import Any, Iterable

# 只保留对决策有用、且不占太多字符的标记
_FLAG_ORDER = (
    "click",
    "longclick",
    "edit",
    "scroll",
    "checkable",
    "checked",
    "selected",
    "focused",
    "disabled",
    "password",
)


def _quote(value: str, limit: int = 120) -> str:
    value = value.replace("\n", "\\n").replace("\t", " ")
    if len(value) > limit:
        value = value[: limit - 1] + "…"
    return f'"{value}"'


def _bounds(node: dict[str, Any]) -> str:
    box = node.get("b")
    if not box or len(box) != 4:
        return ""
    return f"[{box[0]},{box[1]},{box[2]},{box[3]}]"


def _flags(node: dict[str, Any]) -> str:
    raw = node.get("f") or []
    present = set(raw)
    ordered = [f for f in _FLAG_ORDER if f in present]
    return " ".join(ordered)


def _line(node: dict[str, Any], depth: int) -> str:
    parts: list[str] = []
    cls = node.get("c", "Node")
    parts.append(cls)

    if node.get("t"):
        parts.append(_quote(node["t"]))
    if node.get("d"):
        parts.append("desc=" + _quote(node["d"]))
    if node.get("v"):
        parts.append(node["v"].split("/", 1)[-1] if "/" in node["v"] else node["v"])
    if node.get("p"):
        parts.append("@" + node["p"])

    box = _bounds(node)
    if box:
        parts.append(box)
    flags = _flags(node)
    if flags:
        parts.append(flags)

    more = node.get("more")
    if more:
        parts.append(f"(+{more} 子节点未展开)")

    indent = "  " * depth
    return f"{indent}[{node.get('i', '?')}] " + " ".join(parts)


def _walk(node: dict[str, Any], depth: int, out: list[str]) -> None:
    out.append(_line(node, depth))
    for child in node.get("ch") or []:
        _walk(child, depth + 1, out)


def _iter_nodes(node: dict[str, Any]) -> Iterable[dict[str, Any]]:
    yield node
    for child in node.get("ch") or []:
        yield from _iter_nodes(child)


def render_tree(
    payload: dict[str, Any],
    *,
    interactive_only: bool = False,
    contains: str | None = None,
    max_lines: int | None = None,
) -> str:
    root = payload.get("root")
    if not root:
        return "(空界面：没有可读取的节点)"

    screen = payload.get("screen") or {}
    header_bits = [
        f"屏幕 {screen.get('w', '?')}x{screen.get('h', '?')}",
        f"包名 {payload.get('package') or '?'}",
    ]
    if payload.get("window"):
        header_bits.append(f"窗口 {payload['window']}")
    header_bits.append(f"节点 {payload.get('node_count', '?')}")
    header_bits.append(f"快照 v{payload.get('snapshot', '?')}")

    body: list[str] = []

    if interactive_only or contains:
        picked = []
        needle = contains.lower() if contains else None
        for node in _iter_nodes(root):
            flags = set(node.get("f") or [])
            if interactive_only and not ({"click", "edit", "scroll", "longclick"} & flags):
                continue
            if needle:
                haystack = " ".join(
                    str(node.get(k, "")) for k in ("t", "d", "v", "c")
                ).lower()
                if needle not in haystack:
                    continue
            picked.append(_line(node, 0))
        if not picked:
            return "\n".join(header_bits) + "\n(没有匹配的节点)"
        if max_lines and len(picked) > max_lines:
            picked = picked[:max_lines] + [f"… 还有 {len(picked) - max_lines} 个未显示"]
        return "\n".join(header_bits + ["---"] + picked)

    _walk(root, 0, body)
    if max_lines and len(body) > max_lines:
        body = body[:max_lines] + [f"… 共 {len(body)} 行，已截断。用 contains/interactive_only 收窄，或调大 max_lines"]

    windows = payload.get("windows")
    tail = ""
    if windows:
        rows = []
        for w in windows:
            active = "*" if w.get("active") else " "
            rows.append(f"{active} [{w.get('type')}] {w.get('package')} {w.get('title') or ''}".rstrip())
        tail = "\n\n窗口:\n" + "\n".join(rows)

    return "\n".join(header_bits + ["---"] + body) + tail
