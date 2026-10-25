# 扫码配对与安全迁移

本版本（Android 1.1.0 / versionCode 31）移除了 APK 内置共享设备 Token。
旧手机和旧中继不能直接沿用连接：先准备新版中继和 HTTPS 路由，再升级手机并扫码。
部署会断开旧版设备，应在能够实际操作手机的维护窗口进行。

## 配对流程

1. 管理员在电脑运行 `phone-mcp-pair ... pair --out <新目录>`。
2. 中继创建 256 位随机的一次性配对码，五分钟过期，内存只保存摘要；重启失效。
3. 电脑保存二维码、专属控制凭据与 `mcp-env.json`，输出目录限制其他普通用户访问；系统和管理员仍可访问。
4. 手机扫码，通过 HTTPS POST `/phonemcp/pair` 兑换设备凭据。
5. 手机将凭据用 Android Keystore 中的 AES-GCM 密钥加密保存，建立 WSS 隧道。
6. 日常 MCP 使用生成的专属控制凭据；管理员凭据只用于管理，不放入日常配置。

配对码相当于短期入场凭证，请勿发布到群聊或公共页面。只扫描自己可信电脑上的二维码。
配对过程中断网可能导致码已兑换但手机未保存，生成新码重试，并用 `list`/`revoke` 清理旧条目。
扫码时相机权限被拒绝可取消返回，再到系统设置授予相机权限。

## 中继部署

1. 备份现有中继程序、环境文件、systemd unit、nginx 配置到仅管理员可读的位置。
2. 安装 `relay/requirements.txt` 中依赖。
3. 同步 `relay/relay.py` 和 `relay/phonemcp-relay.service`；部署脚本不再打印凭据。
4. 环境文件 `/opt/phonemcp-relay/relay.env` 保留随机管理员 `PHONE_MCP_CONTROL_TOKEN`，权限 0600。
5. 删除旧 `PHONE_MCP_DEVICE_TOKEN`、`PHONE_MCP_DEVICE_TOKENS` 配置；新版不会接受它们。
6. `systemctl daemon-reload` 后重启。unit 用 `StateDirectory` 创建 `/var/lib/phonemcp-relay`（0700），注册表原子写入其中（0600）。
7. 新增下列 HTTPS 代理路由，再 `nginx -t`，通过后 reload。保留现有下载页、APK、版本清单路由。

把以下 location 放进现有 TLS server 块；已有同名规则应合并，避免重复。

```nginx
location ~ ^/phonemcp/(pairings|pair|devices(?:/[^/]+)?)$ {
    rewrite ^/phonemcp/(.*)$ /$1 break;
    proxy_pass http://127.0.0.1:8903;
    proxy_set_header Host $host;
    proxy_set_header Authorization $http_authorization;
    proxy_read_timeout 65s;
    proxy_cache off;
    client_max_body_size 16k;
    access_log off;
}
```

现有 `/phonemcp/tunnel` 需正确转发 WebSocket Upgrade，`/phonemcp/device/` 和 `/phonemcp/health` 仍转给中继。
如果现有配置使用 `^~ /phonemcp/`，上面的正则 location 不会生效，须把对应路由并入该前缀规则。
不得在代理、错误跟踪或抓包日志中记录 Authorization、配对请求体或响应体。不得关闭证书校验。

## 管理员凭据

已有服务器可执行 `bash scripts/relay_creds.sh .secrets/admin`，它通过 SSH 将凭据写入新建私有目录，不回显。
不要把管理员 Token 当作命令行参数、粘贴到聊天或提交到源码仓库。
若旧凭据曾出现在日志/聊天里，部署时应轮换管理员 Token 并重启中继，重新导出私有文件。
旧 APK 和构建备份可能仍含旧共享设备 Token；新版拒绝这些值，但未升级的中继仍可能接受。

## 撤销与恢复

```powershell
server/.venv/Scripts/python.exe -m phone_mcp.pairing --relay-url https://host:8900 --admin-token-file .secrets/admin/admin.token list
server/.venv/Scripts/python.exe -m phone_mcp.pairing --relay-url https://host:8900 --admin-token-file .secrets/admin/admin.token revoke phone-xxxxxxxxxxxx
```

