package io.healthassistant.android.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Gate for the pure URL join the web hand-off entry points rely on. */
class WebAppLauncherTest {
    @Test
    fun blank_or_null_origin_yields_null() {
        assertNull(webAppUrl(null))
        assertNull(webAppUrl(""))
        assertNull(webAppUrl("   "))
    }

    @Test
    fun non_http_origin_yields_null() {
        assertNull(webAppUrl("healthassistant://connect"))
        assertNull(webAppUrl("192.168.1.50:8000"))
    }

    @Test
    fun bare_origin_returns_trimmed_origin() {
        assertEquals("https://health.example", webAppUrl("https://health.example"))
        assertEquals("https://health.example", webAppUrl("https://health.example/"))
        assertEquals("http://10.0.0.2:8000", webAppUrl("http://10.0.0.2:8000/"))
    }

    @Test
    fun path_is_joined_with_single_slash() {
        assertEquals("https://health.example/ai-assistant", webAppUrl("https://health.example", "ai-assistant"))
        assertEquals("https://health.example/ai-assistant", webAppUrl("https://health.example/", "/ai-assistant"))
        assertEquals(
            "https://health.example/examinations/e1",
            webAppUrl("https://health.example//", "examinations/e1"),
        )
    }

    @Test
    fun blank_path_returns_origin_only() {
        assertEquals("https://health.example", webAppUrl("https://health.example", ""))
        assertEquals("https://health.example", webAppUrl("https://health.example/", "  "))
    }
}
