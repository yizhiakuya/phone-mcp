package top.rainaki.phonemcp.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object PairingClient {
    /**
     * 从二维码里解析出中继地址，**不做任何网络请求**。
     *
     * 用于配对前的确认弹窗：用户必须看到自己要连的是哪个中继，
     * 扫到恶意二维码时才有机会取消。
     */
    fun peekOrigin(qr: String): String? = runCatching {
        require(qr.length <= 4096)
        val data = JSONObject(qr)
        require(data.optString("type") == "phone-mcp-pair" && data.optInt("version") == 1)
        ConnectionSecurity.httpsOrigin(data.getString("relay_url"))
    }.getOrNull()

    fun pair(qr: String, prefs: Prefs) {
        require(qr.length <= 4096) { "无效的配对二维码" }
        val data = JSONObject(qr)
        require(data.optString("type") == "phone-mcp-pair" && data.optInt("version") == 1) {
            "请扫描 Phone MCP 配对二维码"
        }
        val origin = ConnectionSecurity.httpsOrigin(data.getString("relay_url"))
        val code = data.getString("code")
        require(code.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "无效的配对码" }
        // 端到端密钥随二维码下发，不经过中继。缺失说明二维码来自旧版本。
        val e2eKey = data.optString("e2e_key", "")
        require(e2eKey.length >= 40) { "二维码缺少端到端密钥，请用新版生成" }
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS).build()
        try {
            val request = Request.Builder().url("$origin/phonemcp/pair")
                .post(JSONObject().put("code", code).toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                check(response.code != 401) { "二维码已过期或已使用，请生成新的二维码" }
                check(response.isSuccessful) { "配对失败，请检查中继服务" }
                val result = JSONObject(response.body?.string() ?: error("配对响应为空"))
                prefs.savePairing(
                    origin,
                    result.getString("device_id"),
                    result.getString("device_token"),
                    e2eKey,
                )
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
