package top.rainaki.phonemcp.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.view.WindowManager
import com.google.zxing.integration.android.IntentIntegrator
import androidx.core.app.ActivityCompat
import top.rainaki.phonemcp.PhoneMcpApp
import top.rainaki.phonemcp.BuildConfig
import top.rainaki.phonemcp.R
import top.rainaki.phonemcp.service.HumanOverride
import top.rainaki.phonemcp.service.PhoneMcpService
import top.rainaki.phonemcp.tunnel.TunnelClient
import top.rainaki.phonemcp.update.Updater
import top.rainaki.phonemcp.util.Prefs
import top.rainaki.phonemcp.util.PairingClient

class MainActivity : android.app.Activity() {

    private lateinit var app: PhoneMcpApp
    private lateinit var statusService: TextView
    private lateinit var statusTunnel: TextView
    private lateinit var statusDevice: TextView
    private lateinit var pairingButton: Button
    private var pairing = false
    private lateinit var deviceIdText: TextView
    private lateinit var statusVersion: TextView
    private lateinit var statusStopBtn: TextView
    private lateinit var btnOverlayPerm: Button
    private lateinit var statusOverride: TextView
    private lateinit var btnResume: Button

    // 上一次渲染的状态快照：只有变化时才更新控件，避免无谓重绘
    private var lastRenderKey: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            renderStatus()
            // 3 秒一次足够：状态变化本身会通过回调立即触发，这里只是兜底
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        app = application as PhoneMcpApp
        setContentView(R.layout.activity_main)

        statusService = findViewById(R.id.statusService)
        statusTunnel = findViewById(R.id.statusTunnel)
        statusDevice = findViewById(R.id.statusDevice)
        pairingButton = findViewById(R.id.btnPair)
        deviceIdText = findViewById(R.id.deviceIdText)
        statusVersion = findViewById(R.id.statusVersion)
        statusStopBtn = findViewById(R.id.statusStopBtn)
        btnOverlayPerm = findViewById(R.id.btnOverlayPerm)
        statusOverride = findViewById(R.id.statusOverride)
        btnResume = findViewById(R.id.btnResume)

        pairingButton.setOnClickListener {
            if (!pairing) IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("扫描电脑上的 Phone MCP 配对二维码")
                .setBeepEnabled(false)
                .setBarcodeImageEnabled(false)
                .setCaptureActivity(PairingCaptureActivity::class.java)
                .initiateScan()
        }
        findViewById<Button>(R.id.btnForget).setOnClickListener {
            if (pairing) return@setOnClickListener
            app.stopTunnel()
            app.prefs.clearPairing()
            lastRenderKey = null
            renderStatus()
            toast("已断开并清除本机配对；服务器撤销请在电脑端操作")
        }

        findViewById<Button>(R.id.btnOpenAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { requestIgnoreBattery() }
        findViewById<Button>(R.id.btnUpdate).setOnClickListener { checkForUpdate() }
        btnOverlayPerm.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        app.settingsVisible = true
        // 无障碍开关可能是刚从设置页回来改的，重新拉一次状态
        renderStatus()
        handler.post(refresh)
    }

    override fun onPause() {
        app.settingsVisible = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    @Deprecated("Activity result bridge for bundled QR scanner")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val qr = result.contents ?: return
        if (pairing) return
        pairing = true
        pairingButton.isEnabled = false
        pairingButton.text = "正在配对…"
        Thread {
            val paired = runCatching { PairingClient.pair(qr, app.prefs) }
            runOnUiThread {
                pairing = false
                if (isDestroyed) return@runOnUiThread
                pairingButton.isEnabled = true
                pairingButton.text = "扫码配对"
                paired.onSuccess {
                    app.startTunnel()
                    toast("配对成功，正在连接")
                }.onFailure {
                    // JSON/网络异常可能带原始载荷，不能把异常原文放到界面或日志。
                    toast("配对失败：请检查网络并重新生成有效二维码")
                }
                lastRenderKey = null
                renderStatus()
            }
        }.start()
    }

    // 这两项查询较贵（跨进程 / 查系统设置），降低检查频率
    private var overlayCheckedAt = 0L
    private var overlayGranted = false
    private var windowAccessCheckedAt = 0L
    private var windowAccessible = false