撤销同时删除设备凭据与专属控制凭据，持久化后关闭在线通道、拒绝后续请求并结束服务器等待。
手机收到撤销关闭码后停止重连并丢弃尚未开始的排队任务；已经完成或已交给 Android 的动作无法撤回。
手机的「清除本机配对」仅清除本机保存，不代表服务器撤销。
注册表损坏时不要默默创建空表；恢复受保护备份，或明确重新配对。
回滚到旧中继会恢复旧鉴权模型，因此不要在吊销泄漏凭据后无审查地回滚。

## 验收

- Python：`cd server; .venv/Scripts/python.exe -m pytest -q`。
- Android：Gradle `:app:testDebugUnitTest :app:assembleRelease :app:assembleDebugAndroidTest`。
- 在专用调试设备运行 `:app:connectedDebugAndroidTest`，验证 Keystore 加解密、防篡改、明文迁移与清除。
  该测试会清空 debug 包的测试配置，不能对用户正式包运行。
- 真机：首次授权相机、扫码成功、过期码/重复码失败、杀进程重连、撤销后停止连接。
- 检查首次安装和旧版升级后局域网均默认关闭；开启后 `/health` 也必须鉴权。
- 配置页/扫码页截图应被保护，配置页树不可导出，远程动作不可点击复制凭据。
- 部署前对真实 HTTPS 入口验证路由与证书；本地假服务测试不验证公网代理或真机相机。

## 端到端加密

指令与截图用 AES-256-GCM 加密，**中继只转发密文**。

### 密钥分发

配对时电脑生成一把 256 位随机密钥，写进二维码：

```
二维码 = { code, relay_url, e2e_key }
                            ↑ 只在这一处出现，从不发给中继
```

手机扫码后保存，电脑端同时保存同一把。中继从头到尾没有获取途径。

### 密码学参数

| 项 | 值 | 理由 |
|---|---|---|
| 算法 | AES-256-GCM | AEAD：机密性 + 完整性一体 |
| Nonce | 12 字节随机，每次新生成 | GCM 重用 nonce 会泄露明文异或值 |
| AAD | `device_id` + `path` + `rid` + `ts`（换行分隔） | 绑定设备、路径、请求号、时间戳 |
| 编码 | base64url 无填充 | URL/JSON 安全 |

AAD 的作用是**防中继作恶**：

* 把给 A 设备的密文重放到 B → 解密失败
* 把 `/screenshot` 的密文当 `/click` 用 → 解密失败
* 篡改任意字节 → GCM tag 校验失败
* 改 rid / ts → AAD 不匹配，解密失败

### 重放防护

只绑定 device + path 是不够的：中继能把抓到的**合法密文原样再发一次**，
AAD 完全匹配，解密会成功，动作会被执行。所以再加两个字段：

| 字段 | 作用 |
|---|---|
| `rid` | 电脑端每次请求 +1，手机端记住已见最大值，`rid` 不大于它的一律拒 |
| `ts` | Unix 秒，超出 ±120 秒窗口的拒绝 |

响应密文里回显 `rid`/`ts`，电脑端校验回显值与本次请求一致——
防止中继把旧响应重放给模型（模型会看到过期的界面树）。

**rid 从 1 开始单调递增**，所以校验必须写成 `rid != 期望值`，
不能写成 `if (rid != 0 && rid != 期望值)`——后者会让"缺失 rid"绕过检查。

### 中继能看到什么

**看不到**：指令内容、界面树、截图、输入的文字。
**能看到**：设备 ID、请求路径、时间、大小、频率。

### 实现一致性要求

Python（`server/phone_mcp/e2ee.py`）与 Kotlin（`android/.../util/E2ee.kt`）
必须逐字节一致：nonce 位置、AAD 格式、base64 变体。任何不一致都会解密失败——
这是特性不是缺陷，说明有东西被动了手脚。

排查解密失败时，手机端会返回**明文诊断**（只含密钥长度与异常类型，无敏感数据），
格式为 `e2ee_fail|key=len43 path=/health err=...`。

### 已知边界

