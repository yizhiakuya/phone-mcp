package top.rainaki.phonemcp.tunnel

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import top.rainaki.phonemcp.http.DeviceApi
import top.rainaki.phonemcp.util.E2ee
import java.util.concurrent.TimeUnit

/**
 * 主动外连中继的 WebSocket 客户端。
 *
 * 手机在 NAT / 移动网络后面，无法被电脑直接连。这里反过来：
 * 手机建立一条到中继的长连接，中继把 MCP 的请求塞进来，我们执行完把结果送回去。
 *
 * 断线自动重连，带指数退避；中继重启或网络切换都能自己恢复。
 */
class TunnelClient(
    private val relayUrl: String,
    private val token: String,
    private val deviceId: String,
    /** 端到端密钥，来自配对二维码。中继永远看不到它。 */
    private val e2eKey: String,
    private val onState: (State, String?) -> Unit,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

    private val api = DeviceApi()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * 动作执行线程池。
     *
     * 不能直接在 OkHttp 的 WebSocket 读线程上同步执行动作：一个请求卡住
     * （如手势等回调超时）就会堵住整条连接，后续请求全部排队、中继侧表现为
     * inflight 一直不归零。单线程保证动作串行（界面操作本来就不该并发），
     * 但不阻塞读线程。
     */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "phone-mcp-action").apply { isDaemon = true }
    }

    private val http = OkHttpClient.Builder()
        // 心跳从 25s 放宽到 60s：移动网络下每次心跳都要唤醒射频，
        // 60s 足以维持 NAT 映射（常见路由器 120s+），同时明显省电
        .pingInterval(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 长连接，不设读超时
        .build()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var running = false

    /** 同一时刻只允许一条重连链，防止 onFailure + onClosed 双回调叠加。 */
    private val reconnectScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    private var attempt = 0

    /** 本次连接建立的时间，用来判断"刚连上就断"（不该重置退避）。 */
    @Volatile
    private var connectedAt = 0L

    fun start() {
        if (running) return
        running = true
        attempt = 0
        reconnectScheduled.set(false)
        connect()
    }

    fun stop() {
        running = false
        reconnectScheduled.set(false)
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "client stop")
        socket = null
        worker.shutdownNow()
        notify(State.DISCONNECTED, null)
    }

    val isRunning: Boolean get() = running

    // ------------------------------------------------------------ 连接

    private fun connect() {
        if (!running) return
        try {
            top.rainaki.phonemcp.util.ConnectionSecurity.requireSecureTunnel(relayUrl)
        } catch (e: Exception) {
            notify(State.ERROR, "中继地址无效，需重新扫码配对")
            return
        }
        notify(State.CONNECTING, null)

        // token 走 Authorization 头，不拼进 URL：避免出现在 access log / Referer / 代理缓存里
        val url = buildString {
            val base = relayUrl.trimEnd('/')
            // 允许配置写成 ".../phonemcp" 或 ".../phonemcp/tunnel" 两种形式，
            // 避免拼出 ".../phonemcp/tunnel/tunnel"
            append(if (base.endsWith("/tunnel")) base else "$base/tunnel")
            append("?device=").append(deviceId)
        }

        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()
        } catch (t: Throwable) {
            notify(State.ERROR, "中继地址无效：${t.message}")
            scheduleReconnect()
            return
        }

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connectedAt = System.currentTimeMillis()
                notify(State.CONNECTED, null)
                Log.i(TAG, "tunnel connected to $url")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // OkHttp 回调在后台线程，动作本身会自己切主线程
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (code == 4003) {
                    running = false
                    worker.shutdownNow()
                    notify(State.ERROR, "设备已被撤销，请重新扫码配对")
                }
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socket = null
                if (code == 4003) {
                    running = false
                    notify(State.ERROR, "设备已被撤销，请重新扫码配对")
                    return
                }
                // 连接活够久才算真的恢复，重置退避；刚连上就断说明服务端或网络有问题，
                // 继续按指数退避，避免 1 秒一次的高频重连。
                if (System.currentTimeMillis() - connectedAt > STABLE_CONNECTION_MS) attempt = 0
                if (running) {
                    notify(State.DISCONNECTED, "连接已关闭($code)")
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                socket = null
                if (response?.code == 401) {
                    running = false
                    worker.shutdownNow()
                    notify(State.ERROR, "配对凭据已失效，请重新扫码配对")
                    return
                }
                if (System.currentTimeMillis() - connectedAt > STABLE_CONNECTION_MS) attempt = 0
                if (running) {
                    notify(State.ERROR, t.message ?: t.javaClass.simpleName)
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!running) return
        // OkHttp 在 onFailure 之后仍可能回调 onClosed，两个回调各排一次重连
        // 会滚出多条并行的重连链（表现为疯狂断连重连）。用标志位保证同一时刻
        // 只有一条重连链在跑。
        if (!reconnectScheduled.compareAndSet(false, true)) return

        // 1s, 2s, 4s … 最多 30s。加抖动避免多台设备同时重连打爆服务端
        val base = (1000L shl attempt.coerceAtMost(5)).coerceAtMost(30_000L)
        val jitter = (base * 0.2 * Math.random()).toLong()
        attempt++
        val delay = base + jitter
        Log.i(TAG, "reconnect in ${delay}ms (attempt $attempt)")
        handler.postDelayed({
            reconnectScheduled.set(false)
            if (running) connect()
        }, delay)
    }

    private fun notify(state: State, detail: String?) {
        handler.post { onState(state, detail) }
    }

    // ------------------------------------------------------------ 请求处理

    private fun handleMessage(text: String) {
        val msg = try {
            JSONObject(text)
        } catch (t: Throwable) {
            Log.w(TAG, "bad message", t)
            return
        }
        if (msg.optString("type") != "request") return

        // 立刻返回读线程，动作在 worker 上串行执行
        worker.execute { runAction(msg) }
    }

    private fun runAction(msg: JSONObject) {
        if (!running || socket == null) return
        val id = msg.optString("id")
        val path = msg.optString("path")

        // 端到端解密：中继只转发 blob，密钥只在本机与电脑端。
        // 解密失败说明密钥不匹配或密文被篡改——直接拒绝，不执行任何动作。
        val body = try {
            // 中继把请求体放在 msg.body 里，密文在 body.e2ee
            val envelope = msg.optJSONObject("body") ?: JSONObject()
            val blob = envelope.optString("e2ee")
            if (blob.isBlank()) throw IllegalStateException("请求未加密")
            E2ee.open(e2eKey, deviceId, path, blob)
        } catch (t: Throwable) {
            Log.e(TAG, "e2ee open failed for $path", t)
            // 诊断：把失败原因和关键参数回传，便于定位两端不一致。
            // 这个响应不含敏感内容（只有长度和异常类型）。
            val diag = "key=${if (e2eKey.isBlank()) "MISSING" else "len${e2eKey.length}"}" +
                " path=$path err=${t.javaClass.simpleName}:${t.message}"
            replyPlain(id, 400, errorJson("e2ee_fail|$diag"))
            return
        }

        try {
            when {
                path == "/screenshot" -> {
                    val bytes = api.screenshot(body)
                    replyBinary(id, bytes, "image/jpeg", path)
                }
                else -> {
                    val (status, payload) = dispatch(path, body)
                    replyJson(id, status, payload, path)
                }
            }
        } catch (e: top.rainaki.phonemcp.service.MoneyGuard.BlockedException) {
            // 资金保护拦截：403 + 固定标记，服务端据此抛 ToolError 让模型停手
            replyJson(id, 403, JSONObject().apply {
                put("ok", false)
                put("error", e.message)
                put("money_blocked", true)
            }, path)
        } catch (e: top.rainaki.phonemcp.service.UserOperatingException) {
            replyJson(id, 409, JSONObject().apply {
                put("ok", false)
                put("error", e.message)
                put("user_operating", true)
                put("retry_after_ms", e.retryAfterMs)
            }, path)
        } catch (e: IllegalArgumentException) {
            replyJson(id, 400, errorJson(e.message), path)
        } catch (e: NoSuchElementException) {
            replyJson(id, 404, errorJson(e.message), path)
        } catch (e: Throwable) {
            Log.e(TAG, "action failed: $path", e)
            replyJson(id, 500, errorJson(e.message ?: e.javaClass.simpleName), path)
        }
    }

    private fun dispatch(path: String, body: JSONObject): Pair<Int, JSONObject> = when (path) {
        "/health" -> 200 to api.health()
        "/tree" -> 200 to api.tree(body)
        "/windows" -> 200 to api.windows()
        "/click" -> 200 to api.click(body)
        "/long_click" -> 200 to api.longClick(body)
        "/tap" -> 200 to api.tap(body)
        "/swipe" -> 200 to api.swipe(body)
        "/scroll" -> 200 to api.scroll(body)
        "/set_text" -> 200 to api.setText(body)
        "/key" -> 200 to api.key(body)
        "/wait_idle" -> 200 to api.waitIdle(body)
        "/wait_until" -> 200 to api.waitUntil(body)
        "/watch" -> 200 to api.watch(body)
        "/user_status" -> 200 to api.overrideStatus()
        "/apps" -> 200 to api.apps()
        "/launch" -> 200 to api.launch(body)
        else -> 404 to errorJson("未知接口 $path")
    }

    /**
     * 明文错误回复。仅用于「端到端解密失败」这一种情况——
     * 此时用错误的密钥加密响应没有意义（对方也解不开），
     * 且该内容不含任何敏感信息，只有长度与异常类型。
     */
    private fun replyPlain(id: String, status: Int, body: JSONObject) {
        val msg = JSONObject().apply {
            put("type", "response")
            put("id", id)
            put("status", status)
            put("plain_error", body)
        }
        socket?.send(msg.toString())
    }

    private fun replyJson(id: String, status: Int, body: JSONObject, path: String? = null) {
        val sealed = try {
            // 用请求路径作为 AAD 的一部分，保持与电脑端一致
            E2ee.seal(e2eKey, deviceId, path ?: "", body)
        } catch (t: Throwable) {
            Log.e(TAG, "e2ee seal failed", t)
            return
        }
        val msg = JSONObject().apply {
            put("type", "response")
            put("id", id)
            put("status", status)
            put("e2ee", sealed)
        }
        socket?.send(msg.toString())
    }

    private fun replyBinary(id: String, bytes: ByteArray, contentType: String, path: String) {
        val sealed = try {
            E2ee.sealBytes(e2eKey, deviceId, path, bytes)
        } catch (t: Throwable) {
            Log.e(TAG, "e2ee sealBytes failed", t)
            return
        }
        val msg = JSONObject().apply {
            put("type", "response")
            put("id", id)
            put("status", 200)
            put("content_type", contentType)
            put("e2ee", sealed)
        }
        socket?.send(msg.toString())
    }

    private fun errorJson(message: String?): JSONObject = JSONObject().apply {
        put("ok", false)
        put("error", message ?: "未知错误")
    }

    private companion object {
        const val TAG = "TunnelClient"

        /** 连接存活超过这个时长才认为稳定，可重置重连退避。 */
        private const val STABLE_CONNECTION_MS = 10_000L
    }
}