    private fun renderStatus() {
        val now = System.currentTimeMillis()
        val connected = PhoneMcpService.isConnected

        // 状态没变就不重绘。TextView.setText 即使内容相同也会触发布局/绘制，
        // 1.5 秒一次的全量刷新会白白唤醒渲染管线。
        val renderKey = buildString {
            append(connected).append('|')
            append(app.tunnelState).append('|')
            append(app.tunnelDetail).append('|')
            append(HumanOverride.isStopped()).append('|')
            append(app.prefs.deviceId).append('|')
            append(app.stopButton?.currentStep).append('|')
            append(app.stopButton?.stepCount)
        }
        if (renderKey == lastRenderKey) return
        lastRenderKey = renderKey

        // hasWindowAccess 会跨进程取窗口，每 5 秒才查一次
        if (now - windowAccessCheckedAt > 5000) {
            windowAccessCheckedAt = now
            windowAccessible = connected && PhoneMcpService.instance?.hasWindowAccess() == true
        }
        val canRead = connected && windowAccessible
        when {
            !connected -> {
                statusService.text = "无障碍服务：未连接 ✗"
                statusService.setTextColor(getColor(R.color.danger))
            }
            !canRead -> {
                statusService.text = "无障碍服务：已连接但读不到窗口 ✗（可能被系统禁用，请重开）"
                statusService.setTextColor(getColor(R.color.danger))
            }
            else -> {
                statusService.text = "无障碍服务：已连接 ✓"
                statusService.setTextColor(getColor(R.color.ok))
            }
        }

        val (tunnelText, tunnelColor) = when (app.tunnelState) {
            TunnelClient.State.CONNECTED -> "中继隧道：已连接 ✓" to R.color.ok
            TunnelClient.State.CONNECTING -> "中继隧道：连接中…" to R.color.warn
            TunnelClient.State.ERROR ->
                "中继隧道：连接失败${app.tunnelDetail?.let { "（$it）" } ?: ""}" to R.color.danger
            TunnelClient.State.DISCONNECTED ->
                if (app.prefs.relayConfigured) "中继隧道：已断开" to R.color.warn
                else "中继隧道：未配置" to R.color.text_secondary
        }
        statusTunnel.text = tunnelText
        statusTunnel.setTextColor(getColor(tunnelColor))

        statusDevice.text = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"


        // 用户正在操作手机时给个提示即可，不需要任何按钮——
        // 停手后自动放行，没有"恢复"这个动作
        // 中断条状态：没权限就用不了，必须让用户知道
        val sb = app.stopButton
        val hasOverlay = sb?.canDrawOverlays() == true
        btnOverlayPerm.visibility =
            if (hasOverlay) android.view.View.GONE else android.view.View.VISIBLE
        statusStopBtn.text = when {
            !hasOverlay -> "中断条：未授权 ✗（AI 操作时无法显示，请点下方按钮授权）"
            HumanOverride.isStopped() ->
                "中断条：已中断（${HumanOverride.remainingMs() / 1000 + 1} 秒后放行）"
            sb?.currentStep != null ->
                "AI 正在操作（第 ${sb.stepCount} 步）：${sb.currentStep}"
            else -> "中断条：就绪 ✓（AI 操作时出现在屏幕底部，点一下即中断）"
        }
        statusStopBtn.setTextColor(getColor(if (hasOverlay) R.color.ok else R.color.warn))

        val stopped = HumanOverride.isStopped()
        statusOverride.visibility = if (stopped) android.view.View.VISIBLE else android.view.View.GONE
        btnResume.visibility = android.view.View.GONE
        if (stopped) {
            statusOverride.text = "⏸ 你已中断自动操作（${HumanOverride.remainingMs() / 1000 + 1} 秒后放行）"
        }

        deviceIdText.text = if (app.prefs.relayConfigured)
            "已配对：${app.prefs.deviceId}\n${app.prefs.relayUrl}"
        else "尚未配对，请扫描电脑生成的二维码"
        if (statusVersion.text.isNullOrBlank() || statusVersion.text.startsWith("正在检查")) {
            statusVersion.text = "当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）"
        }
    }

