plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure-JVM shared module. Deliberately has no Android dependency so both the
// watch app (:app, targetSdk 28) and the companion (:companion, targetSdk 35)
// can consume it without pulling one module's Android constraints into the
// other. Keep it that way — anything needing android.* belongs in a separate
// :core:auth-style android-library module.
kotlin {
    jvmToolchain(11)
}

dependencies {
    // Gson is a pure-JVM library — safe to depend on from :core without
    // dragging Android in. Enables the shared wire models to carry
    // @SerializedName and to be constructed by the same GsonHolder that
    // watch and companion both use for reflection-based (de)serialization.
    api(libs.gson)

    // OkHttp is a pure-JVM library, so DebugWebhookInterceptor can live here
    // and be shared by watch and companion rather than duplicated in both.
    // This does not weaken the no-Android rule above: okhttp pulls in no
    // android.* API, and both modules already depend on it via Retrofit.
    api(libs.okhttp)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
}
