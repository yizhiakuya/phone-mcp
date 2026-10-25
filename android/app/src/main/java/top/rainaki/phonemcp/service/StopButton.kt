package top.rainaki.phonemcp.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 底部状态条：AI 的操作进度显示 + 中断入口 + 连接状态提示。
 *
 * 三种模式共用一条窗口，避免屏幕上同时挂两个悬浮条：
 *
 * | 模式 | 何时 | 外观 | 自动隐藏 |
 * |---|---|---|---|
 * | [Mode.ACTION] | AI 正在执行动作 | 红点 + 当前步骤 | 3 秒 |
 * | [Mode.CONNECTION] | 中继断开/连接失败 | 橙点 + 断开原因 | 常驻 |
 *
 * 交互：
 *   1. 点胶囊 → 向上展开面板，露出最近几步 / 断开详情 + 操作按钮
 *   2. 动作模式下按钮是「停止操作」；连接模式下是「立即重连」
 *   3. 点面板外或再点胶囊 → 收起
 *
 * 为什么中断要靠用户主动点而不是自动检测触摸：Android 无法区分
 * dispatchGesture 注入的手势和真人触摸（系统层面完全一致，无任何标记）。
 * 任何自动判定都会在"误判自己的动作为用户操作"和"漏掉用户真实操作"之间摇摆。
 *
 * 步骤说明来自模型，**只作展示，不作安全判据**——模型可能写一句好听的
 * 描述却干别的事。真正的硬约束是 [MoneyGuard]（资金保护）。
 */
class StopButton(private val context: Context) {

    /** 状态条当前在服务哪种场景。 */
    enum class Mode { NONE, ACTION, CONNECTION, LOCKED }

    companion object {
        private const val TAG = "StopButton"

        /** 动作结束后保持可见的时长。连续操作时会不断顺延。 */
        private const val AUTO_HIDE_MS = 3_000L
        private const val ANIM_MS = 220L

        /** 面板里最多显示几步。 */
        private const val MAX_STEPS = 5

        /** 胶囊上步骤文字的最大长度，超出省略。 */
        private const val PILL_STEP_MAX = 14

        /** 面板里单步描述的最大长度。 */
        private const val PANEL_STEP_MAX = 80
    }

    private var root: FrameLayout? = null
    private var wm: WindowManager? = null
    private var pill: LinearLayout? = null
    private var pillDot: View? = null
    private var pillLabel: TextView? = null
    private var panel: LinearLayout? = null
    private var headerText: TextView? = null
    private var statusText: TextView? = null
    private var stepsText: TextView? = null
    private var actionButton: TextView? = null
    private var hintText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null

    /** 最近几步的描述。只在主线程读写。 */
    private val steps = ArrayDeque<String>()

    /** 本次任务累计执行的动作数（去重后）。 */
    private var totalSteps = 0

    /** 本次任务开始时间，用来在面板上显示已经跑了多久。 */
    private var startedAt = 0L

    /** 连接模式下的断开原因，展开面板时显示。 */
    private var connectionDetail: String? = null

    /** 当前这一步在做什么。给主界面展示用，没有则为 null。 */
    @Volatile
    var currentStep: String? = null
        private set

    /** 本次任务已执行多少步。 */
    @Volatile
    var stepCount: Int = 0
        private set

    @Volatile
    private var visible = false

    @Volatile
    private var expanded = false

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    /** 当前模式。 */
    @Volatile
    private var mode: Mode = Mode.NONE

    /** 连接模式下「立即重连」按钮的回调。 */
    @Volatile
    var onReconnect: (() -> Unit)? = null

    /**
     * 显示胶囊并记录这一步。
     *
     * 必须同步执行（在主线程调用时），否则动作只有 200~300ms，异步 post
     * 会让窗口来不及出现。
     *
     * @param step 这一步在做什么。为空时显示通用的「AI 正在操作」
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(step: String? = null) {
        if (!canDrawOverlays()) {
            Log.w(TAG, "no overlay permission")
            return
        }
        // 已锁定：条已经在显示"恢复"按钮，不要被动作调用改回动作模式
        if (HumanOverride.isStopped()) return
        // 正在显示断开提示时不要被动作顶掉——断开比"AI 在操作"更需要用户知道
        if (mode == Mode.CONNECTION && visible) return
        mode = Mode.ACTION
        val add = {
            if (root == null) createView()
            if (!visible) {
                runCatching { wm?.addView(root, params()) }
                    .onSuccess { visible = true; Log.i(TAG, "shown") }
                    .onFailure { Log.e(TAG, "addView failed", it) }
            }
            recordStep(step)
            render()
            scheduleHide()
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else handler.post(add)
    }

    /**
     * 读取类动作（截图/读树）的进度提示。
     *
     * 和 [show] 的区别只是不设 actionRunning 标记——读操作本来就不长，
     * 但用户仍需要知道 AI 在看什么。没显示时顺手显示出来。
     */
    fun announce(step: String?) {
        show(step)
    }

