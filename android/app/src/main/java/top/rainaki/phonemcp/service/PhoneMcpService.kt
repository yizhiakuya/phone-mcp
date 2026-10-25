package top.rainaki.phonemcp.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import top.rainaki.phonemcp.action.NodeLocator
import top.rainaki.phonemcp.action.NodeRef
import top.rainaki.phonemcp.action.NodeRelocator
import top.rainaki.phonemcp.tree.SnapshotStore
import top.rainaki.phonemcp.tree.TreeSerializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 唯一持有无障碍能力的服务。所有远程动作都从这里执行。
 *
 * 线程模型：HTTP 线程调用同步方法 → 内部 post 到主线程执行 → 用 latch 等结果。
 * 无障碍 API 必须在主线程调用。
 */
class PhoneMcpService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneMcpService"
        private const val LONG_PRESS_MS = 600L

        /** AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT */
        private const val ERROR_INTERVAL_TOO_SHORT = 3
        private const val SCREENSHOT_ATTEMPTS = 3
        private const val SCREENSHOT_RETRY_DELAY_MS = 350L

        /** 单次树遍历的节点上限，防止超大页面把主线程卡到 ANR。 */
        private const val MAX_TREE_NODES = 1500

        @Volatile
        var instance: PhoneMcpService? = null
            private set

        val isConnected: Boolean
            get() = instance != null
    }

    /**
     * 无障碍能力是否真的可用。
     *
     * 服务对象还在，不代表还能读窗口——系统可能因 ANR / 崩溃把无障碍能力禁掉，
     * 此时 instance 非空但 rootInActiveWindow 一直是 null。UI 和 /health 都靠它判断。
     */
    fun hasWindowAccess(): Boolean = runCatching { rootInActiveWindow != null }.getOrDefault(false)

    /** 最近一次事件里的前台包名，供快速查询用。 */
    @Volatile
    private var lastPackage: String? = null

    /** 最近收到的事件类型（诊断用，暴露到 /health）。 */
    private val recentEvents = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val recentTiming = java.util.concurrent.ConcurrentLinkedDeque<String>()

    private fun recordEvent(type: Int) {
        val name = when (type) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> "TOUCH_START"
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> "TOUCH_END"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> "VIEW_LONG_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
            else -> "type_$type"
        }
        recentEvents.addLast(name)
        while (recentEvents.size > 30) recentEvents.removeFirst()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connectedAtMs = System.currentTimeMillis()
        SnapshotStore.clear()
        Log.i(TAG, "accessibility service connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        SnapshotStore.clear()
        Log.i(TAG, "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        SnapshotStore.clear()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        recordEvent(event.eventType)
        // 进事件总线，供 phone_watch 阻塞等待
        runCatching { EventBus.recordFrom(event) }
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                event.packageName?.toString()?.let { lastPackage = it }
        }
    }

    /** 人工接管被触发时的回调（UI 用来提示/刷新）。 */
    @Volatile
    var onHumanOverride: (() -> Unit)? = null


    override fun onInterrupt() = Unit

    // ---------------------------------------------------------------- 查询

    fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /**
     * 当前前台包名。
     *
     * 以实时读取 rootInActiveWindow 为准——lastPackage 是事件缓存，
     * 某些切换（如系统弹层、部分 ROM 的转场）不会触发 WINDOW_STATE_CHANGED，
     * 优先用它会让状态报告过期。
     */
    fun currentPackage(): String? =
        rootInActiveWindow?.packageName?.toString() ?: lastPackage

    fun screenSize(): Rect {
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /** 当前前台窗口标题（部分应用有）。 */
    fun currentWindowTitle(): String? = try {
        windows.firstOrNull { it.isActive }?.title?.toString()
    } catch (t: Throwable) {
        null
    }

    fun dumpTree(maxDepth: Int, includeInvisible: Boolean, withWindows: Boolean): JSONObject {
        val root = rootInActiveWindow
            ?: throw IllegalStateException("无法读取当前窗口（可能被系统弹窗遮挡，或前台是受保护页面）")
        // 配置页也允许读树：界面不渲染任何凭据明文（Token 显示为「已隐藏」），
        // 真正的保护是 FLAG_SECURE 防截屏 + Keystore 加密存储，不是禁止访问。
        val serializer = TreeSerializer(maxDepth, includeInvisible, MAX_TREE_NODES)
        val snapshot = serializer.serialize(
            rootNode = root,
            screen = screenSize(),
            packageName = root.packageName?.toString() ?: currentPackage(),
            windowTitle = currentWindowTitle(),
        )
        SnapshotStore.record(snapshot.refs)
        val out = snapshot.json
        out.put("snapshot", SnapshotStore.latestVersion())
        if (snapshot.truncated) out.put("truncated", true)
        if (withWindows) out.put("windows", listWindows())
        return out
    }

    fun listWindows(): JSONArray {
        val arr = JSONArray()
        for (w in windows) {
            arr.put(JSONObject().apply {
                put("id", w.id)
                put("type", windowTypeName(w.type))
                put("title", w.title?.toString() ?: "")
                put("package", w.root?.packageName?.toString() ?: "")
                put("active", w.isActive)
                put("focused", w.isFocused)
                put("layer", w.layer)
            })
        }
        return arr
    }

    private fun windowTypeName(type: Int): String = when (type) {
        android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
        android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split"
        else -> "type_$type"
    }

    // ---------------------------------------------------------------- 动作

    /** 解析节点：优先快照 id，其次文本/描述/viewId 搜索。 */
    fun resolveNode(
        nodeId: String? = null,
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        className: String? = null,
        requireClickable: Boolean = false,
    ): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        if (!nodeId.isNullOrBlank()) {
            val ref: NodeRef = SnapshotStore.get(nodeId)
                ?: throw IllegalArgumentException("节点 $nodeId 不在最近的快照里，请重新获取界面树")
            NodeRelocator.relocate(root, ref)?.let { return it }
            // 指纹对不上，继续尝试用文本兜底
            val fallback = NodeLocator.search(
                root,
                text = ref.text,
                desc = ref.desc,
                viewId = ref.viewId,
                requireClickable = requireClickable,
            )
            if (fallback != null) return fallback.node
            throw IllegalStateException("节点 $nodeId 已失效（界面已变化），请重新获取界面树")
        }

        if (text == null && desc == null && viewId == null) return null
        return NodeLocator.search(
            root = root,
            text = text,
            desc = desc,
            viewId = viewId,
            className = className,
            requireClickable = requireClickable,
            exact = true,
        )?.node
            ?: NodeLocator.search(
                root = root,
                text = text,
                desc = desc,
                viewId = viewId,
                className = className,
                requireClickable = requireClickable,
                exact = false,
            )?.node
            // 最后兜底：调用方常分不清某个字符串是 text 还是 contentDescription，
            // 两者互相替换再试一轮，避免"明明看得见却找不到"。
            ?: NodeLocator.search(
                root = root,
                text = desc ?: text,
                desc = text ?: desc,
                viewId = viewId,
                className = className,
                requireClickable = requireClickable,
                exact = true,
            )?.node
            ?: NodeLocator.search(
                root = root,
                text = desc ?: text,
                desc = text ?: desc,
                viewId = viewId,
                className = className,
                requireClickable = requireClickable,
                exact = false,
            )?.node
    }

    /**
     * 点击节点。返回 null 表示节点本身点不了，需要调用方用坐标手势兜底。
     *
     * 刻意不在内部兜底：手势要等回调，若本方法正跑在主线程会死锁。
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean? {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            cur = cur.parent
        }
        return null
    }

    /** 长按节点。返回 null 表示需要调用方用坐标手势兜底。 */
    fun longClickNode(node: AccessibilityNodeInfo): Boolean? {
        var cur: AccessibilityNodeInfo? = node
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isLongClickable && cur.isEnabled) {
                return cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
            }
            cur = cur.parent
        }
        return null
    }

    /** 在节点位置执行长按手势。后台线程调用。 */
    fun longPressAt(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty) return false
        return dispatchGestureBlocking(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(
                    pathTo(rect.centerX().toFloat(), rect.centerY().toFloat()),
                    0,
                    LONG_PRESS_MS,
                )
            ).build()
        )
    }

    /** 节点中心坐标，供调用方做手势兜底。 */
    fun centerOf(node: AccessibilityNodeInfo): Pair<Float, Float>? {
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.isEmpty) return null
        return rect.centerX().toFloat() to rect.centerY().toFloat()
    }

    /**
     * 写入文本。
     *
     * 先试 ACTION_SET_TEXT。**不能**用它是否生效来推断成败：
     * 该动作是异步的，performAction 返回 true 后 node.text 往往还没刷新，
     * 按旧值判断会误判成失败、再走一遍粘贴，导致文字重复（"测试"变"测试测试"）。
     *
     * 所以：performAction 返回 true 就认为成功；返回 false 才降级粘贴。
     */
    fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return true
        }
        // 只有 ACTION_SET_TEXT 明确失败（自定义输入框常这样）才走剪贴板
        return pasteInto(node, value)
    }

    private fun pasteInto(node: AccessibilityNodeInfo, value: String): Boolean {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = cm.primaryClip
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("phone-mcp", value))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) false else {
                // 全选替换语义：粘贴后把原有内容清掉不现实，这里只保证新内容追加/替换成功
                true
            }
        } finally {
            if (previous != null) {
                runCatching { cm.setPrimaryClip(previous) }
            }
        }
    }

    fun tapPoint(x: Float, y: Float): Boolean = dispatchGestureBlocking(
        GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(pathTo(x, y), 0, 60)
        ).build()
    )

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatchGestureBlocking(
            GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(50, 5000))
            ).build()
        )
    }

    /** 在可滚动节点上滚动；没有节点时退化为屏幕中部滑动。 */
    /**
     * 在可滚动节点上执行 ACTION_SCROLL_*。返回 null 表示节点滚不动，
     * 需要调用方改用手势。**必须在主线程调用**（NodeInfo 有线程亲和性）。
     */
    fun scrollNode(node: AccessibilityNodeInfo?, direction: String): Boolean? {
        if (node == null || !node.isScrollable) return null
        val forward = direction.lowercase() !in setOf("back", "backward", "up", "left")
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (node.performAction(action)) true else null
    }

    /** 用屏幕中部滑动手势滚动。**后台线程调用**（内部会等手势回调）。 */
    fun scrollByGesture(direction: String, amount: Float): Boolean {
        val screen = screenSize()
        val cx = screen.exactCenterX()
        val cy = screen.exactCenterY()
        val dy = screen.height() * amount.coerceIn(0.1f, 0.9f) / 2f
        return when (direction.lowercase()) {
            "up" -> swipe(cx, cy + dy, cx, cy - dy, 260)
            "down" -> swipe(cx, cy - dy, cx, cy + dy, 260)
            "left" -> swipe(cx + dy, cy, cx - dy, cy, 260)
            "right" -> swipe(cx - dy, cy, cx + dy, cy, 260)
            else -> swipe(cx, cy + dy, cx, cy - dy, 260)
        }
    }

    fun globalAction(action: String): Boolean {
        val code = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications", "notification_shade" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> GLOBAL_ACTION_LOCK_SCREEN
            "take_screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return performGlobalAction(code)
    }

    // ---------------------------------------------------------------- 截图

    /**
     * 同步等待截图结果。
     *
     * 注意：本方法**不能**在 `callOnMain` 里调用——`latch.await()` 会阻塞主线程，
     * 而 TakeScreenshotCallback 恰好投递到主线程，形成死锁（表现为超时）。
     * 调用方必须在后台线程直接调用它（HTTP / WS 工作线程即可）。
     *
     * Android 对截图有频率限制（ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT = 3），
     * 连续调用会失败，所以这里做短暂退避重试。
     */
    fun takeScreenshotBlocking(maxWidth: Int?): Bitmap? {
        var lastError: String? = null
        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            val bitmap = takeScreenshotOnce(maxWidth, onRetryable = { code ->
                lastError = "截图失败，code=$code"
                code == ERROR_INTERVAL_TOO_SHORT
            })
            if (bitmap != null) return bitmap
            if (attempt < SCREENSHOT_ATTEMPTS - 1) {
                Thread.sleep(SCREENSHOT_RETRY_DELAY_MS)
            }
        }
        throw IllegalStateException(lastError ?: "截图失败")
    }

    /** 单次截图。onRetryable 返回 true 表示该错误值得重试。 */
    private fun takeScreenshotOnce(
        maxWidth: Int?,
        onRetryable: (Int) -> Boolean,
    ): Bitmap? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Bitmap?>(null)
        val error = AtomicReference<String?>(null)
        val retryable = AtomicReference(false)

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    // 回调在主线程。先把硬件缓冲区转成软件位图（轻量，只做拷贝），
                    // 再把缩放这类重活挪到后台线程，避免卡 UI / ANR。
                    val buffer: HardwareBuffer = screenshot.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                    val raw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    buffer.close()

                    if (raw == null) {
                        error.set("截图解码失败")
                        latch.countDown()
                        return
                    }

                    Thread {
                        try {
                            var soft: Bitmap = raw
                            if (maxWidth != null && soft.width > maxWidth) {
                                val scale = maxWidth.toFloat() / soft.width
                                val scaled = Bitmap.createScaledBitmap(
                                    soft,
                                    maxWidth,
                                    (soft.height * scale).toInt().coerceAtLeast(1),
                                    true,
                                )
                                if (scaled != soft) soft.recycle()
                                soft = scaled
                            }
                            result.set(soft)
                        } catch (t: Throwable) {
                            raw.recycle()
                            error.set(t.message ?: t.javaClass.simpleName)
                        } finally {
                            latch.countDown()
                        }
                    }.start()
                }

                override fun onFailure(errorCode: Int) {
                    retryable.set(onRetryable(errorCode))
                    error.set("截图失败，code=$errorCode")
                    latch.countDown()
                }
            }
        )

        if (!latch.await(8, TimeUnit.SECONDS)) {
            throw IllegalStateException("截图超时（8s）")
        }
        if (result.get() == null) {
            // 只有可重试的错误才吞掉，其它直接抛
            if (!retryable.get()) {
                throw IllegalStateException(error.get() ?: "截图失败")
            }
        }
        return result.get()
    }

    // ---------------------------------------------------------------- 等待

    /** 等到界面稳定（连续两次树指纹相同）或超时。 */
    /**
     * 等到界面稳定（连续 quietMs 内树指纹不变）或超时。
     *
     * **必须在后台线程调用**：内部靠 sleep 轮询，放主线程会 ANR，
     * 进而被系统判定无障碍服务异常并自动关闭。每次取指纹单独切主线程。
     */
    fun waitForIdle(timeoutMs: Long, quietMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSignature = ""
        var stableSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val sig = try {
                callOnMain(timeoutMs = 3_000) { treeSignature() }
            } catch (t: Throwable) {
                // 取指纹失败（如窗口正切换）不算致命，继续等
                ""
            }
            val now = System.currentTimeMillis()
            if (sig.isNotEmpty() && sig == lastSignature) {
                if (stableSince == 0L) stableSince = now
                if (now - stableSince >= quietMs) return true
            } else {
                lastSignature = sig
                stableSince = 0L
            }
            Thread.sleep(60)
        }
        return false
    }

    /**
     * 等到界面上出现（或消失）指定文本。**必须在后台线程调用**。
     *
     * 比 `waitForIdle` 有用得多：点完「发送」之后，界面可能一直在动
     * （转圈、动画），但真正该等的是「发送成功」这几个字出现。
     *
     * 判定基于无障碍树的可见节点文本，不看像素。
     *
     * @param text 目标文本，子串匹配
     * @param disappear true = 等它消失
     * @return true = 条件在超时前满足
     */
    fun waitForText(
        text: String,
        disappear: Boolean = false,
        timeoutMs: Long,
        pollMs: Long = 150,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = runCatching {
                callOnMain(timeoutMs = 3_000) { treeContainsText(text) }
            }.getOrDefault(false)
            if (found != disappear) return true
            Thread.sleep(pollMs)
        }
        return false
    }

    /** 当前可见树里是否含指定文本。**必须在主线程调用**。 */
    private fun treeContainsText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TREE_NODES) {
            val n = queue.removeFirst()
            visited++
            if (!n.isVisibleToUser) continue
            if (n.text?.toString()?.contains(text) == true) return true
            if (n.contentDescription?.toString()?.contains(text) == true) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun treeSignature(): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < 800) {
            val (node, depth) = queue.removeFirst()
            visited++
            val rect = Rect().also { node.getBoundsInScreen(it) }
            sb.append(depth).append(':')
                .append(node.className).append('|')
                .append(node.text).append('|')
                .append(node.contentDescription).append('|')
                .append(rect.flattenToString()).append(';')
            val n = node.childCount
            for (i in 0 until n) {
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return sb.toString()
    }

    /**
     * 动作后的效果校验：当前界面指纹。**必须在主线程调用**。
     *
     * 用来判断"这一下到底有没有反应"。点了空白处、点偏了、目标被遮挡，
     * 手势都会返回成功（手指确实碰了屏幕），但界面毫无变化——
     * 不校验的话调用方会以为做成了。
     *
     * 指纹只含类名/文本/坐标，不含像素，成本远低于截图。
     */
    fun signatureSnapshot(): String = treeSignature()

    /**
     * 坐标处最深的节点，以及它（或祖先）是否可点击。
     *
     * 用于点击前的落点体检：点在两列图标之间的空隙里，返回的节点是容器
     * 而非图标，此时提示调用方"这个坐标下没有可点目标"。
     */
    fun hitTest(x: Float, y: Float): Pair<AccessibilityNodeInfo?, Boolean> {
        val root = rootInActiveWindow ?: return null to false
        val point = Rect(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1)
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val n = queue.removeFirst()
            visited++
            val rect = Rect()
            n.getBoundsInScreen(rect)
            if (rect.contains(point)) {
                val area = rect.width().toLong() * rect.height()
                if (area in 0 until bestArea) {
                    bestArea = area
                    best = n
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        // 沿父链找可点击祖先
        var cur = best
        var guard = 0
        while (cur != null && guard++ < 8) {
            if (cur.isClickable && cur.isEnabled) return best to true
            cur = cur.parent
        }
        return best to false
    }

    // ---------------------------------------------------------------- 线程桥

    /**
     * 无障碍 API 必须在主线程调用，而 HTTP 请求跑在 nanohttpd 的工作线程上。
     * 这里统一把调用切到主线程并同步等结果，异常原样抛回调用方。
     */
    fun <T> callOnMain(timeoutMs: Long = 15_000, block: () -> T): T {
        if (android.os.Looper.myLooper() == mainLooper) return block()

        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<T>?>(null)
        val posted = android.os.Handler(mainLooper).post {
            result.set(runCatching(block))
            latch.countDown()
        }
        if (!posted) throw IllegalStateException("无法投递到主线程")
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            mainTimeouts.incrementAndGet()
            throw IllegalStateException("主线程执行超时（${timeoutMs}ms），可能有系统弹窗阻塞")
        }
        return result.get()!!.getOrThrow()
    }

    /** 主线程超时次数。持续增长说明有无障碍调用卡死主线程，需要排查。 */
    private val mainTimeouts = java.util.concurrent.atomic.AtomicLong(0)

    /** 供 /health 暴露的诊断信息。 */
    fun diagnostics(): JSONObject = JSONObject().apply {
        put("main_timeouts", mainTimeouts.get())
        put("window_count", runCatching { windows.size }.getOrDefault(0))
        put("root_available", rootInActiveWindow != null)
        put("service_connected_at", connectedAtMs)
        put("stopped", HumanOverride.isStopped())
        put("stopped_for_ms", HumanOverride.lockedForMs())
        put("action_running", HumanOverride.actionRunning)
        put("recent_events", org.json.JSONArray(recentEvents.toList()))
        put("recent_timing", org.json.JSONArray(recentTiming.toList()))
    }

    private var connectedAtMs: Long = 0L

    // ---------------------------------------------------------------- 内部

    private fun pathTo(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    /**
     * 执行手势并同步等结果。
     *
     * **不能包在 callOnMain 里**：这里 `latch.await()` 会阻塞调用线程，
     * 而 GestureResultCallback 投递到主线程。若调用线程就是主线程，
     * 回调永远排不上 → 5s 后误判失败（此前 tap/swipe 失败的根因）。
     */
    private fun dispatchGestureBlocking(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val success = AtomicReference(false)

        // dispatchGesture 本身要求主线程调用，单独切一次，不等结果。
        val dispatched = try {
            callOnMain(timeoutMs = 3_000) {
                dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            success.set(true)
                            latch.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            success.set(false)
                            latch.countDown()
                        }
                    },
                    null,
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "dispatchGesture failed", t)
            false
        }
        if (!dispatched) return false

        // 在当前（后台）线程等回调，不占主线程
        if (!latch.await(5, TimeUnit.SECONDS)) {
            Log.w(TAG, "gesture callback timeout")
            return false
        }

        // 手势执行期间用户可能点了中断条 —— 此时结果作废，立刻中断
        if (HumanOverride.isStopped()) {
            Log.i(TAG, "user stopped during gesture, aborting")
            throw UserOperatingException(HumanOverride.lockedForMs())
        }
        return success.get()
    }

}
