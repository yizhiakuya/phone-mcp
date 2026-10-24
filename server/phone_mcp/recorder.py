"""动作录制与回放。

## 为什么

模型做一件事要走「看界面 → 想一步 → 点一下」的循环，一个二十步的固定流程
每次都要重新推理，烧掉大量 token。录下来之后，同样的流程一次调用重放。

## 回放为什么是安全的

每一步都检查返回结果：工具报失败、或手势成功但界面没变化（`no_effect`），
立刻停下，把控制权交回模型。**绝不跳过失败继续执行下一步**——那会在界面
已经错位的情况下继续乱点。

## 局限

- `node_id` 是单次快照内的临时编号，回放时必然失效。录制时会从返回里
  解析出目标的文本/描述/资源 id 作为替代定位；解析不出来就只能依赖
  原参数，通常会在该步失败并停下。
- 纯坐标步骤（tap/swipe）按原坐标重放，界面布局变了会点错。
- 录制不保存「预期结果」，除非流程里显式用了 `phone_wait_until`——
  那一步本身就是校验点。
"""

from __future__ import annotations

import contextlib
import json
import os
import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterator


# 可回放的动作。读取类（observe / screenshot / ui / find / status / apps）
# 不录：它们不改状态，回放时应当重新读当前界面，而不是重放旧数据。
REPLAYABLE_TOOLS = frozenset({
    "phone_launch", "phone_click", "phone_long_click", "phone_tap",
    "phone_swipe", "phone_scroll", "phone_set_text", "phone_key",
    "phone_wait", "phone_wait_until",
})

# 端点 → 工具名。`_action` 是所有动作的统一出口，在那里集中录制。
PATH_TO_TOOL = {
    "/launch": "phone_launch",
    "/click": "phone_click",
    "/long_click": "phone_long_click",
    "/tap": "phone_tap",
    "/swipe": "phone_swipe",
    "/scroll": "phone_scroll",
    "/set_text": "phone_set_text",
    "/key": "phone_key",
}

# 从 `describeForLog()` 的输出里抠出定位信息：
#   text="发送" desc="..." id=com.app:id/btn bounds=[...]
_TEXT_RE = re.compile(r'text="([^"]*)"')
_DESC_RE = re.compile(r'desc="([^"]*)"')
_ID_RE = re.compile(r"id=(\S+)")


class RecorderError(RuntimeError):
    """录制/回放的用法错误（不是手机端错误）。"""


@dataclass
class Step:
    tool: str
    args: dict[str, Any]
    step: str | None = None
    # 录制时解析出的目标定位信息，回放时 node_id 失效可拿它兜底
    fallback: dict[str, str] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {"tool": self.tool, "args": self.args}
        if self.step:
            d["step"] = self.step
        if self.fallback:
            d["fallback"] = self.fallback
        return d

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> Step:
        return cls(
            tool=str(d.get("tool") or ""),
            args=dict(d.get("args") or {}),
            step=d.get("step"),
            fallback=dict(d.get("fallback") or {}),
        )


@dataclass
class Recording:
    name: str
    created: float
    steps: list[Step] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "created": self.created,
            "steps": [s.to_dict() for s in self.steps],
        }


def default_dir() -> Path:
    env = os.environ.get("PHONE_MCP_RECORDINGS_DIR", "").strip()
    if env:
        return Path(env)
    return Path.home() / ".phone-mcp" / "recordings"


def _safe_name(name: str) -> str:
    """校验录制名。它是文件名的一部分，必须挡住路径穿越。"""
    n = (name or "").strip()
    if not n:
        raise RecorderError("录制名不能为空")
    if len(n) > 64:
        raise RecorderError("录制名过长（最多 64 字）")
    if n in (".", "..") or any(c in n for c in '/\\:*?"<>|'):
        raise RecorderError("录制名不能包含路径分隔符或非法字符")
    return n


def _clean_args(args: dict[str, Any]) -> dict[str, Any]:
    """存盘用的参数副本。去掉 None，保留原始类型。"""
    return {k: v for k, v in args.items() if v is not None}


