"""Create a short-lived pairing QR, or revoke a paired phone. Never print tokens."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess

import httpx

from .config import _normalize_url


def _private_directory(path: Path) -> None:
    path.mkdir(parents=True, mode=0o700, exist_ok=False)
    if os.name == "nt":
        identity = subprocess.check_output(["whoami", "/user", "/fo", "csv", "/nh"], text=True)
        sid = re.search(r"S-1-[0-9-]+", identity)
        if sid is None:
            raise RuntimeError("无法确定当前用户，未保存凭据")
        subprocess.run(["icacls", str(path), "/inheritance:r", "/grant:r", f"*{sid.group()}:(OI)(CI)F"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        path.chmod(0o700)


def save_pairing(result: dict, origin: str, directory: Path) -> None:
    """保存配对产物。

    端到端密钥在这里生成：它只写进二维码（给手机）和本机 mcp-env（给电脑），
    **不发给中继**。中继因此永远只有密文。
    """
    import qrcode

    from .e2ee import generate_key

    token_file = directory / "control.token"
    with token_file.open("x", encoding="utf-8") as out:
        out.write(result["control_token"])
    if os.name != "nt":
        token_file.chmod(0o600)

    e2e_key = generate_key()
    key_file = directory / "e2e.key"
    with key_file.open("x", encoding="utf-8") as out:
        out.write(e2e_key)
    if os.name != "nt":
        key_file.chmod(0o600)

    config = {
        "PHONE_MCP_RELAY_URL": origin,
        "PHONE_MCP_DEVICE_ID": result["device_id"],
        "PHONE_MCP_RELAY_TOKEN_FILE": str(token_file.resolve()),
        "PHONE_MCP_E2E_KEY_FILE": str(key_file.resolve()),
    }
    (directory / "mcp-env.json").write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding="utf-8")

    # 二维码里带上 e2e_key——这是它唯一一次离开本机
    qr = dict(result["qr"])
    qr["e2e_key"] = e2e_key
    qrcode.make(json.dumps(qr, separators=(",", ":"))).save(directory / "pairing.png")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay-url", required=True, help="HTTPS 源地址，如 https://host:8900")
    parser.add_argument("--admin-token-file", required=True, type=Path, help="仅当前用户可读的管理员凭据文件")
    commands = parser.add_subparsers(dest="command", required=True)
    pair = commands.add_parser("pair", help="生成一次性配对二维码和专属 MCP 配置")
    pair.add_argument("--out", required=True, type=Path, help="新建私有输出目录，不允许覆盖已有目录")
    commands.add_parser("list", help="列出已配对和在线设备")
    revoke = commands.add_parser("revoke", help="立即吊销设备与控制凭据，并断开设备")
    revoke.add_argument("device_id")
    args = parser.parse_args()
    try:
        origin = _normalize_url(args.relay_url, relay=True)
        token = args.admin_token_file.read_text(encoding="utf-8").strip()
        if not token:
            raise ValueError("管理员凭据文件为空")
        if args.command == "revoke" and not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.device_id):
            raise ValueError("设备 ID 无效")
        if args.command == "pair":
            import qrcode  # 检查可选依赖后再创建服务器配对请求。
            _private_directory(args.out)
        with httpx.Client(base_url=origin, headers={"Authorization": f"Bearer {token}"},
                          timeout=30, follow_redirects=False, trust_env=False) as client:
            if args.command == "pair":
                response = client.post("/phonemcp/pairings", json={"relay_url": origin})
            elif args.command == "list":
                response = client.get("/phonemcp/devices")
            else:
                response = client.delete(f"/phonemcp/devices/{args.device_id}")
            if not response.is_success:
                raise RuntimeError(f"中继请求失败（HTTP {response.status_code}），请检查权限与反向代理路由")
            result = response.json()
        if args.command == "pair":
            save_pairing(result, origin, args.out)
            print(f"设备：{result['device_id']}；二维码有效 {result['expires_in']} 秒，仅可使用一次。")
            print(f"请用手机扫码：{(args.out / 'pairing.png').resolve()}")
            print(f"电脑 MCP 配置：{(args.out / 'mcp-env.json').resolve()}")
        elif args.command == "list":
            for device in result["devices"]:
                print(device["device_id"], "在线" if device["online"] else "离线")
        else:
            print(f"已撤销 {args.device_id}，旧凭据不能再连接或控制。")
    except ImportError:
        parser.exit(1, "请先安装 phone-mcp[pairing] 以生成二维码。\n")
    except (OSError, ValueError, RuntimeError, httpx.HTTPError, subprocess.SubprocessError) as exc:
        # 网络异常可能含 URL、代理或响应载荷；仅输出类型与固定提示。
        parser.exit(1, f"操作失败（{type(exc).__name__}），检查地址、凭据文件、目录权限及中继状态；未输出凭据。\n")


if __name__ == "__main__":
    main()
