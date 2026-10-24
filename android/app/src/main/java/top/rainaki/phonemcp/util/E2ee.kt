package top.rainaki.phonemcp.util

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 端到端加密。中继只转发密文，看不到指令与截图。
 *
 * 密钥来自配对二维码里的 `e2e_key`，与电脑端同一把；**从不发给中继**。
 *
 * 与 Python 端 `server/phone_mcp/e2ee.py` 必须完全一致：
 *   * AES-256-GCM，12 字节随机 nonce
 *   * 明文 = nonce || ciphertext || tag（GCM 的 tag 附在密文尾部）
 *   * AAD = "device_id\npath"
 *   * base64url 无填充
 *
 * 任何不一致都会导致解密失败——这是好事，说明中继篡改或密钥不匹配。
 */
object E2ee {

    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32

    private val random = SecureRandom()

    private fun decodeKey(key: String): ByteArray {
        val padded = key + "=".repeat((4 - key.length % 4) % 4)
        val raw = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
        require(raw.size == KEY_BYTES) { "端到端密钥长度不对" }
        return raw
    }

    private fun aad(deviceId: String, path: String): ByteArray =
        "$deviceId\n$path".toByteArray(Charsets.UTF_8)

    /** 解密一个请求体。失败抛异常，绝不静默返回空对象。 */
    fun open(key: String, deviceId: String, path: String, blob: String): JSONObject {
        val padded = blob + "=".repeat((4 - blob.length % 4) % 4)
        val raw = Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
        require(raw.size > NONCE_BYTES) { "密文长度不足" }

        val nonce = raw.copyOfRange(0, NONCE_BYTES)
        val ct = raw.copyOfRange(NONCE_BYTES, raw.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(decodeKey(key), "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad(deviceId, path))
        val plain = cipher.doFinal(ct)
        return JSONObject(String(plain, Charsets.UTF_8))
    }

    /** 加密响应体。 */
    fun seal(key: String, deviceId: String, path: String, payload: JSONObject): String {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(decodeKey(key), "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad(deviceId, path))
        val ct = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(nonce + ct, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** 加密二进制（截图）。 */
    fun sealBytes(key: String, deviceId: String, path: String, payload: ByteArray): String {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(decodeKey(key), "AES"),
            GCMParameterSpec(TAG_BITS, nonce),
        )
        cipher.updateAAD(aad(deviceId, path))
        val ct = cipher.doFinal(payload)
        return Base64.encodeToString(nonce + ct, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
