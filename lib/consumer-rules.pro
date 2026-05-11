# =============================================================================
# Consumer ProGuard / R8 rules for spark-kotlin-sdk.
#
# These rules ship with the published AAR and are applied automatically to any
# Android app that minifies its release build (`isMinifyEnabled = true`).
# Consumers should NOT need to copy any of this into their own proguard files.
# =============================================================================

# -----------------------------------------------------------------------------
# Public SDK surface
# -----------------------------------------------------------------------------
# Keep the public API so reflection-based clients (samples, integration tests,
# tooling) and Kotlin's signature metadata continue to work.
-keep public class gy.pig.spark.** { public *; }
-keepclassmembers public class gy.pig.spark.** { public *; }

# -----------------------------------------------------------------------------
# Generated protobuf-lite + gRPC stubs (gy.pig.spark depends on these)
#
# protobuf-lite uses reflection on the parser/defaultInstance fields, and gRPC
# loads service descriptors via Class.forName at runtime.
# -----------------------------------------------------------------------------
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
    <methods>;
}
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite$Builder {
    <fields>;
    <methods>;
}

# gRPC stubs and descriptors
-keep class io.grpc.** { *; }
-keep class spark.** { *; }
-keep class spark_token.** { *; }
-keep class spark_authn.** { *; }

# OkHttp gRPC transport pulls in conscrypt / okio reflectively
-dontwarn io.grpc.netty.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# -----------------------------------------------------------------------------
# UniFFI + JNA bindings for the FROST native library
#
# JNA uses reflection over `Structure` subclasses and field declarations.
# UniFFI's generated Kotlin code references native function pointers by name.
# -----------------------------------------------------------------------------
-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure {
    <fields>;
    public *;
}

-keep class uniffi.spark_frost.** { *; }
-keep class gy.pig.spark.frost.uniffi.** { *; }

# -----------------------------------------------------------------------------
# Kotlin coroutines + Flow
# -----------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}

# -----------------------------------------------------------------------------
# BouncyCastle (provider registration, optional algorithms loaded reflectively)
# -----------------------------------------------------------------------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# -----------------------------------------------------------------------------
# OkHttp / Okio — known safe `-dontwarn` set published by Square.
# -----------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn javax.annotation.**

# -----------------------------------------------------------------------------
# Native methods (JNI entry points must not be renamed or stripped)
# -----------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}
