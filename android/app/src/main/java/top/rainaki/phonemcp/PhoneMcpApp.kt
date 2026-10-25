package top.rainaki.phonemcp

import android.app.Application
import android.util.Log
import top.rainaki.phonemcp.service.KeepAliveService
import top.rainaki.phonemcp.service.StopButton
import top.rainaki.phonemcp.tunnel.TunnelClient
import top.rainaki.phonemcp.util.Prefs

/**
 * 进程级单例。持有隧道客户端与配置，Activity 与服务都从这里取状态。
 *
 * 只保留中继隧道这一条接入路径：手机主动外连、全链路 TLS，
 * 不提供局域网明文监听，避免在家庭/办公网络里暴露一个无加密入口。
 */
class PhoneMcpApp : Application() {

    companion object {
        private const val TAG = "PhoneMcpApp"
        private const val SOCKET_READ_TIMEOUT = 10_000

        @Volatile
        lateinit var instance: PhoneMcpApp
            private set
    }

    lateinit var prefs: Prefs
        private set

    @Volatile
    private var tunnel: TunnelClient? = null

    /** 顶部中断条（AI 操作时显示，用户点一下即中断）。 */
    @Volatile
    var stopButton: StopButton? = null
        private set


    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var tunnelState: TunnelClient.State = TunnelClient.State.DISCONNECTED
        private set

    @Volatile
    var tunnelDetail: String? = null
        private set

    /** 供前台服务订阅隧道状态变化，用来刷新常驻通知。 */
    @Volatile
    var onTunnelStateChanged: ((TunnelClient.State, String?) -> Unit)? = null

    /** 前台服务读取当前隧道实例，判断是否已在运行。 */
    val tunnelClient: TunnelClient?
        get() = tunnel

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        // 顶部中断条：AI 操作时显示，用户点一下立即中断
        runCatching {
            stopButton = StopButton(this).also { sb ->
                // 断开提示里的「立即重连」按钮
                sb.onReconnect = { startTunnel() }
            }
        }.onFailure { Log.w(TAG, "stop button init failed", it) }

        if (prefs.relayConfigured) {
            startTunnel()
            // 拉起前台服务：没有它，进程会被 ROM 在后台回收，隧道随之断开且不再重连
            runCatching { KeepAliveService.start(this) }
                .onFailure { Log.w(TAG, "failed to start keep-alive service", it) }
        }
    }

    // ------------------------------------------------------------ 中继隧道

    /** 按当前配置启动隧道。未配置时返回 false。 */
    @Synchronized
    fun startTunnel(): Boolean {
        stopTunnel()
        if (!prefs.relayConfigured) {
            tunnelState = TunnelClient.State.DISCONNECTED
            tunnelDetail = "未配置中继"
            return false
        }
        val token = try {
            prefs.relayToken
        } catch (e: Exception) {
            tunnelState = TunnelClient.State.ERROR
            tunnelDetail = "无法解密配对凭据，请重新扫码"
            return false
        }
        val client = TunnelClient(
            relayUrl = prefs.relayUrl,
            token = token,
            deviceId = prefs.deviceId,
            e2eKey = prefs.e2eKey,
        ) { state, detail ->
            tunnelState = state
            tunnelDetail = detail
            onTunnelStateChanged?.invoke(state, detail)
            // 断开时在底部条上提示——手机上什么反应都没有时，
            // 用户得知道是中继断了还是 AI 卡住了
            when (state) {
                TunnelClient.State.DISCONNECTED, TunnelClient.State.ERROR ->
                    stopButton?.showConnection(detail)
                TunnelClient.State.CONNECTED ->
                    stopButton?.clearConnection()
                else -> Unit
            }
        }
        tunnel = client
        client.start()
        // 隧道在跑就保证有前台服务护着，避免进程被回收后不再重连
        runCatching { KeepAliveService.start(this) }
            .onFailure { Log.w(TAG, "failed to start keep-alive service", it) }
        return true
    }

    @Synchronized
    fun stopTunnel() {
        tunnel?.stop()
        tunnel = null
        tunnelState = TunnelClient.State.DISCONNECTED
        tunnelDetail = null
        runCatching { KeepAliveService.stop(this) }
    }

    val isTunnelRunning: Boolean
        get() = tunnel?.isRunning == true
}
