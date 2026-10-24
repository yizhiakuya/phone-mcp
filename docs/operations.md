# 运维手册

日常维护、排障、发布的操作步骤。架构见 [architecture.md](architecture.md)。

## 健康检查

```bash
# 中继是否在跑、有哪些设备在线
curl -s -H "Authorization: Bearer $(cat .secrets/admin/admin.token)" \
  https://relay.example.com:8900/phonemcp/health | python -m json.tool

# 中继服务状态
ssh root@relay-host "systemctl status phonemcp-relay --no-pager | head -20"

# 中继日志
ssh root@relay-host "journalctl -u phonemcp-relay -n 50 --no-pager"
```

`/health` 的 `diag` 字段是排查重点：

| 字段 | 含义 |
|---|---|
| `main_timeouts` | 主线程超时次数。持续增长说明有无障碍调用卡死 |
| `root_available` | `false` = 服务绑着但读不到窗口（系统禁用了无障碍） |
| `recent_events` | 最近事件类型，排查"事件没来" |
| `stopped` | 用户是否点了中断 |
| `action_running` | 是否有动作在执行 |

## 排障决策树

### 报「设备不在线」

```
1. 手机上 App 的「中继隧道」显示什么？
   ├─ 已连接 → 设备 ID 对不上（卸载重装会变）→ 改 MCP 配置
   └─ 未连接/连接中
      ├─ 检查网络（4G/WiFi）
      ├─ App 是否被杀（看通知栏有没有常驻通知）
      └─ 中继是否活着（上面健康检查）
```

### 无障碍服务被系统自动关闭

**根因通常是主线程阻塞导致 ANR。** 检查：

1. `/health` 的 `diag.main_timeouts` 是否在涨
2. 最近是否调过 `phone_wait`、`phone_ui`（大树遍历）

修复方向见 `docs/threading.md` 规则 4：主线程禁止长循环。

### 动作 5 秒后返回失败

在主线程等一个需要主线程执行的回调 —— 死锁。检查是否把 `dispatchGesture` / `takeScreenshot` 包进了 `callOnMain`。

### 点击没反应

定位降级链：`node_id` → 指纹重定位 → 文本搜索 → 坐标手势。全失败会明确报错。如果"找到了但点不动"，可能是节点不可点且父链也不可点。

### 截图超时或 `code=3`

`code=3` = `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`，截图太频繁。已内置 3 次重试（350ms 间隔）。持续失败要查：
- 是否 `FLAG_SECURE` 页面（银行/支付类）
- 是否系统弹窗遮挡

### 启动 App 被拒绝

MIUI 拦截后台应用拉起其它应用。需要：
```
设置 → 应用管理 → Phone MCP → 权限管理 → 后台弹出界面 → 允许
```

### 输入文字重复

`ACTION_SET_TEXT` 是异步的，不能按 `performAction` 返回后的旧 `node.text` 判断失败，否则会再走一遍粘贴。已修：返回 true 就信它。

## 发布流程

### 发新版 APK

```bash
cd /d/phone-mcp
bash scripts/build_apk.sh --changelog "修复了 XXX"
```

一条命令完成全部发布：

1. 递增 `versionCode` / `versionName`
2. release 构建 + 校验签名（DN 必须含 `CN=Phone MCP`，否则中止）
3. 生成 `version.json`（含版本号、大小、MD5、changelog、发布时间）
4. 上传到中继主机：
   - `phonemcp-<版本>.apk`（按版本命名，保留历史）
   - 更新 `phonemcp-latest.apk` 软链
   - 更新 `version.json`
   - **重新生成下载页** `index-phonemcp.html`（版本号/MD5/大小自动同步）

完成后打印两个地址。

### 下载链接从哪来

| 地址 | 内容 |
|---|---|
| `https://relay.example.com:8900/phonemcp` | 下载页（手机浏览器打开） |
| `https://relay.example.com:8900/apk/phonemcp-latest.apk` | APK 直链 |
| `https://relay.example.com:8900/version.json` | 版本清单（App 自更新用） |

三者都由 nginx 静态提供，物理路径在 中继主机的 `/var/www/phone-album/`。

**nginx 路由**（在 `/etc/nginx/sites-enabled/phone-album` 的 TLS server 块里）：

```nginx
# 下载页
location = /phonemcp {
    alias /var/www/phone-album/index-phonemcp.html;
    default_type text/html;
}

# APK 静态直出（不经隧道、不限速）
location /apk/ {
    alias /var/www/phone-album/;
    autoindex off;
    add_header Content-Disposition "attachment" always;
}

# 版本清单，禁缓存避免拿到旧版本号
location = /version.json {
    alias /var/www/phone-album/version.json;
    default_type application/json;
    add_header Cache-Control "no-store" always;
}
```

**首次部署**（新服务器）需要手动建这三条路由 + `nginx -t && systemctl reload nginx`。之后发版由脚本自动维护文件，不用再动 nginx。