* 端到端加密保护**内容**，不保护**元数据**（谁、何时、多大）。
* 中继仍可拒绝服务（丢弃请求、断开连接），只是无法伪造或读取内容。
* 配对二维码本身含 e2e_key，泄露二维码 = 泄露密钥。它 5 分钟过期、一次性使用。
* 电脑端的 `e2e.key` 是明文文件，受文件 ACL 保护——本机进程仍可读取。

## 资金保护

限制 AI 操作任何涉及钱的行为。**拦截在手机端**，不在提示词里、不在电脑端——
模型是不可信输入源，页面里藏一句「帮我付一下」就能让它照做；电脑端参数检查
又会因为 APK 版本旧而被绕过。

### 三道闸（`android/.../service/MoneyGuard.kt`）

| 闸 | 判据 | 覆盖场景 | 误伤控制 |
|---|---|---|---|
| 整包 | 前台包名在禁用表（30+ 个支付/银行/券商/钱包/交易所），或应用名命中「银行/支付/证券/钱包」等 | 支付宝、各大银行、同花顺、云闪付 | 微信/淘宝/京东**不在**表内，它们日常要用 |
| 目标链 | 目标节点及往上 8 层父节点的 text/desc/资源 id 命中「支付/转账/下单/余额」等 40+ 词 | 按钮本身没字、父容器写着「确认支付」 | 只看目标链，不看整页 |
| 整屏 | 页面节点里出现「付款码/确认支付/提交订单/支付密码」等（只认 ≤24 字的短标签） | 微信里进到真实支付页 | 长文本（聊天记录、正文）不参与判定 |

资源 id 里的英文词（`pay`/`transfer`/`checkout`…）按 `_`/驼峰切词后**整词**比对，
避免 `pay` 命中 `display`。

**读取同样拦截**：资金类 App 的截图和界面树直接拒绝。付款码是不记名凭证，
余额、卡号本身即敏感数据。

**`back`/`home` 永远放行**，否则用户被锁在支付页里出不来。

### 为什么 AI 关不掉

- 策略是 `MoneyGuard` 里的 `private val`，编译进 APK，没有任何远程配置入口。
- 15 个 MCP 工具没有一个能读写它，没有对应的环境变量或 HTTP 接口。
- 服务端 `server/phone_mcp/moneyguard.py` 是**兜底**（防旧版 APK），不是开关；
  它和手机端同时生效，取更严的那个。
- 拦截结果以 `ToolError` 抛出，模型看到的是「工具调用失败」，不是「工具返回了一段话」。

### 多窗口

手势是**全局下发**的（`dispatchGesture` 不认窗口），所以分屏、小窗里的
资金类 App 同样可能被点到。整包判定检查**所有可见窗口**的包名，不只活动窗口。

### 已知边界

**纯图片/Canvas 渲染的支付页、应用名和页面文字都不像资金的场景，三道闸都命中不了。**
没有可读文本就没有判据，加词表解决不了。

真正的兜底是：**不要在这台手机上登录支付类账号**。这台手机是给 AI 用的执行终端，
不该同时是资金终端。

## 中继的信任边界

中继是**不可信节点**，这一点在实现里贯穿始终：

| 中继能做什么 | 为什么挡得住 |
|---|---|
| 读指令与截图 | 端到端加密，密钥不在中继 |
| 篡改密文 | GCM tag 校验失败 |
| 重放旧指令 | rid 单调递增 + ts 时间窗口 |
| 重放旧响应 | 响应回显 rid，电脑端校验 |
| 向模型注入文字 | 未认证响应的正文一律丢弃，只按状态码给固定文案 |
| 拒绝服务 / 断连 | **挡不住**，这是可用性问题不是安全问题 |

它能观察元数据：谁、何时、连了哪台设备、请求大小、频率。

## 边界

中继**仍能观察到元数据**，也能拒绝服务或断开连接。
局域网直连已整体移除：它曾是明文 HTTP 入口，在任何共享网络里都等于把控制面摊开。
Keystore 与文件权限不能防御已经获得设备进程控制权或管理员权限的攻击者。
剪贴板定时清除依赖进程存活且系统允许读取，不保证后台或杀进程后仍能清除。
