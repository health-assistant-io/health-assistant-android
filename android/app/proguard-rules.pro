# Phase B.2: R8 keep rules for release builds.
#
# The app uses several libraries that reach reflectively into Kotlin/Java
# classes — if R8 strips or renames those classes, the app crashes at runtime
# (silent because the release build hides stacktrace detail). These rules
# keep the reflective surfaces intact while still letting R8 shrink + obfuscate
# everything else.

# --- androidx.compose ---
# Compose ships its own keep rules via consumer-rules, but the runtime needs
# the Composable lambdas + @Stable marker classes intact. Bundled.
-dontwarn androidx.compose.**

# --- kotlinx.serialization ---
# Models annotated @Serializable get a generated serializer that R8 would
# otherwise strip. The plugin ships consumer rules for the runtime; this is
# the safety net for our own @Serializable model classes (ClientRecord,
# ObservationPoint, DocumentSummary, Medication, Allergy, …).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.healthassistant.** {
    *** Companion;
}
-keepclasseswithmembers class io.healthassistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep the @Serializable model classes themselves (kotlinx.serialization's
# plugin generates the serializer on the companion; the lookup is reflective).
-keep,includedescriptorclasses class io.healthassistant.**$$serializer { *; }
-keepclassmembers class io.healthassistant.** {
    *** INSTANCE;
}

# --- Ktor (the kotlin-sdk HTTP client) ---
# Ktor uses HttpClient engines + plugins that are loaded reflectively. The
# CIO engine we ship is JVM-only and has its own consumer rules; this covers
# the plugins we use (HttpTimeout, content negotiation).
-dontwarn io.ktor.**
-keepnames class io.ktor.** { *; }
-keepnames class io.healthassistant.bridge.** { *; }

# --- Koin (DI) ---
# Koin resolves by name; the module definitions must survive.
-dontwarn org.koin.**
-keep class org.koin.** { *; }
-keepnames class io.healthassistant.android.di.**

# --- Room (M8 on-device cache) ---
# Room generates impl classes at compile time via KSP; the generated classes
# reference the @Dao interfaces + @Entity models by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.**

# --- Health Connect ---
# Connect-client usesParcelable + AIDL; keep the record types the source reads.
-keep class androidx.health.connect.client.records.** { *; }
-dontwarn androidx.health.connect.**

# --- Coil (image loading) ---
# Bundled consumer rules; the only extra is keeping our SvgDecoder if we
# ever add one. None today.
-dontwarn coil.**

# --- Markwon (rich-text rendering) ---
# Pure span-based TextView rendering, no reflection; commonmark-java is plain
# Java. Spans must keep their class names (TextView span rendering is not
# reflective, but obfuscated span classes break spannable persistence).
-keep class io.noties.markwon.** { *; }
-keep class org.commonmark.** { *; }
-dontwarn io.noties.markwon.**
-dontwarn org.commonmark.**

# --- GMS code scanner / ML Kit barcode (QR onboarding) ---
# The scanner boots through the Firebase-component runtime (MlKitContext ->
# ComponentRuntime.get(SharedPrefManager.class)); with R8 on, that component
# never registers and tapping "Scan QR code" NPEs inside
# gms.internal.mlkit_code_scanner.zzny.<init> (getClass() on the null
# SharedPrefManager). Keep the whole ML Kit surface.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }

# --- SQLCipher (Phase C — encrypted Room DBs) ---
# libsqlcipher.so's JNI_OnLoad resolves the database classes by NAME via
# JNI FindClass (register_android_database_*) — if R8 renames them the load
# aborts the process (SIGABRT in JNI_OnLoad, seen on the first run of a
# minified release build). Keep the whole surface.
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }

# --- OkHttp / Conscrypt (transitive via some Ktor engines + Coil) ---
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**

# --- Kotlin metadata + reflection (kotlin-reflect is used by serialization) ---
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,Signature,EnclosingMethod,InnerClasses,SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