def _parse_target(target: str | None) -> dict[str, str]:
    """从 `text="..." desc="..." id=...` 里解析出可复用的定位信息。

    解析失败返回空 dict——宁可不兜底，也不要拿错的目标去点。
    """
    if not target:
        return {}
    out: dict[str, str] = {}
    if (m := _TEXT_RE.search(target)) and m.group(1).strip():
        out["text"] = m.group(1)
    if (m := _DESC_RE.search(target)) and m.group(1).strip():
        out["desc"] = m.group(1)
    if m := _ID_RE.search(target):
        out["view_id"] = m.group(1)
    return out


class Recorder:
    """录制状态机 + 磁盘读写。只在服务端运行，不改手机端。"""

    def __init__(self, directory: Path) -> None:
        self._dir = directory
        self._active: Recording | None = None
        # >0 表示暂停记录（回放期间不要把自己录进去）
        self._suspended = 0

    @property
    def is_recording(self) -> bool:
        return self._active is not None

    @property
    def active_name(self) -> str | None:
        return self._active.name if self._active else None

    @property
    def active_step_count(self) -> int:
        return len(self._active.steps) if self._active else 0

    # -- 录制 --------------------------------------------------------------

    def start(self, name: str, overwrite: bool = False) -> Recording:
        if self._active is not None:
            raise RecorderError(
                f"已经在录制「{self._active.name}」（{len(self._active.steps)} 步），"
                "先 stop 再开始新的"
            )
        safe = _safe_name(name)
        if (self._dir / f"{safe}.json").exists() and not overwrite:
            raise RecorderError(f"已存在同名录制「{safe}」，加 overwrite 覆盖")
        self._active = Recording(name=safe, created=time.time())
        return self._active

    def stop(self) -> Recording:
        rec = self._active
        if rec is None:
            raise RecorderError("当前没有在录制")
        self._active = None
        if rec.steps:
            self._save(rec)
        return rec

    def cancel(self) -> None:
        self._active = None

    def append(self, tool: str, args: dict[str, Any], *, target: str | None = None) -> None:
        """记录一步。未在录制或已暂停时静默忽略。"""
        if self._active is None or self._suspended > 0:
            return
        if tool not in REPLAYABLE_TOOLS:
            return
        self._active.steps.append(
            Step(
                tool=tool,
                args=_clean_args(args),
                step=(str(args.get("step")) if args.get("step") else None),
                fallback=_parse_target(target),
            )
        )

    @contextlib.contextmanager
    def suspended(self) -> Iterator[None]:
        """回放期间暂停录制，避免把自己录进去。"""
        self._suspended += 1
        try:
            yield
        finally:
            self._suspended -= 1

    # -- 存取 --------------------------------------------------------------

    def _path(self, name: str) -> Path:
        return self._dir / f"{name}.json"

    def _save(self, rec: Recording) -> None:
        self._dir.mkdir(parents=True, exist_ok=True)
        path = self._path(rec.name)
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(
            json.dumps(rec.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        os.replace(tmp, path)

    def list(self) -> list[dict[str, Any]]:
        if not self._dir.exists():
            return []
        out: list[dict[str, Any]] = []
        for p in sorted(self._dir.glob("*.json")):
            try:
                d = json.loads(p.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            out.append({
                "name": d.get("name") or p.stem,
                "created": d.get("created"),
                "steps": len(d.get("steps") or []),
            })
        return out

    def load(self, name: str) -> Recording:
        safe = _safe_name(name)
        path = self._path(safe)
        if not path.exists():
            raise RecorderError(f"没有名为「{safe}」的录制")
        try:
            d = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise RecorderError(f"录制「{safe}」已损坏：{exc}") from None
        return Recording(
            name=d.get("name") or safe,
            created=float(d.get("created") or 0),
            steps=[Step.from_dict(x) for x in (d.get("steps") or [])],
        )

    def delete(self, name: str) -> None:
        safe = _safe_name(name)
        path = self._path(safe)
        if not path.exists():
            raise RecorderError(f"没有名为「{safe}」的录制")
        path.unlink()

    def rename(self, old: str, new: str) -> Recording:
        """改名。内容里的 name 字段也要同步，否则 list 会显示旧名。"""
        src = _safe_name(old)
        dst = _safe_name(new)
        if src == dst:
            return self.load(src)
        if self._path(dst).exists():
            raise RecorderError(f"已存在同名录制「{dst}」")
        rec = self.load(src)
        rec.name = dst
        self._save(rec)
        self._path(src).unlink()
        return rec
