package top.rainaki.phonemcp.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionSecurityTest {
    @Test fun encryptedOriginAccepted() {
        assertEquals("https://example.com:8900", ConnectionSecurity.httpsOrigin("https://example.com:8900/"))
        ConnectionSecurity.requireSecureTunnel("wss://example.com:8900/phonemcp")
    }

    @Test fun unsafeQrOriginsRejected() {
        for (url in listOf("http://example.com", "https://u:p@example.com", "https://example.com?token=x",
            "https://example.com/#x", "https://example.com/path", "https://example.com:99999", "https:///x")) {
            assertThrows(url, IllegalArgumentException::class.java) { ConnectionSecurity.httpsOrigin(url) }
        }
    }

    @Test fun plaintextTunnelAndCredentialsRejected() {
        for (url in listOf("ws://example.com/tunnel", "wss://u:p@example.com/tunnel", "wss://example.com?token=x")) {
            assertThrows(url, IllegalArgumentException::class.java) { ConnectionSecurity.requireSecureTunnel(url) }
        }
    }
}
