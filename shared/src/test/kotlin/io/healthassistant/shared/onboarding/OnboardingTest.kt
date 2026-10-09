package io.healthassistant.shared.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTest {

    private val uuid = "00000000-0000-0000-0000-000000000000"
    private val secret = "0123456789abcdef" // 16 chars

    // --- QR ----------------------------------------------------------------

    @Test
    fun `qr parses three fields`() {
        val c = Onboarding.parseQr("https://health.example.io|$uuid|$secret")
        assertEquals("https://health.example.io", c?.baseUrl)
        assertEquals(uuid, c?.integrationId)
        assertEquals(secret, c?.apiSecret)
        assertTrue(c!!.hasSecret)
    }

    @Test
    fun `qr parses two fields without secret`() {
        val c = Onboarding.parseQr("http://192.168.1.5:8000|$uuid")
        assertEquals("http://192.168.1.5:8000", c?.baseUrl)
        assertNull(c?.apiSecret)
        assertFalse(c!!.hasSecret)
    }

    @Test
    fun `qr rejects malformed`() {
        assertNull(Onboarding.parseQr("just-one-field"))
        assertNull(Onboarding.parseQr("https://x.example|$uuid|too-short|$secret"))
    }

    @Test
    fun `qr rejects tampered url and short secret`() {
        assertNull(Onboarding.parseQr("not-a-url|$uuid|$secret"))
        assertNull(Onboarding.parseQr("https://x.example|$uuid|short"))
    }

    // --- Deep link ---------------------------------------------------------

    @Test
    fun `deep link parses query-encoded fields`() {
        val uri = "https://health.example.io/connect?base_url=https%3A%2F%2Fhealth.example.io&integration_id=$uuid&api_secret=$secret"
        val c = Onboarding.parseDeepLink(uri)
        assertEquals("https://health.example.io", c?.baseUrl)
        assertEquals(uuid, c?.integrationId)
        assertEquals(secret, c?.apiSecret)
    }

    @Test
    fun `deep link without secret`() {
        val uri = "healthassistant://connect?base_url=https%3A%2F%2Fx.example&integration_id=$uuid"
        val c = Onboarding.parseDeepLink(uri)
        assertNull(c?.apiSecret)
    }

    @Test
    fun `deep link rejects missing fields`() {
        assertNull(Onboarding.parseDeepLink("https://x.example/connect?base_url=https%3A%2F%2Fx.example"))
        assertNull(Onboarding.parseDeepLink("https://x.example/connect"))
    }

    // --- Manual ------------------------------------------------------------

    @Test
    fun `manual validation accepts good input`() {
        val c = Onboarding.validateManual("https://health.example.io", uuid, secret)
        assertEquals(uuid, c?.integrationId)
    }

    @Test
    fun `manual validation rejects bad url and short secret`() {
        assertNull(Onboarding.validateManual("health.example", uuid, secret))
        assertNull(Onboarding.validateManual("https://health.example", "not-a-uuid", secret))
        assertNull(Onboarding.validateManual("https://health.example", uuid, "short"))
    }
}
