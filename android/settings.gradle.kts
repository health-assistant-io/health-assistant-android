pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Composite build: the KMP shared core at ../shared (group io.healthassistant).
includeBuild("../shared")

// Composite build: the Kotlin bridge SDK (group io.healthassistant, module bridge).
// Default resolves from the sibling core/ checkout (two-repo model). CI and
// other layouts override with HA_KOTLIN_SDK_DIR (docs/dev/development.md).
val kotlinSdkDir =
    providers.environmentVariable("HA_KOTLIN_SDK_DIR").orNull
        ?: "../../core/integrations/health_assistant_bridge/kotlin-sdk"
includeBuild(kotlinSdkDir)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Health Assistant"
include(":app", ":baselineprofile")
