package top.rainaki.phonemcp.http

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import top.rainaki.phonemcp.action.NodeLocator
import top.rainaki.phonemcp.service.EventBus
import top.rainaki.phonemcp.service.HumanOverride
import top.rainaki.phonemcp.service.MoneyGuard
import top.rainaki.phonemcp.service.PhoneMcpService
import java.io.ByteArrayOutputStream

/**
 * 动作分发。所有方法都在 HTTP 线程上被调用，内部统一切到主线程。
 */
class DeviceApi {

    private val service: PhoneMcpService?
        get() = PhoneMcpService.instance

    private fun requireService(): PhoneMcpService =
        service ?: throw IllegalStateException(
            "无障碍服务未连接。请在手机「设置 → 无障碍」中开启 Phone MCP 控制服务"
        )

    /**
     * 动作前统一检查：用户中断 + 资金保护。
     *
     * 设置页**不拦截**：界面上不显示任何凭据明文（Token 显示为「已隐藏」，
     * 设备 ID 只是标识），用「禁止操作」来保护是把代价转嫁给用户。
     * 真正该做的是敏感信息加密存储、不渲染到界面/无障碍树，这一点已经做到。
     *
     * 资金保护必须在主线程判定（要读 rootInActiveWindow 和 NodeInfo），
     * 所以这里切一次主线程。每次动作多一次 15ms 级的主线程往返，
     * 相比"误付一笔钱"，这个代价可以忽略。
     */
    private fun requireControl(keyAction: String? = null, readOnly: Boolean = false): PhoneMcpService {
        val svc = requireService()
        HumanOverride.check()
        svc.callOnMain(timeoutMs = 5_000) { MoneyGuard.enforceBasic(svc, keyAction, readOnly) }
        return svc
    }

    /**
     * 在"动作执行中"标记下运行一段逻辑。
     *
     * 动作期间产生的事件无法区分是系统触发还是用户触发，一律忽略；
     * 动作结束后才开始监听用户操作。这样避免自己的滑动被误判成接管。
     *
     * @param step 模型填的这一步说明，显示在底部条上。**只作展示**，
     *   模型可能写一句好听的描述却干别的事——硬约束是 [MoneyGuard]。
     */
    private inline fun <T> duringAction(step: String?, block: () -> T): T {
        HumanOverride.beginAction()
        // 动作开始时亮出底部条。用 callOnMain 同步加窗口——动作只有
        // 200~300ms，异步 post 会让窗口来不及出现。
        val svc = service
        val sb = top.rainaki.phonemcp.PhoneMcpApp.instance.stopButton
        if (svc != null && sb != null) {
            runCatching { svc.callOnMain(timeoutMs = 1000) { sb.show(step) } }
        }
        return try {
            block()
        } finally {
            HumanOverride.endAction()
            // 中断状态下立即隐藏，不等到延迟；否则会一直显示"正在操作"
            if (HumanOverride.isStopped()) sb?.hide() else sb?.hideSoon()
        }
    }

    /** 从请求体里取步骤说明。读取类动作用它刷新底部条（不新建窗口）。 */
    private fun announceStep(body: JSONObject) {
        val step = body.optString("step").ifBlank { null } ?: return
        top.rainaki.phonemcp.PhoneMcpApp.instance.stopButton?.announce(step)
    }

    private fun stepOf(body: JSONObject): String? =
        body.optString("step").ifBlank { null }

    /**
     * 动作后的效果校验：等一小会儿，比较界面指纹有没有变。
     *
     * 手势返回成功只代表"手指确实在屏幕上动了"，不代表界面响应了。
     * 点空白处、点偏、目标被遮挡、应用卡住，都会静默失败。
     * 这里给出可判断的信号，避免调用方"以为做成了"继续往下走。
     *
     * @return true = 界面有变化；false = 看不出变化（不代表一定失败，比如
     *   只是弹了个 toast，或页面本来就在动画中途）
     */
    private fun effectChanged(before: String, waitMs: Long = 400): Boolean {
        if (before.isEmpty()) return true
        val svc = service ?: return true
        runCatching { Thread.sleep(waitMs) }
        val after = runCatching { svc.callOnMain(timeoutMs = 3_000) { svc.signatureSnapshot() } }
            .getOrDefault("")
        if (after.isEmpty()) return true
        return after != before
    }

    // ------------------------------------------------------------- 查询

