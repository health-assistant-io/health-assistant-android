package io.healthassistant.shared.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PublicConfigTest {
    @Test
    fun `reachable frontend origin is used as-is`() {
        val resolved =
            PublicConfig.resolveFrontendOrigin(
                frontendBaseUrl = "http://10.0.0.5:3000",
                clientBaseUrl = "http://10.0.0.5:8000",
                connectionBaseUrl = "http://10.0.0.5:8000",
            )

        assertEquals("http://10.0.0.5:3000", resolved)
    }

    @Test
    fun `loopback frontend origin is rewritten to the connection host keeping its port`() {
        val resolved =
            PublicConfig.resolveFrontendOrigin(
                frontendBaseUrl = "http://localhost:3000",
                clientBaseUrl = "http://10.0.0.5:8000",
                connectionBaseUrl = "http://10.0.0.5:8000",
            )

        assertEquals("http://10.0.0.5:3000", resolved)
    }

    @Test
    fun `frontend falls back to client connect url when absent`() {
        val resolved =
            PublicConfig.resolveFrontendOrigin(
                frontendBaseUrl = null,
                clientBaseUrl = "http://10.0.0.5:8000",
                connectionBaseUrl = "http://10.0.0.5:8000",
            )

        assertEquals("http://10.0.0.5:8000", resolved)
    }

    @Test
    fun `null advertised origins resolve to null`() {
        assertNull(
            PublicConfig.resolveFrontendOrigin(
                frontendBaseUrl = null,
                clientBaseUrl = null,
                connectionBaseUrl = "http://10.0.0.5:8000",
            ),
        )
        assertNull(
            PublicConfig.resolveFrontendOrigin(
                frontendBaseUrl = "  ",
                clientBaseUrl = "  ",
                connectionBaseUrl = "http://10.0.0.5:8000",
            ),
        )
    }
}
