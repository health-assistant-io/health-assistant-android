package io.healthassistant.shared.sync

import io.healthassistant.bridge.BridgeClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeSyncSenderTest {

    private fun sender(status: HttpStatusCode) = BridgeSyncSender(
        BridgeClient(
            baseUrl = "https://example.test",
            integrationId = "00000000-0000-0000-0000-000000000000",
            apiSecret = "test-secret-16chars!",
            client = HttpClient(MockEngine { _ -> respond("", status) }),
        ),
    )

    @Test
    fun `2xx is success`() = runBlocking {
        assertTrue(sender(HttpStatusCode.OK).send("POST", "/sync", ByteArray(0)) is SendResult.Success)
    }

    @Test
    fun `503 is transient`() = runBlocking {
        assertTrue(sender(HttpStatusCode.ServiceUnavailable).send("POST", "/sync", ByteArray(0)) is SendResult.Transient)
    }

    @Test
    fun `429 is transient`() = runBlocking {
        val r = sender(HttpStatusCode.TooManyRequests).send("POST", "/sync", ByteArray(0))
        assertTrue(r is SendResult.Transient)
        assertTrue((r as SendResult.Transient).statusCode == 429)
    }

    @Test
    fun `400 is permanent`() = runBlocking {
        assertTrue(sender(HttpStatusCode.BadRequest).send("POST", "/sync", ByteArray(0)) is SendResult.Permanent)
    }
}