    fun health(): JSONObject = JSONObject().apply {
        val svc = service
        put("ok", true)
        put("service_connected", svc != null)
        put("sdk", android.os.Build.VERSION.SDK_INT)
        put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        put("version_code", top.rainaki.phonemcp.BuildConfig.VERSION_CODE)
        put("version_name", top.rainaki.phonemcp.BuildConfig.VERSION_NAME)
        if (svc != null) {
            val screen = svc.screenSize()
            put("screen", JSONObject().put("w", screen.width()).put("h", screen.height()))
            put("package", svc.currentPackage() ?: "")
            // 诊断：main_timeouts 持续增长说明有无障碍调用卡主线程
            put("diag", svc.diagnostics())
            put("stopped", HumanOverride.isStopped())
            put("stop_remaining_ms", HumanOverride.remainingMs())
            // 资金保护策略摘要：确认手机端装的是带 MoneyGuard 的版本
            put("money_guard", MoneyGuard.policySummary())
        }
    }

    /** 人工操作状态查询。没有"恢复"操作——用户停手后自然放行。 */
    fun overrideStatus(): JSONObject = JSONObject().apply {
        put("stopped", HumanOverride.isStopped())
        put("stop_remaining_ms", HumanOverride.remainingMs())
        put("blocked_count", HumanOverride.blockedCount())
    }

    fun tree(maxDepth: Int, includeInvisible: Boolean, withWindows: Boolean): JSONObject {
        val svc = requireService()
        // rootInActiveWindow 有线程亲和性，判定要回主线程
        svc.callOnMain(timeoutMs = 5_000) { MoneyGuard.enforceRead(svc) }
        return svc.callOnMain { svc.dumpTree(maxDepth, includeInvisible, withWindows) }
    }

    /** 带步骤说明的读树：用于 phone_observe / phone_ui。 */
    fun tree(body: JSONObject): JSONObject {
        announceStep(body)
        return tree(
            maxDepth = body.optInt("max_depth", 30).coerceIn(1, 200),
            includeInvisible = body.optBoolean("include_invisible", false),
            withWindows = body.optBoolean("windows", false),
        )
    }

    fun windows(): JSONObject {
        val svc = requireService()
        return JSONObject().put("windows", svc.callOnMain { svc.listWindows() })
    }

    // ------------------------------------------------------------- 动作

    fun click(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        val svc = requireControl()

        // 阶段 1（主线程）：定位节点 + 资金保护 + 尝试 ACTION_CLICK
        val located = svc.callOnMain {
            val node = svc.resolveNode(
                nodeId = body.optString("node_id").ifBlank { null },
                text = body.optString("text").ifBlank { null },
                desc = body.optString("desc").ifBlank { null },
                viewId = body.optString("view_id").ifBlank { null },
                className = body.optString("class_name").ifBlank { null },
                requireClickable = body.optBoolean("clickable_only", false),
            ) ?: throw NoSuchElementException("没找到匹配的节点")
            MoneyGuard.enforceTarget(node)
            Triple(node, node.describeForLog(), svc.clickNode(node))
        }
        val (node, target, direct) = located

        // 阶段 2（后台线程）：节点不可点时用坐标手势兜底。
        // 不能放在上面的主线程块里 —— 手势要等回调，主线程等会死锁。
        val ok = direct ?: run {
            val center = svc.centerOf(node)
            if (center == null) false else svc.tapPoint(center.first, center.second)
        }
        return JSONObject().apply {
            put("ok", ok)
            if (!ok) put("error", "节点找到了但点击失败")
            put("target", target)
        }
    }

    fun longClick(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        val svc = requireControl()
        val located = svc.callOnMain {
            val node = svc.resolveNode(
                nodeId = body.optString("node_id").ifBlank { null },
                text = body.optString("text").ifBlank { null },
                desc = body.optString("desc").ifBlank { null },
                viewId = body.optString("view_id").ifBlank { null },
            ) ?: throw NoSuchElementException("没找到匹配的节点")
            MoneyGuard.enforceTarget(node)
            node to svc.longClickNode(node)
        }
        val (node, direct) = located
        val ok = direct ?: svc.longPressAt(node)
        return JSONObject().put("ok", ok)
    }

