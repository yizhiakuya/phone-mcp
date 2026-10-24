# 1.1.0 安全与扫码配对验收记录

本地构建：versionCode 31，versionName 1.1.0。
安装包：`dist/phone-mcp-1.1.0.apk`；尚未发布到线上下载地址。
SHA-256：`b13efeebb56ee29cfae215917f8e6ddabfbac5b70b59713e9c52a6942e298df3`。

## 已通过

- Python：57 项测试通过，包含真实本地 HTTP/WebSocket 的配对、一次性兑换、过期、并发兑换、权限隔离、持久化失败和撤销。
- Android JVM：4 项测试通过，包含 HTTPS/WSS 地址校验，以及 Python 生成的二维码像素通过 ZXing 解码。
- Gradle：Release APK、Android 仪器测试 APK 构建通过。
- Lint：0 个错误、63 个警告；未把警告视为零问题。
- APK 签名验证通过，签名主体 CN=Phone MCP。
- 解包检查：新 APK 不含原构建文件中的内置设备 Token；未输出旧凭据。
- 三个变更的运维 Shell 脚本通过 bash -n。
- Windows 私有凭据目录 ACL 已实际创建并检查，保留当前用户、系统/管理员访问。

## 未完成的运行验收

- ADB 未连接真机。只读隐藏模拟器在 QEMU 启动阶段退出（exit 1），未进入 Android，根因未确定。
- Keystore 加解密、防篡改、旧凭据迁移仪器测试仅编译，未运行。
- 未实测相机授权、相机扫码、手机进程重启、界面防截图与真实撤销行为。
- 未部署中继、未修改线上 nginx、未轮换线上管理员凭据、未生成真实配对码。

## 发布约束

先按 `docs/security.md` 完成 HTTPS 路由与中继迁移，再在手机上验收扫码与撤销。
新中继拒绝旧共享设备 Token，已有手机需要重新扫码；不要直接单独发布 APK。
源码与测试不能证明线上传输、权限配置或真机行为已通过。
