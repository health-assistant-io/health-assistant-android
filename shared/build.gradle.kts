// KMP shared core (composite build; consumed by app/android via includeBuild).
// Phase 0–2: kotlin("jvm") only. Converts to kotlin("multiplatform") with android
// + ios targets when platform-specific code lands (Phase 3 / Phase 9). See
// app/android/AGENTS.md "module map".
plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "io.healthassistant"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        // JVM 11 bytecode so the Android :app (Java 11 compat) can consume it.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("io.healthassistant:kotlin-sdk:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.ktor:ktor-client-mock:3.5.2")
}
