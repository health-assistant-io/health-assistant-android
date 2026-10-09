package io.healthassistant.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformTest {
    @Test
    fun name() {
        assertEquals("Health Assistant shared core", Platform.NAME)
    }
}
