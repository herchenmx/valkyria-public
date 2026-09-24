# ProGuard / R8 rules for the sideloaded companion (phone) app.
#
# Minify is ON (build.gradle.kts release { isMinifyEnabled = true }) purely to
# shrink the APK — unminified it was ~39 MB, virtually all of it unused
# dependency code (play-services-wearable, Compose, Coil, Retrofit/OkHttp)
# shipped whole. R8 tree-shakes that down.
#
# UNLIKE the watch, this module stays debuggable (isDebuggable = true) for the
# `run-as` / JDWP / Log.d diagnostic surface, so these rules deliberately
# DIVERGE from app/proguard-rules.pro:
#   - `-dontobfuscate`: keep class/method names, so `run-as` inspection,
#     reflection-by-name (Gson field names, WorkManager worker class name), and
#     crash stacktraces all stay readable. Shrinking (the size win) is
#     independent of obfuscation, so we keep the win without the opacity.
#   - NO `-assumenosideeffects class android.util.Log`: the watch strips
#     Log.d/v/i; here we keep them, since visible companion logging is the
#     whole point of the debuggable build.

# Keep generic signatures + annotations so Gson's TypeToken / @SerializedName
# and Retrofit's parameterized return types resolve after shrinking.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep crash-stack line numbers legible (sideload-only; no mapping upload).
-keepattributes SourceFile,LineNumberTable

# Names stay intact — see the header. Cheap insurance for every reflection path
# and the debuggable diagnostic surface; the size win comes from shrinking.
-dontobfuscate

# ── Gson ─────────────────────────────────────────────────────────────────────
# Gson is a plain jar (no consumer rules). Without these, R8 strips model
# fields/classes it thinks are unused (Gson sets them reflectively). Keep the
# @SerializedName members, and keep the DTO-bearing packages whole — these are
# the request bodies + response DTOs crossing the JSON boundary (companion's
# public v1 API, the M&M generator models, the wear token-bridge payloads, and
# the shared :core auth models).
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.example.hevycompanion.data.** { *; }
-keep class com.example.hevycompanion.generate.mm.** { *; }
-keep class com.example.hevycompanion.wear.** { *; }
-keep class com.example.hevycore.** { *; }

# Gson's own generic-type and adapter machinery.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Preserve no-arg constructors so Gson can instantiate models reflectively.
-keepclassmembers,allowobfuscation class * {
    <init>();
}

# ── Retrofit (belt-and-suspenders on top of its bundled rules) ───────────────
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# ── WorkManager ──────────────────────────────────────────────────────────────
# androidx.work ships consumer rules that keep ListenableWorker subclasses, but
# pin TokenRefreshWorker explicitly — WorkManager instantiates it by name via
# reflection, so it must survive shrinking with its (Context, WorkerParameters)
# constructor intact.
-keep class com.example.hevycompanion.TokenRefreshWorker { *; }

# ── Tink / EncryptedSharedPreferences ────────────────────────────────────────
# androidx.security-crypto bundles Tink, which is reflection + protobuf heavy.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
