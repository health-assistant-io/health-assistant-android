package io.healthassistant.android

import io.healthassistant.android.di.appModule
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/** Phase 0: proves the Koin dependency resolves and starts cleanly.
 *  stopKoin in a finally so a failure here can't poison every other test
 *  that shares the JVM (KoinApplicationAlreadyStartedException). */
class KoinSmokeTest {
    @Test
    fun startsWithAppModule() {
        try {
            startKoin { modules(appModule) }
        } finally {
            stopKoin()
        }
    }
}