    // ---------------------------------------------------------------- 连接状态

    /**
     * 显示断开提示。与动作模式共用同一条窗口。
     *
     * 断开比"AI 在操作"更需要用户知道：手机上什么反应都没有时，
     * 用户得知道是中继断了、还是 AI 卡住了。
     */
    fun showConnection(detail: String?) {
        if (!canDrawOverlays()) return
        connectionDetail = detail
        mode = Mode.CONNECTION
        val add = {
            if (root == null) createView()
            if (!visible) {
                runCatching { wm?.addView(root, params()) }
                    .onSuccess { visible = true }
                    .onFailure { Log.e(TAG, "addView failed", it) }
            }
            render()
            // 不自动隐藏：连接问题需要用户看到
            hideRunnable?.let { handler.removeCallbacks(it) }
            Unit
        }
        if (Looper.myLooper() == Looper.getMainLooper()) add() else handler.post(add)
    }

    /** 连接恢复。若当前是连接模式就收起，动作模式不受影响。 */
    fun clearConnection() {
        handler.post {
            if (mode != Mode.CONNECTION) return@post
            connectionDetail = null
            mode = Mode.NONE
            collapse(animate = false)
            if (visible) {
                runCatching { root?.let { wm?.removeView(it) } }
                visible = false
            }
        }
    }

    private fun applyParams() {
        if (!visible) return
        runCatching { root?.let { wm?.updateViewLayout(it, params()) } }
            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
    }

    fun hideSoon() {
        handler.post { if (mode == Mode.ACTION) scheduleHide() }  // 锁定/连接模式不自动隐藏
    }

    fun hide() {
        handler.post {
            hideRunnable?.let { handler.removeCallbacks(it) }
            collapse(animate = false)
            if (visible) {
                runCatching { root?.let { wm?.removeView(it) } }
                visible = false
            }
            // 条消失了就是一个任务边界，下次重新计数
            steps.clear()
            totalSteps = 0
            startedAt = 0L
            currentStep = null
            stepCount = 0
            if (mode == Mode.ACTION) mode = Mode.NONE
        }
    }