**验证发布结果**：

```bash
curl -s https://relay.example.com:8900/version.json | python -m json.tool
curl -sI https://relay.example.com:8900/apk/phonemcp-latest.apk | head -5
# 下载后核对 MD5
curl -s https://relay.example.com:8900/apk/phonemcp-latest.apk | md5sum
```

### 部署中继

```bash
bash scripts/deploy_relay.sh
```

同步 `relay.py` + systemd unit → 重启 → 打印状态（不打印凭据）。

改了 nginx 路由要单独处理：

```bash
ssh root@relay-host "nginx -t && systemctl reload nginx"
```

## 配对与设备管理

### 配对一台新手机

```bash
server/.venv/Scripts/python.exe -m phone_mcp.pairing \
  --relay-url https://relay.example.com:8900 \
  --admin-token-file .secrets/admin/admin.token \
  pair --out .secrets/phone-<名字>
```

输出目录里有：
- `pairing.png` —— 手机扫这个
- `control.token` —— 专属控制凭据
- `mcp-env.json` —— 直接填进 MCP 配置 `env`

### 列出设备

```bash
server/.venv/Scripts/python.exe -m phone_mcp.pairing \
  --relay-url https://relay.example.com:8900 \
  --admin-token-file .secrets/admin/admin.token list
```

### 撤销设备

```bash
server/.venv/Scripts/python.exe -m phone_mcp.pairing \
  --relay-url https://relay.example.com:8900 \
  --admin-token-file .secrets/admin/admin.token revoke phone-xxxxxxxxxxxx
```

撤销会：删除设备凭据 + 专属控制凭据 → 持久化 → 关闭在线通道 → 拒绝后续请求。

**手机的「清除本机配对」只清本地，不代表服务器撤销。**

## 备份清单

**必须备份**（丢了无法恢复）：

| 文件 | 后果 |
|---|---|
| `android/keystore/phonemcp.jks` + `password.txt` | 丢了**永远无法覆盖升级**，只能卸载重装且用户数据全失 |
| `.secrets/admin/admin.token` | 丢了无法管理设备，需改中继 env 并重启 |
| 中继 `/opt/phonemcp-relay/relay.env` | 含管理员凭据 |
| 中继 `/var/lib/phonemcp-relay/registry.json` | 已配对设备注册表，丢了所有设备需重新配对 |

## 轮换凭据

### 轮换管理员 Token

```bash
ssh root@relay-host "nano /opt/phonemcp-relay/relay.env"   # 改 PHONE_MCP_CONTROL_TOKEN
ssh root@relay-host "systemctl restart phonemcp-relay"
bash scripts/relay_creds.sh .secrets/admin    # 重新导出到私有文件
```

### 轮换某台设备

用 `revoke` 撤销后重新配对。

## 常见维护

### 重启中继

```bash
ssh root@relay-host "systemctl restart phonemcp-relay"
```

手机端会自动重连（指数退避，最长 30 秒）。**中继重启会让所有在线设备短暂掉线**，但注册表持久化，凭据不会失效。

### 查看中继资源占用

```bash
ssh root@relay-host "systemctl status phonemcp-relay | grep -E 'Memory|CPU'"
```

### 清理旧 APK

`/var/www/phone-album/` 下的历史 APK 可以删，但要保留 `phonemcp-latest.apk` 软链和 `version.json`。

## 手机端维护

### 必须给的权限

| 权限 | 位置 | 不给的后果 |
|---|---|---|
| 无障碍服务 | 设置 → 无障碍 | 完全无法工作 |
| 后台弹出界面 | 应用管理 → Phone MCP → 权限 | 无法启动其它 App |
| 自启动 | 应用管理 → Phone MCP | 重启后不自动连 |
| 电池无限制 | 应用管理 → Phone MCP → 省电策略 | 后台被回收，隧道断 |
| 悬浮窗 | 应用管理 → Phone MCP | 中断条不显示 |
| 安装未知应用 | 首次更新时引导 | 无法自更新 |
| 相机 | 首次配对时引导 | 无法扫码 |

**每次安装新版 APK，无障碍服务会被系统关闭**，必须手动重开。

### 设备 ID 变化

卸载重装后设备 ID 重新生成（`phone-xxxxxx`）。需要同步改 MCP 配置的 `PHONE_MCP_DEVICE_ID`。

想避免的话，别卸载——用 App 内更新（保留数据）。

## 测试

```bash
cd server
.venv/Scripts/python.exe -m pytest -q          # 全部
.venv/Scripts/python.exe -m pytest tests/test_relay.py -q   # 中继
```

Android 端：

```bash
cd android
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

真机冒烟：

```bash
PHONE_MCP_RELAY_URL=https://relay.example.com:8900 \
PHONE_MCP_RELAY_TOKEN=<控制token> \
PHONE_MCP_DEVICE_ID=phone-xxxxxx \
server/.venv/Scripts/python.exe scripts/smoke_test.py
```
