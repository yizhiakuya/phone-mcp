# 线程模型

这个 App 同时踩了 Android 两个最难缠的约束：无障碍 API 有**线程亲和性**，而远程请求必须**并发**。两者交叉的地方最容易写出死锁，之前几个 bug 全在这里。这份文档是设计约定，改代码前先读。

## 四条线程

| 线程 | 谁在跑 | 能做什么 | 不能做什么 |
|---|---|---|---|
| **主线程** | UI + 无障碍 API | 一切 `AccessibilityNodeInfo` 操作、`dispatchGesture`、`takeScreenshot`、`performGlobalAction` | 任何阻塞等待（sleep / latch.await / 网络 / 大计算） |
| **WS 读线程**（OkHttp） | 收 WebSocket 消息 | 解析 JSON，把任务丢进 worker | 执行动作 |
| **action worker**（单线程池） | 执行远程动作 | 调 `callOnMain`、等手势/截图回调、压缩图片 | 直接碰 NodeInfo |
| **IO / 后台线程** | 下载、MD5、缩放 | 纯计算与磁盘 | 碰无障碍对象 |

## 核心规则

### 1. NodeInfo 只在主线程用

`AccessibilityNodeInfo` 的 `performAction` / `getChild` / 属性读取都必须在主线程。跨线程传引用是未定义行为。

所以凡是"先找节点、再操作节点"的动作，拆成**两阶段**：

```kotlin
// 阶段 1：主线程 —— 定位 + 直接动作
val direct = svc.callOnMain {
    val node = svc.resolveNode(...)
    svc.clickNode(node)          // 返回 Boolean?，null = 需要兜底
}

// 阶段 2：后台线程 —— 手势兜底
val ok = direct ?: svc.tapPoint(cx, cy)
```

`click` / `longClick` / `scroll` 都是这个形状。新增动作照抄。

### 2. 绝不在主线程等主线程

凡是"回调投递到主线程"的 API，都不能在 `callOnMain` 里 `await`：

| API | 回调去哪 | 正确做法 |
|---|---|---|
| `takeScreenshot` | `mainExecutor` | `dispatch` 后回后台线程 `latch.await` |
| `dispatchGesture` | 主线程 | 同上 |

错误写法（死锁，5~15 秒后误判失败）：

```kotlin
svc.callOnMain { svc.tapPoint(...) }   // ✗ tapPoint 内部 latch.await
```

正确写法：

```kotlin
svc.tapPoint(...)                       // ✓ 内部自己切主线程，在当前线程等回调
```

### 3. 动作执行不能占用 WS 读线程

`TunnelClient.handleMessage` 只负责解析，立刻 `worker.execute { ... }`。否则一个动作卡住（比如手势等超时），整条连接的消息都堵住，中继侧表现为 `inflight` 一直不归零。

worker 是**单线程**：界面操作本来就不该并发，串行还能保证顺序。

### 4. 主线程上禁止长循环

`waitForIdle` 曾经在主线程 `Thread.sleep` 轮询，最长 30 秒 → ANR → **系统自动禁用无障碍服务**。

现在：在调用线程循环，每次取树指纹单独 `callOnMain`。树遍历也有 1500 节点上限（`MAX_TREE_NODES`），防止超大页面拖死主线程。

## 已有的保护

- `callOnMain(timeout)`：超时抛异常并累加 `mainTimeouts` 计数，`/health` 的 `diag.main_timeouts` 可查
- `hasWindowAccess()`：服务绑着但读不到窗口（系统禁用后）能被 UI 和 health 识别
- 手势 / 截图：内部自带超时与重试，不向外抛原始异常
- 重连：`AtomicBoolean` 保证同时只有一条重连链；连接存活 >10s 才重置退避

## 排查信号

| 现象 | 大概率原因 |
|---|---|
| 无障碍被系统自动关闭 | 主线程阻塞 → ANR（规则 4） |
| 动作 5 秒后返回失败 | 在主线程等主线程回调（规则 2） |
| `inflight` 不归零 | 动作占着 WS 读线程（规则 3） |
| `diag.main_timeouts` 持续增长 | 某个主线程操作在卡 |
| 界面读到了但点不动 | 跨线程用了 NodeInfo（规则 1） |
