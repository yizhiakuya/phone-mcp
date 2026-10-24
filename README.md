# phone-mcp

通过 Android 无障碍服务（AccessibilityService）远程操作一台真实手机的 MCP 服务器。

模型可以先读取界面的无障碍树与截图，再按节点语义执行点击、输入、滚动等动作；
手机主动外连中继，因此在 4G、异地网络下同样可用，不需要端口映射。

**文档**
[架构设计](docs/architecture.md) · [线程模型](docs/threading.md) · [部署与运维](docs/operations.md) · [安全模型](docs/security.md) · [验收记录](docs/security-validation.md) · [踩坑记录](docs/lessons.md)

---

## 组成

```
MCP Client ──stdio──► phone-mcp (Python) ──HTTPS──► Relay ◄──WSS── Android App
                                                      │
                                     手机主动外连，穿越 NAT / 移动网络
                                     指令与截图端到端加密，中继只见密文
```

| 组件 | 位置 | 职责 |
|---|---|---|
| MCP 服务器 | `server/` | 17 个工具，把模型调用翻译成对手机的请求 |
| 中继 | `relay/` | 手机与 MCP 之间的会合点，转发密文，不持有密钥 |
| Android App | `android/` | 无障碍能力的唯一持有者，执行全部界面操作 |

## 设计要点

**手机主动外连。** 手机位于 NAT 之后，电脑无法直接连接。改由手机启动时向中继建立
WebSocket 长连接，中继把请求塞进这条通道。换 WiFi、切 4G、换网络都无需改配置。

**指令端到端加密。** 中继是唯一能看到全部流量的节点，因此不把它当作可信方。
指令与截图以 AES-256-GCM 加密后转发，密钥随配对二维码在电脑与手机之间直接分发。
中继能观察到元数据（谁、何时、多大），但无法读取内容。

**扫码配对鉴权。** 没有内置共享口令。管理员生成一次性配对码（五分钟过期、
内存只存摘要），手机扫码后换取专属设备凭据，并各自保存端到端密钥。

**资金保护。** 涉及支付、转账、购买的操作在手机端被硬性拦截。策略编译进 APK，
不提供任何远程开关，MCP 侧没有工具能关闭它。详见[安全模型](docs/security.md#资金保护)。

**人工中断。** 屏幕底部常驻一条状态条，显示 AI 当前步骤；用户点一下即可中断，
当前动作立刻失败返回。Android 无法区分注入手势与真人触摸，因此不采用自动检测。

## 快速开始

### 1. 构建并安装 App

```bash
# 需要 Android SDK、JDK 17+、Gradle 8.9
bash scripts/build_apk.sh --no-upload --no-bump
bash scripts/install.sh          # adb 安装
```

首次使用需在系统设置中开启无障碍服务、悬浮窗权限，并允许自启动与后台弹出界面
（部分国产 ROM 必需）。

### 2. 部署中继

```bash
export PHONE_MCP_RELAY_HOST=user@your-server
bash scripts/deploy_relay.sh
```

中继只监听本机端口，对外由 nginx 终止 TLS。反向代理配置见[部署与运维](docs/operations.md)。

### 3. 配对

```bash
cd server
python -m phone_mcp.pairing \
  --relay-url https://relay.example.com:8900 \
  --admin-token-file ../.secrets/admin/admin.token \
  pair --out ../.secrets/phone1
```

用手机扫描生成的 `pairing.png`，然后把 `mcp-env.json` 的内容填入 MCP 客户端配置。

### 4. 配置 MCP 客户端

```json
{
  "mcpServers": {
    "phone": {
      "command": "python",
      "args": ["-m", "phone_mcp.server"],
      "env": {
        "PHONE_MCP_RELAY_URL": "https://relay.example.com:8900",
        "PHONE_MCP_RELAY_TOKEN_FILE": "/path/to/control.token",
        "PHONE_MCP_DEVICE_ID": "phone-xxxxxxxx",
        "PHONE_MCP_E2E_KEY_FILE": "/path/to/e2e.key"
      }
    }
  }
}
```

凭据一律通过文件引用（`*_FILE`），不要写进命令行参数或配置文件明文。

## 工具

| 工具 | 用途 |
|---|---|
| `phone_observe` | 截图 + 无障碍树一次获取，最常用的入口 |
| `phone_screenshot` | 仅截图 |
| `phone_ui` | 仅读树，支持按文本过滤 |
| `phone_find` | 按文本/描述/资源 id 查找节点 |
| `phone_click` | 按节点 id 或语义定位点击 |
| `phone_long_click` | 长按 |
| `phone_tap` / `phone_swipe` | 坐标操作，用于游戏、Canvas 等无树场景 |
| `phone_scroll` | 滚动列表 |
| `phone_set_text` | 输入文本 |
| `phone_key` | 系统按键（back / home / recents 等） |
| `phone_wait` | 等待界面稳定（树指纹不再变化） |
| `phone_wait_until` | 等待某段文本出现或消失，语义条件更准 |
| `phone_watch` | 阻塞等待无障碍事件（通知、界面切换等），监听而非轮询 |
| `phone_apps` / `phone_launch` | 列出并启动应用 |
| `phone_record` / `phone_replay` | 录制操作流程并一键重放，失败即停 |
| `phone_status` | 连接与服务状态 |

所有工具接受可选的 `step` 参数（一句中文短语），用于在手机状态条上显示当前进度。

## 状态条

AI 操作时屏幕底部显示一条胶囊，实时反映当前步骤：

```
● AI · 在搜索框输入学超 ▾
```

点击展开可看到最近五步、累计步数与耗时，以及「停止操作」按钮。中继断开时同一条
转为橙色提示并提供重连入口。三种状态共用一条悬浮窗。

## 仓库结构

```
server/       MCP 服务器（Python）
  phone_mcp/    工具层、传输层、端到端加密、配对 CLI
  tests/        单元与集成测试
relay/        中继服务（Python / aiohttp）
android/      手机端（Kotlin）
  app/src/main/java/top/rainaki/phonemcp/
    service/      无障碍服务、资金保护、状态条
    tunnel/       WebSocket 客户端
    tree/         无障碍树序列化与快照
docs/         架构、线程模型、部署、安全
scripts/      构建、部署、诊断脚本
```

## 开发

```bash
# Python 测试
cd server && python -m pytest -q

# Android 单元测试
cd android && gradle :app:testDebugUnitTest

# 设备健康检查
python scripts/device_status.py
```

修改 Android 端前请先阅读[线程模型](docs/threading.md)——无障碍 API 有线程亲和性，
跨线程使用 `AccessibilityNodeInfo` 会产生未定义行为。

## 已知限制

- 最低 Android 11（`takeScreenshot` 要求 API 30+）
- 银行、支付类 App 普遍启用 `FLAG_SECURE`，截图全黑，只能通过无障碍树操作
- 部分 ROM（MIUI、ColorOS 等）需要手动授予自启动、后台无限制、后台弹出界面权限
- 资金保护依赖包名、目标节点文本与页面可见文本判定；纯图片渲染的支付页无法识别
- 中继可观察元数据，也可拒绝服务，但无法读取或伪造内容

## 许可

尚未指定开源许可证。未经许可请勿分发。