    private fun scheduleHide() {
        if (mode != Mode.ACTION) return
        hideRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            if (visible && !expanded && mode == Mode.ACTION) {
                runCatching { root?.let { wm?.removeView(it) } }
                visible = false
                steps.clear()
                totalSteps = 0
                startedAt = 0L
                currentStep = null
                stepCount = 0
                mode = Mode.NONE
            }
        }
        hideRunnable = r
        // 展开状态不自动隐藏——用户正在看面板
        handler.postDelayed(r, AUTO_HIDE_MS)
    }

    // ---------------------------------------------------------------- 步骤

    private fun recordStep(step: String?) {
        val s = step?.trim()?.replace('\n', ' ')?.take(PANEL_STEP_MAX) ?: return
        if (s.isEmpty()) return
        // 连续相同描述算同一步（如 observe 内部连发两次请求）
        if (steps.lastOrNull() == s) return
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        steps.addLast(s)
        totalSteps++
        currentStep = s
        stepCount = totalSteps
        while (steps.size > MAX_STEPS) steps.removeFirst()
    }

    private fun render() {
        if (mode == Mode.CONNECTION) {
            renderConnection()
            return
        }
        if (mode == Mode.LOCKED) {
            renderLocked()
            return
        }
        val current = steps.lastOrNull()
        pillDot?.background = dot(Color.parseColor("#FF3B30"))
        pillLabel?.text = if (current == null) {
            "AI 正在操作"
        } else {
            "AI · " + current.let { if (it.length > PILL_STEP_MAX) it.take(PILL_STEP_MAX) + "…" else it }
        }

        statusText?.text = if (current == null) "AI 正在操作手机" else current

        headerText?.text = if (totalSteps == 0) {
            "AI 正在操作手机"
        } else {
            "已执行 $totalSteps 步 · ${elapsed()}"
        }

        stepsText?.text = if (steps.isEmpty()) {
            ""
        } else {
            // 最早的在上面，最新的在下面并加 ▶ 标记
            steps.mapIndexed { i, s ->
                if (i == steps.size - 1) "▶ $s" else "· $s"
            }.joinToString("\n")
        }
        stepsText?.visibility = if (steps.isEmpty()) View.GONE else View.VISIBLE
        actionButton?.text = "停止操作"
        actionButton?.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#FF3B30"))
        }
        hintText?.text = "点下方按钮立即中断，把控制权交还给你"
    }

    /**
     * 锁定状态：显示已中断，按钮变「恢复」。
     *
     * 条**不会自动消失**——这是用户夺回控制权后的确认，也是唯一的解锁入口。
     */
    private fun renderLocked() {
        pillDot?.background = dot(Color.parseColor("#FF9500"))
        pillLabel?.text = "已中断 · 点此恢复 ▾"
        headerText?.text = "AI 操作已停止"
        statusText?.text = "控制权已交还给你。AI 的任何操作都会被拒绝，直到你点「恢复」。"
        val sec = HumanOverride.lockedForMs() / 1000
        val dur = if (sec < 60) "${sec} 秒" else "${sec / 60} 分钟"
        stepsText?.text = "已锁定 $dur"
        stepsText?.visibility = View.VISIBLE
        actionButton?.text = "恢复（允许 AI 继续）"
        actionButton?.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#0A84FF"))
        }
        hintText?.text = "不点恢复的话，AI 会一直被你挡在外面"
    }

    private fun renderConnection() {
        pillDot?.background = dot(Color.parseColor("#FF9500"))
        pillLabel?.text = "中继已断开 ▾"
        headerText?.text = "与中继的连接已断开"
        // 底层异常（如 "Expected HTTP 101 ... 502"）对用户没意义，先给可读结论，
        // 原始信息放到括号里，方便排查时对照
        statusText?.text = humanize(connectionDetail)
        stepsText?.text = "可能原因：\n· 手机断网或切到了飞行模式\n· 中继服务重启\n· 进程被系统回收"
        stepsText?.visibility = View.VISIBLE
        actionButton?.text = "立即重连"
        actionButton?.background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor("#0A84FF"))
        }
        hintText?.text = "AI 暂时无法操作这台手机；恢复后会自动重连"
    }

    /**
     * 把底层异常翻译成用户能看懂的一句话。
     *
     * 原始信息保留在括号里——排查时还是要靠它，但用户第一眼该看到结论。
     */
    private fun humanize(detail: String?): String {
        val d = detail?.trim().orEmpty()
        if (d.isEmpty()) return "手机不在中继上，AI 无法操作"
        val plain = when {
            d.contains("502") || d.contains("503") ->
                "中继暂时不可用（服务可能正在重启）"
            d.contains("401") || d.contains("凭据") ->
                "配对凭据已失效，需要重新扫码"
            d.contains("4003") || d.contains("撤销") ->
                "设备已被撤销，需要重新扫码配对"
            d.contains("101") ->
                "中继握手失败（服务可能正在重启）"
            d.contains("timeout", true) || d.contains("超时") ->
                "连接超时，检查手机网络"
            else -> d
        }
        return if (plain == d) d else "$plain\n（$d）"
    }

    private fun dot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun elapsed(): String {
        if (startedAt == 0L) return "0s"
        val sec = (System.currentTimeMillis() - startedAt) / 1000
        return if (sec < 60) "${sec}s" else "${sec / 60}m${sec % 60}s"
    }

    // ---------------------------------------------------------------- 视图

    private fun params(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // 底部居中，避开手势导航条
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = navigationBarHeight() + dp(16)
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun createView() {
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val container = FrameLayout(context).apply {
            // 点击外部区域收起
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && expanded) {
                    collapse(animate = true)
                    true
                } else false
            }
        }

        // ---- 胶囊 ----
        val pillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(12), dp(8))
            background = islandBackground(collapsed = true)
        }
        val dotView = View(context).apply {
            background = dot(Color.parseColor("#FF3B30"))
        }
        pillDot = dotView
        pillRow.addView(
            dotView,
            LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) },
        )
        pillLabel = TextView(context).apply {
            text = "AI 正在操作"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        pillRow.addView(pillLabel)
        pillRow.addView(TextView(context).apply {
            text = " ▾"
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        pillRow.setOnClickListener { toggle() }
        pill = pillRow

        // ---- 展开面板 ----
        val panelCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = islandBackground(collapsed = false)
            visibility = View.GONE
            alpha = 0f
        }

        statusText = TextView(context).apply {
            text = "AI 正在操作手机"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // 面板顶部：已执行步数 + 耗时
        headerText = TextView(context).apply {
            text = "AI 正在操作手机"
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        panelCol.addView(headerText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) })

        panelCol.addView(statusText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        stepsText = TextView(context).apply {
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        panelCol.addView(stepsText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        val hint = TextView(context).apply {
            text = "点下方按钮立即中断，把控制权交还给你"
            setTextColor(Color.parseColor("#99FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }
        hintText = hint
        panelCol.addView(hint, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        val stopBtn = TextView(context).apply {
            text = "停止操作"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(0, dp(11), 0, dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#FF3B30"))
            }
            setOnClickListener {
                // 三种模式下按钮语义不同
                when (mode) {
                    Mode.CONNECTION -> {
                        onReconnect?.invoke()
                        statusText?.text = "正在重连…"
                    }
                    Mode.LOCKED -> requestResume()
                    else -> requestStop()
                }
                Unit
            }
        }
        actionButton = stopBtn
        panelCol.addView(stopBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) })

        panelCol.setOnClickListener { }   // 吃掉点击，避免穿透

        panel = panelCol

        container.addView(pillRow, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })

        // 面板向上展开：贴底，内容自下而上
        container.addView(panelCol, FrameLayout.LayoutParams(
            dp(280),
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })

        root = container
    }

    private fun islandBackground(collapsed: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(if (collapsed) 20 else 18).toFloat()
            setColor(Color.parseColor("#F21A1D28"))
            setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }

    // ---------------------------------------------------------------- 展开/收起

    private fun toggle() {
        if (expanded) collapse(animate = true) else expand()
    }

    private fun expand() {
        val p = panel ?: return
        val pl = pill ?: return
        expanded = true
        hideRunnable?.let { handler.removeCallbacks(it) }
        p.visibility = View.VISIBLE
        p.measure(
            View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val targetH = p.measuredHeight
        p.layoutParams = (p.layoutParams as FrameLayout.LayoutParams)
        p.alpha = 0f
        p.translationY = dp(8).toFloat()   // 从下方升起

        // 胶囊淡出，面板展开
        pl.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f)
            .setDuration(ANIM_MS / 2).start()
        p.animate().alpha(1f).translationY(0f)
            .setDuration(ANIM_MS).setInterpolator(DecelerateInterpolator()).start()

        // 高度从 0 长到实际高度
        val lp = p.layoutParams as FrameLayout.LayoutParams
        val anim = ValueAnimator.ofInt(0, targetH)
        anim.duration = ANIM_MS
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            lp.height = a.animatedValue as Int
            p.layoutParams = lp
        }
        anim.start()

        handler.postDelayed({ if (expanded) pl.visibility = View.INVISIBLE }, ANIM_MS / 2)
    }

    private fun collapse(animate: Boolean) {
        val p = panel ?: return
        val pl = pill ?: return
        if (!expanded) return
        expanded = false

        if (!animate) {
            p.visibility = View.GONE
            pl.visibility = View.VISIBLE
            pl.alpha = 1f
            pl.scaleX = 1f
            pl.scaleY = 1f
            return
        }

        p.animate().alpha(0f).translationY(dp(8).toFloat())
            .setDuration(ANIM_MS / 2).start()
        handler.postDelayed({
            p.visibility = View.GONE
            pl.visibility = View.VISIBLE
            pl.alpha = 0f
            pl.scaleX = 0.9f
            pl.scaleY = 0.9f
            pl.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(ANIM_MS / 2).start()
            scheduleHide()
        }, ANIM_MS / 2)
    }

    /**
     * 用户点了「停止操作」。
     *
     * 中断是**锁**不是冷却：锁定后条**不消失**，改为显示「恢复」按钮。
     * 早期版本 10 秒后自动放行，被注入的模型等一会儿重试就能继续操作，
     * 中断形同虚设。现在必须用户手动恢复。
     */
    private fun requestStop() {
        HumanOverride.requestStop()
        mode = Mode.LOCKED
        render()
        hideRunnable?.let { handler.removeCallbacks(it) }
    }

    /** 用户点了「恢复」，解除锁定。 */
    private fun requestResume() {
        HumanOverride.resume()
        mode = Mode.ACTION
        statusText?.text = "已恢复，AI 可以继续操作"
        stepsText?.visibility = View.GONE
        render()
        scheduleHide()
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(28)
    }

    /** 手势导航条高度，条要避开它。 */
    private fun navigationBarHeight(): Int {
        val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else dp(24)
    }

    fun release() {
        handler.post {
            hideRunnable?.let { handler.removeCallbacks(it) }
            if (visible) {
                runCatching { root?.let { wm?.removeView(it) } }
                visible = false
            }
            root = null
            wm = null
            steps.clear()
            totalSteps = 0
            currentStep = null
            stepCount = 0
        }
    }
}
