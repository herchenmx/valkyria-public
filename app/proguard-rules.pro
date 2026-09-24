# ProGuard / R8 rules for the sideloaded Wear OS app.
#
# Minify is ON (build.gradle.kts release { isMinifyEnabled = true }) to shrink
# the dex/class count on the 512 MB-class watch. Most of the app survives R8
# unchanged because Compose / Retrofit / OkHttp / Tink ship their own consumer
# rules via their AARs; the rules below cover the gaps R8 can't infer:
# reflection-driven Gson (model field names + no-arg construction).

# Keep generic signatures + annotations so Gson's TypeToken / @SerializedName
# and Retrofit's parameterized return types resolve after shrinking.
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep crash-stack line numbers legible (sideload-only; no mapping upload).
-keepattributes SourceFile,LineNumberTable

# ── Gson ─────────────────────────────────────────────────────────────────────
# Gson is a plain jar (no consumer rules). Without these, R8 renames model
# fields -> wrong JSON keys, or strips fields it thinks are unused (Gson sets
# them reflectively). Keep the @SerializedName members, and keep the API /
# domain model packages whole -- these are the types crossing the JSON boundary
# (request bodies + response DTOs) for both the public v1 and private v2 APIs.
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.example.hevywatch.data.api.model.** { *; }
-keep class com.example.hevywatch.data.model.** { *; }

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

# ── Log stripping ────────────────────────────────────────────────────────────
# Every android.util.Log.d/v/i call is a JNI hop + a String alloc even in
# release. Tell R8 the calls have no side effects so it can prune them (and
# the arguments they built) from the release DEX. Errors and warnings stay
# because those are what shows up in adb logcat after a crash.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# ── Tink / EncryptedSharedPreferences ────────────────────────────────────────
# androidx.security-crypto bundles Tink, which is reflection + protobuf heavy.
# Its consumer rules usually suffice, but pin the keyset/config classes and
# silence warnings about optional providers it never loads at runtime.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
