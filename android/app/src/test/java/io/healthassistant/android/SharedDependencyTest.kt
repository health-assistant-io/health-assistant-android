package io.healthassistant.android

import io.healthassistant.shared.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the composite `shared` build is wired into `:app` (Phase 0 gate). */
class SharedDependencyTest {
    @Test
    fun sharedResolves() {
        assertEquals("Health Assistant shared core", Platform.NAME)
    }
}