    @Suppress("DEPRECATION")
    private fun requestIgnoreBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("已经在忽略电池优化列表里")
            return
        }
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }.onFailure {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- 自更新

    private fun checkForUpdate() {
        val btn = findViewById<Button>(R.id.btnUpdate)
        btn.isEnabled = false
        btn.text = "检查中…"
        statusVersion.text = "正在检查更新…"

        Thread {
            val updater = Updater(this)
            when (val result = updater.check()) {
                is Updater.CheckResult.UpToDate -> runOnUiThread {
                    btn.isEnabled = true
                    btn.text = "检查更新"
                    statusVersion.text = "已是最新版本 ${BuildConfig.VERSION_NAME}"
                    toast("已是最新版本")
                }
                is Updater.CheckResult.Failed -> runOnUiThread {
                    btn.isEnabled = true
                    btn.text = "检查更新"
                    statusVersion.text = "检查失败：${result.message}"
                    toast("检查失败")
                }
                is Updater.CheckResult.Available -> runOnUiThread {
                    btn.isEnabled = true
                    btn.text = "检查更新"
                    confirmUpdate(updater, result.release)
                }
            }
        }.start()
    }

    private fun confirmUpdate(updater: Updater, release: Updater.Release) {
        val cached = updater.hasDownloaded(release)
        val header = buildString {
            append("发现新版本 ${release.versionName}")
            append("（当前 ${BuildConfig.VERSION_NAME}）")
        }
        val footer = if (cached) {
            "安装包已下载完成，可直接安装。"
        } else {
            "下载后系统会弹一次安装确认，点「安装」即可。"
        }

        // changelog 是 Markdown（GitHub Release 说明），必须渲染后再显示，
        // 否则用户看到的是 `## 标题` 这样的源码。
        val content = MarkdownView.render(this, release.changelog.ifBlank { footer })
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, 0)
            addView(android.widget.TextView(this@MainActivity).apply {
                text = header
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0xFF6B7280.toInt())
                setPadding(0, 0, 0, p / 2)
            })
            addView(
                content,
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            if (release.changelog.isNotBlank()) {
                addView(android.widget.TextView(this@MainActivity).apply {
                    text = footer
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(0xFF6B7280.toInt())
                    setPadding(0, p / 2, 0, 0)
                })
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle(if (cached) "可以安装了" else "更新可用")
            .setView(container)
            .setPositiveButton(if (cached) "立即安装" else "下载并安装") { _, _ ->
                startUpdate(updater, release)
            }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun startUpdate(updater: Updater, release: Updater.Release) {
        val btn = findViewById<Button>(R.id.btnUpdate)
        btn.isEnabled = false
        btn.text = if (updater.hasDownloaded(release)) "安装中…" else "下载中…"

        Thread {
            try {
                val result = updater.download(release) { done, total ->
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    runOnUiThread {
                        btn.text = if (total > 0) "下载中 $pct%" else "下载中…"
                        statusVersion.text = "下载 ${release.versionName}：$pct%"
                    }
                }
                val apk = result.file
                runOnUiThread {
                    btn.isEnabled = true
                    btn.text = "检查更新"
                    val err = updater.install(apk)
                    when {
                        err == null -> {
                            statusVersion.text = "已拉起安装器，请在系统弹窗里确认"
                            toast("请在系统弹窗里点\"安装\"")
                        }
                        err == "NEED_PERMISSION" -> {
                            statusVersion.text = "需要先允许\"安装未知应用\""
                            android.app.AlertDialog.Builder(this)
                                .setTitle("需要安装权限")
                                .setMessage("Phone MCP 需要「安装未知应用」权限才能自更新。\n点「去设置」打开，然后回来再点一次检查更新。")
                                .setPositiveButton("去设置") { _, _ -> updater.openInstallPermissionSettings() }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                        else -> {
                            statusVersion.text = "安装失败：$err"
                            toast("安装失败：$err")
                        }
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    btn.isEnabled = true
                    btn.text = "检查更新"
                    statusVersion.text = "更新失败：${t.message}"
                    toast("更新失败：${t.message}")
                }
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
