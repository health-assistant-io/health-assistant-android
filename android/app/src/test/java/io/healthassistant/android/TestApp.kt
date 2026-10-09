package io.healthassistant.android

import android.app.Application

/** Plain [Application] for Robolectric tests — skips the real [HAApplication]
 *  (Koin graph + BiometricPrompt + WorkManager), which cannot boot on the JVM.
 *  Bound globally via src/test/resources/robolectric.properties. */
class TestApp : Application()