    fun tap(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        val svc = requireControl()
        val x = body.optDouble("x", Double.NaN)
        val y = body.optDouble("y", Double.NaN)
        require(!x.isNaN() && !y.isNaN()) { "需要 x 和 y" }

        // 落点体检：坐标下面是容器（两列图标之间的空隙）而不是可点目标时，
        // 手势会"成功"但界面毫无反应，调用方却以为做成了。
        // 这里先看一眼，命中可点目标就继续；否则也照点（可能是 Canvas/游戏），
        // 但在返回里明确标注，让调用方知道"点了，但大概率没点到东西"。
        val hit = svc.callOnMain(timeoutMs = 5_000) {
            val root = svc.activeRoot()
            if (root != null) MoneyGuard.enforceTarget(MoneyGuard.nodeAt(root, x.toFloat(), y.toFloat()))
            svc.hitTest(x.toFloat(), y.toFloat())
        }
        val hitLabel = hit.first?.let { n ->
            (n.text ?: n.contentDescription)?.toString()?.takeIf { it.isNotBlank() }
        }

        // tapPoint 内部已自行切主线程并等待手势回调，不能再包 callOnMain（会死锁）
        val before = runCatching { svc.callOnMain(timeoutMs = 3_000) { svc.signatureSnapshot() } }
            .getOrDefault("")
        val ok = svc.tapPoint(x.toFloat(), y.toFloat())
        return JSONObject().apply {
            put("ok", ok)
            if (ok && !hit.second) {
                put("warning", "该坐标下没有可点击的控件（可能点在了空白处）")
            }
            if (hitLabel != null) put("hit", hitLabel)
            // 手势成功但界面没变化 —— 说出来，别让调用方以为做成了
            if (ok && !effectChanged(before)) {
                put("no_effect", true)
            }
        }
    }

