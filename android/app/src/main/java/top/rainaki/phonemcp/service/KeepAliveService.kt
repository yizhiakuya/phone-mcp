package top.rainaki.phonemcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import top.rainaki.phonemcp.PhoneMcpApp
import top.rainaki.phonemcp.R
import top.rainaki.phonemcp.tunnel.TunnelClient
import top.rainaki.phonemcp.ui.MainActivity

/**
 * 常驻前台服务。
 *
 * 为什么需要它：隧道跑在 App 自己的进程里。没有前台服务时，MIUI / ColorOS 这类
 * ROM 会在后台随时回收该进程，隧道随之断开且不再重连（表现为"用一下就掉线"）。
 * 前台服务带常驻通知，系统不会轻易杀，隧道才能长期稳定。
 *
 * 注意：它不持有无障碍能力——那是 PhoneMcpService 的事。这里只负责
 * "让进程活着"并维护隧道生命周期。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "phone_mcp_keepalive"
        private const val NOTIFICATION_ID = 1001

        fun start(context: android.content.Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }

    private var observer: TunnelClient? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("正在连接中继…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        attachObserver()
        return START_STICKY
    }

    private fun attachObserver() {
        val app = application as PhoneMcpApp
        if (observer === app.tunnelClient) return
        observer = app.tunnelClient
        // 状态变化时刷新通知文案（App 内部已有回调，这里轮询一次即可）
        app.onTunnelStateChanged = { state, detail ->
            val text = when (state) {
                TunnelClient.State.CONNECTED -> "中继已连接"
                TunnelClient.State.CONNECTING -> "正在连接中继…"
                TunnelClient.State.ERROR -> "中继连接失败：${detail ?: ""}"
                TunnelClient.State.DISCONNECTED -> "中继已断开"
            }
            updateNotification(text)
        }
        updateNotification("Phone MCP 运行中")
    }

    private fun updateNotification(text: String) {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        }.onFailure { Log.w(TAG, "update notification failed", it) }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone MCP")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Phone MCP 后台服务",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持中继连接不断开"
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        (application as PhoneMcpApp).onTunnelStateChanged = null
        super.onDestroy()
    }
}
