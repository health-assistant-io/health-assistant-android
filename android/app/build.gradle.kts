plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.baselineprofile)
}

android {
    namespace = "io.healthassistant.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.healthassistant.android"
        minSdk = 28
        targetSdk = 37
        versionCode = 3
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // ktlint's experimental flag must stay off for the debug build
            // (ktlintCheck enforces separately). No special minify config.
            isMinifyEnabled = false
        }
        release {
            // Phase L.1/L.6 — sign release builds with the debug keystore so
            // the baseline-profile plugin's nonMinifiedRelease build can
            // install on a device/emulator for generation; prod signing is a
            // release-management step (plan L.6). CI release builds override
            // via -Pkeystore* project properties fed from repo secrets (see
            // .github/workflows/release.yml); absent those, debug signing.
            val keystoreFile = providers.gradleProperty("keystoreFile").orNull
            signingConfig =
                if (keystoreFile != null) {
                    signingConfigs.create("releaseCi") {
                        storeFile = file(keystoreFile)
                        storePassword = providers.gradleProperty("keystoreStorePassword").get()
                        keyAlias = providers.gradleProperty("keystoreAlias").get()
                        keyPassword = providers.gradleProperty("keystoreKeyPassword").get()
                    }
                } else {
                    signingConfigs.getByName("debug")
                }
            // Phase B.2: R8 + resource shrinking on for release. Was disabled
            // (optimization { enable = false }) which shipped a 2-3x larger
            // APK + left every reflective symbol reachable — a security and
            // size bug. The keep rules (proguard-rules.pro) cover the
            // reflective access patterns of kotlinx.serialization, Ktor, Koin,
            // and Room; verified by assembleRelease + installRelease before
            // each release tag.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        getByName("debug") {
            assets.srcDir("schemas")
        }
        getByName("test") {
            assets.srcDir("schemas")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("io.healthassistant:shared:0.1.0")
    implementation("io.healthassistant:kotlin-sdk:0.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.gms.code.scanner)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.health.connect)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.coil.compose)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.vico.compose.m3)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Phase C — SQLCipher encrypts the on-device Room DBs (observation cache +
    // outbox) at rest. Key derived from a Keystore-backed master key (see
    // DatabaseKeyProvider) and passed to Room via SupportFactory.
    implementation(libs.sqlcipher.android)
    // Phase I — UnifiedPush native push receiver + registration. The user picks
    // a distributor app (ntfy, Gotify…); UnifiedPush.register() asks it for an
    // endpoint we POST to /notifications/register-device. Exclude Tink — the
    // connector pulls com.google.crypto.tink:tink for its (unused, v2) E2E
    // encryption, which collides with the tink-android that security-crypto
    // already brings (duplicate classes). v2 ships plaintext-over-HTTPS push.
    implementation(libs.unifiedpush.connector) {
        exclude(group = "com.google.crypto.tink")
    }
    // Rich clinical text (exam notes / impressions / biomarker info): shared
    // layer detects HTML/Markdown/plain; HTML is converted to Markdown, and
    // Markwon renders Markdown (GFM tables + linkify) into a TextView via the
    // RichText composable. No WebView is ever used for server content.
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.linkify)
    // Phase L.1 — Baseline Profiles: profileinstaller compiles the packaged
    // baseline profile on first run for sideloaded (non-Play) installs, and
    // exposes the SAVE_PROFILE receiver used by profile generation runs.
    implementation(libs.androidx.profileinstaller)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.turbine)
    // Robolectric Compose tests (the device instrumentation never runs on the
    // MIUI test device — these run the same createComposeRule() tests as part
    // of the JVM gate on every build).
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.compose.ui.test.manifest)
    baselineProfile(project(":baselineprofile"))
}