    fun swipe(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        val svc = requireControl()
        val x1 = body.optDouble("x1", Double.NaN)
        val y1 = body.optDouble("y1", Double.NaN)
        val x2 = body.optDouble("x2", Double.NaN)
        val y2 = body.optDouble("y2", Double.NaN)
        require(!x1.isNaN() && !y1.isNaN() && !x2.isNaN() && !y2.isNaN()) { "需要 x1,y1,x2,y2" }
        val duration = body.optLong("duration_ms", 300)
        return JSONObject().put(
            "ok",
            svc.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), duration)
        )
    }

    fun scroll(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        // 滚动不改钱，资金页上也放行——否则「服务」页连找「城市服务」都做不到
        val svc = requireControl(readOnly = true)
        val direction = body.optString("direction", "up")
        val amount = body.optDouble("amount", 0.6).toFloat()

        // 阶段 1（主线程）：定位节点并尝试 ACTION_SCROLL_*
        // NodeInfo 有线程亲和性，不能拿到别的线程再 performAction。
        val direct = svc.callOnMain {
            val node = runCatching {
                svc.resolveNode(
                    nodeId = body.optString("node_id").ifBlank { null },
                    text = body.optString("text").ifBlank { null },
                    desc = body.optString("desc").ifBlank { null },
                    viewId = body.optString("view_id").ifBlank { null },
                )
            }.getOrNull()
            MoneyGuard.enforceTarget(node)
            svc.scrollNode(node, direction)
        }

        // 阶段 2（后台线程）：节点滚不动就用屏幕中部手势
        val ok = direct ?: svc.scrollByGesture(direction, amount)
        return JSONObject().put("ok", ok)
    }

    fun setText(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        val svc = requireControl()
        val value = body.optString("value", "")
        return svc.callOnMain {
            val node = svc.resolveNode(
                nodeId = body.optString("node_id").ifBlank { null },
                text = body.optString("text").ifBlank { null },
                desc = body.optString("desc").ifBlank { null },
                viewId = body.optString("view_id").ifBlank { null },
                className = body.optString("class_name").ifBlank { null },
            ) ?: throw NoSuchElementException("没找到输入框")
            MoneyGuard.enforceTarget(node)
            JSONObject().apply {
                put("ok", svc.setText(node, value))
                put("value", value)
            }
        }
    }

    fun key(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        val action = body.optString("action")
        require(action.isNotBlank()) { "需要 action" }
        // back / home 在资金页也放行，否则用户被锁在支付页里出不来
        val svc = requireControl(keyAction = action)
        return svc.callOnMain { JSONObject().put("ok", svc.globalAction(action)) }
    }

    fun waitIdle(body: JSONObject): JSONObject {
        val svc = requireService()
        val timeout = body.optLong("timeout_ms", 5000)
        val quiet = body.optLong("quiet_ms", 400)
        return JSONObject().put("stable", svc.waitForIdle(timeout, quiet))
    }

    // ------------------------------------------------------------- 事件

    /**
     * 阻塞等待一条匹配的无障碍事件。
     *
     * 手机端没有事件时线程真正挂起，不占 CPU；超时返回 matched=false。
     * 这是「监听」而非「轮询」的关键——模型不需要反复调用 observe。
     */
    fun watch(body: JSONObject): JSONObject {
        requireService()
        HumanOverride.check()
        val timeout = body.optLong("timeout_ms", 30_000).coerceIn(500, 120_000)
        val since = body.optLong("since_id", EventBus.currentId())
        val pkg = body.optString("package").ifBlank { null }
        val text = body.optString("text").ifBlank { null }
        val types = body.optJSONArray("types")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }.toSet()
        } ?: emptySet()

        val hit = EventBus.await(
            afterId = since,
            pkg = pkg,
            textContains = text,
            types = types,
            timeoutMs = timeout,
        )
        return JSONObject().apply {
            put("matched", hit != null)
            if (hit != null) {
                put("event", JSONObject().apply {
                    put("id", hit.id)
                    put("type", hit.type)
                    put("package", hit.pkg ?: "")
                    put("text", hit.text ?: "")
                    put("at", hit.at)
                })
            } else {
                // 超时也把这段时间攒下的事件带回去，模型据此判断"到底发生了什么"
                val recent = EventBus.since(since, limit = 10)
                put("recent_count", recent.size)
                put("recent", org.json.JSONArray(recent.map { e ->
                    JSONObject().apply {
                        put("type", e.type)
                        put("package", e.pkg ?: "")
                        put("text", e.text ?: "")
                    }
                }))
            }
        }
    }

    /** 语义条件等待：等文本出现/消失。 */
    fun waitUntil(body: JSONObject): JSONObject {
        val svc = requireService()
        HumanOverride.check()
        val text = body.optString("text").ifBlank { null }
            ?: throw IllegalArgumentException("需要 text")
        val disappear = body.optBoolean("disappear", false)
        val timeout = body.optLong("timeout_ms", 5000).coerceIn(200, 60_000)
        val ok = svc.waitForText(text, disappear, timeout)
        return JSONObject().apply {
            put("satisfied", ok)
            put("text", text)
            put("disappear", disappear)
        }
    }

    // ------------------------------------------------------------- 截图

    fun screenshot(body: JSONObject): ByteArray {
        val svc = requireService()
        announceStep(body)
        svc.callOnMain(timeoutMs = 5_000) { MoneyGuard.enforceRead(svc) }
        val maxWidth = body.optInt("max_width", 0).takeIf { it > 0 }
        val quality = body.optInt("quality", 80).coerceIn(30, 100)
        // 不能包 callOnMain：takeScreenshotBlocking 内部 await 会阻塞主线程，
        // 而回调恰好投递到主线程 —— 会死锁成超时。
        val bitmap: Bitmap = svc.takeScreenshotBlocking(maxWidth)
            ?: throw IllegalStateException("截图失败")
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    // ------------------------------------------------------------- 文字识别

    /**
     * 截图 → OCR → 带坐标的文字行。
     *
     * 用于无障碍树读不到内容的界面（游戏、Canvas、Flutter 自绘）。
     * 返回的坐标已经是**屏幕原生坐标**，可直接用于 phone_tap。
     */
    fun ocr(body: JSONObject): JSONObject {
        val svc = requireService()
        svc.callOnMain(timeoutMs = 5_000) { MoneyGuard.enforceRead(svc) }
        // 识别不需要缩小：缩了反而降低准确率，且坐标换算容易出错。
        // 只限制最大宽度，避免超大截图拖慢识别。
        val maxWidth = body.optInt("max_width", 0).takeIf { it > 0 }
        val bitmap = svc.takeScreenshotBlocking(maxWidth)
            ?: throw IllegalStateException("截图失败")

        val screen = svc.screenSize()
        // takeScreenshotBlocking 可能缩小了图片，坐标要换算回屏幕原生
        val scale = if (bitmap.width > 0) screen.width().toFloat() / bitmap.width else 1f

        val lines = try {
            top.rainaki.phonemcp.capture.Ocr.recognize(bitmap, scale)
        } finally {
            bitmap.recycle()
        }

        val arr = JSONArray()
        for (l in lines) {
            arr.put(JSONObject().apply {
                put("t", l.text)
                put("b", JSONArray().put(l.bounds.left).put(l.bounds.top)
                    .put(l.bounds.right).put(l.bounds.bottom))
            })
        }
        return JSONObject().apply {
            put("ok", true)
            put("count", lines.size)
            put("lines", arr)
            put("screen", JSONObject().put("w", screen.width()).put("h", screen.height()))
        }
    }

    /**
     * 按文字点击：OCR 找到目标文字，点它的中心。
     *
     * 这是 OCR 真正有用的地方——把「看得见但点不到」变成「按文本点」。
     * 找不到时返回明确错误，不会盲点。
     */
    fun ocrClick(body: JSONObject): JSONObject {
        val svc = requireControl()
        val text = body.optString("text").ifBlank { null }
            ?: throw IllegalArgumentException("需要 text")
        val index = body.optInt("index", 0)

        val screen = svc.screenSize()
        val bitmap = svc.takeScreenshotBlocking(null)
            ?: throw IllegalStateException("截图失败")
        val scale = if (bitmap.width > 0) screen.width().toFloat() / bitmap.width else 1f
        val lines = try {
            top.rainaki.phonemcp.capture.Ocr.recognize(bitmap, scale)
        } finally {
            bitmap.recycle()
        }

        val hits = top.rainaki.phonemcp.capture.Ocr.find(lines, text)
        if (hits.isEmpty()) {
            throw NoSuchElementException(
                "屏幕上没有识别到「$text」。可能文字是图片/图标，或识别不准——" +
                "可以先用 ocr 看看都识别出了什么"
            )
        }
        if (index >= hits.size) {
            throw IllegalArgumentException("「$text」只找到 ${hits.size} 处，没有第 ${index + 1} 处")
        }
        val target = hits[index]
        // 落点资金判定：OCR 目标没有 NodeInfo，只能靠坐标 + 整屏
        val (cx, cy) = top.rainaki.phonemcp.capture.Ocr.center(target)
        svc.callOnMain(timeoutMs = 5_000) {
            val root = svc.activeRoot()
            if (root != null) MoneyGuard.enforceTarget(MoneyGuard.nodeAt(root, cx, cy))
        }
        // 文字本身也要过一遍目标词判定
        MoneyGuard.enforceTargetText(target.text)

        val ok = svc.tapPoint(cx, cy)
        return JSONObject().apply {
            put("ok", ok)
            put("target", "OCR:\"${target.text}\" [${target.bounds.left},${target.bounds.top}]")
            put("hits", hits.size)
        }
    }

    // ------------------------------------------------------------- 应用

    /** 列出可启动的第三方应用（有 LAUNCHER intent 的）。 */
    fun apps(): JSONObject {
        val svc = requireService()
        val pm = svc.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val list = pm.queryIntentActivities(intent, 0)
        val arr = JSONArray()
        val seen = HashSet<String>()
        for (info in list) {
            val pkg = info.activityInfo.packageName
            if (!seen.add(pkg)) continue
            if (pkg == svc.packageName) continue
            arr.put(
                JSONObject().apply {
                    put("package", pkg)
                    put("label", info.loadLabel(pm).toString())
                }
            )
        }
        return JSONObject().put("apps", arr)
    }

    fun launch(body: JSONObject): JSONObject = duringAction(stepOf(body)) {
        val svc = requireControl()
        val pkg = body.optString("package").ifBlank { null }
            ?: throw IllegalArgumentException("需要 package")
        // 资金类 App 连启动都不允许——一打开就在付款页上，后续再拦已经晚了
        MoneyGuard.checkPackage(pkg)
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg)
            ?: throw NoSuchElementException("应用 $pkg 没有可启动的入口")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            svc.startActivity(intent)
            JSONObject().put("ok", true).put("launched", pkg)
        } catch (t: Throwable) {
            // MIUI 会拦截后台应用拉起其它应用，报错要说清楚，否则调用方以为成功了
            throw IllegalStateException(
                "启动 $pkg 被系统拒绝：${t.javaClass.simpleName}: ${t.message}。" +
                "MIUI 需在「应用管理 → Phone MCP → 权限」里允许「后台弹出界面」。"
            )
        }
    }

    private fun android.view.accessibility.AccessibilityNodeInfo.describeForLog(): String {
        if (isPassword) return "受保护的输入框"
        val rect = android.graphics.Rect().also { getBoundsInScreen(it) }
        return buildString {
            text?.let { append("text=\"$it\" ") }
            contentDescription?.let { append("desc=\"$it\" ") }
            viewIdResourceName?.let { append("id=$it ") }
            append("bounds=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
        }
    }
}
