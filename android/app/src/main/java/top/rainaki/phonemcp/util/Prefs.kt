package top.rainaki.phonemcp.util

import android.content.Context

/**
 * 本机持久化配置。
 *
 * 只有中继一条连接路径：地址、设备 ID、设备凭据、端到端密钥全部来自扫码配对，
 * 且除地址与设备 ID 外都用 Keystore 加密后落盘。
 *
 * 历史版本曾提供局域网明文直连，相关配置项已在迁移中清除。
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("phone_mcp", Context.MODE_PRIVATE)
    private val secrets = SecretStore()

    init {
        // 清理已废弃的局域网配置与旧版明文凭据。
        // 局域网直连已整体移除，这些键不再有任何读取方，留着只会扩大泄露面。
        val stale = listOf(
            "token", "relay_token", "lan_token_enc", "lan_enabled", "port",
            "auto_start", "relay_url", "relay_token", "device_id",
        )
        val edit = sp.edit()
        stale.forEach { edit.remove(it) }
        check(edit.commit()) { "清理旧配置失败" }
    }

    val relayUrl: String get() = sp.getString("paired_url", "").orEmpty()
    val relayToken: String get() = sp.getString("paired_token_enc", null)?.let(secrets::decrypt).orEmpty()
    val relayConfigured: Boolean get() = relayUrl.isNotBlank() && sp.contains("paired_token_enc")
    val deviceId: String get() = sp.getString("paired_device_id", "").orEmpty()

    /** 端到端密钥。与电脑端同一把，中继看不到。 */
    val e2eKey: String get() = sp.getString("paired_e2e_enc", null)?.let(secrets::decrypt).orEmpty()

    fun savePairing(origin: String, deviceId: String, token: String, e2eKey: String) {
        val url = ConnectionSecurity.httpsOrigin(origin)
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "设备 ID 格式无效" }
        require(token.length >= 32) { "设备凭据无效" }
        require(e2eKey.length >= 40) { "端到端密钥无效" }
        check(
            sp.edit()
                .putString("paired_url", url.replaceFirst("https://", "wss://") + "/phonemcp")
                .putString("paired_device_id", deviceId)
                .putString("paired_token_enc", secrets.encrypt(token))
                .putString("paired_e2e_enc", secrets.encrypt(e2eKey))
                .commit()
        ) { "保存配对失败" }
    }

    fun clearPairing() {
        check(
            sp.edit()
                .remove("paired_url").remove("paired_device_id")
                .remove("paired_token_enc").remove("paired_e2e_enc")
                .commit()
        )
    }
}
