package top.rainaki.phonemcp.util

import androidx.test.platform.app.InstrumentationRegistry
import junit.framework.TestCase

class CredentialsTest : TestCase() {
    fun testKeystoreRoundTripAndTamperDetection() {
        val store = SecretStore()
        val secret = "test-only-device-credential-1234567890"
        val first = store.encrypt(secret)
        assertEquals(secret, SecretStore().decrypt(first))
        assertFalse(first.contains(secret))
        assertFalse(first == store.encrypt(secret))
        val damaged = first.toCharArray().also { it[20] = if (it[20] == 'A') 'B' else 'A' }.concatToString()
        try {
            store.decrypt(damaged)
            fail("Tampered ciphertext must be rejected")
        } catch (_: java.security.GeneralSecurityException) {
            // AES-GCM authentication rejects modification.
        }
    }

    fun testMigrationPairingAndClear() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val raw = context.getSharedPreferences("phone_mcp", 0)
        raw.edit().clear().putString("token", "legacy-lan-token")
            .putString("relay_token", "legacy-shared-token").putBoolean("auto_start", true).commit()
        try {
            val prefs = Prefs(context)
            assertFalse(prefs.autoStart)
            assertFalse(prefs.relayConfigured)
            assertEquals("legacy-lan-token", prefs.token)
            assertFalse(raw.contains("token"))
            assertFalse(raw.contains("relay_token"))
            prefs.savePairing("https://example.com", "phone-test", "new-device-token-12345678901234567890")
            val reopened = Prefs(context)
            assertTrue(reopened.relayConfigured)
            assertEquals("new-device-token-12345678901234567890", reopened.relayToken)
            assertEquals("wss://example.com/phonemcp", reopened.relayUrl)
            assertFalse(raw.all.values.any { it.toString().contains("new-device-token") })
            reopened.clearPairing()
            assertFalse(Prefs(context).relayConfigured)
        } finally {
            raw.edit().clear().commit()
        }
    }
}
