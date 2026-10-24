package top.rainaki.phonemcp.util

import java.net.URI

object ConnectionSecurity {
    fun httpsOrigin(raw: String): String {
        val url = URI(raw)
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.rawUserInfo == null
            && url.rawQuery == null && url.rawFragment == null && url.rawPath in listOf("", "/")
            && (url.port == -1 || url.port in 1..65535)) { "配对地址必须是有效的 HTTPS 源地址" }
        return raw.trimEnd('/')
    }

    fun requireSecureTunnel(raw: String) {
        val url = URI(raw)
        require(url.scheme == "wss" && !url.host.isNullOrBlank() && url.rawUserInfo == null
            && url.rawQuery == null && url.rawFragment == null
            && (url.port == -1 || url.port in 1..65535)) { "中继只允许 WSS 加密连接" }
    }
}
