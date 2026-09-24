import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) load(f.inputStream())
}

// Resolved once, and reported at configure time: a build that signs with the
// wrong key should say so while it is happening, not weeks later at install.
val debugKeystore = run {
    val explicit = System.getenv("HEVY_DEBUG_KEYSTORE")
        ?: (findProperty("hevy.debugKeystore") as String?)
    file(explicit ?: "${System.getProperty("user.home")}/.android/debug.keystore")
}
if (debugKeystore.isFile) {
    logger.lifecycle("signing: using keystore $debugKeystore")
} else {
    logger.warn("signing: NO keystore at $debugKeystore -- AGP will generate a " +
        "throwaway one, and the resulting APK will NOT install over an existing build")
}

android {
    namespace = "com.example.hevycompanion"
    compileSdk = 36

    defaultConfig {
        // The companion app runs on a modern phone (Pixel 7a etc.) — its SDK
        // targets are independent of the watch and SHOULD NOT be aligned to
        // the watch's API 28 floor. The watch app at /app is constrained by
        // its hardware (Kate Spade Scallop 2, Wear OS 2.5); the companion is
        // not.
        // applicationId MUST match the watch's applicationId
        // (com.example.hevywatch). Wearable MessageAPI routes incoming
        // messages on the watch to PhoneAuthService only when the sender's
        // package matches an installed app on the receiver — with mismatched
        // applicationIds, /auth_tokens and /on_phone_authenticated never
        // reach the watch, even though MessageClient.sendMessage() reports
        // success and connectedNodes() still returns the paired watch.
        // (Capability advertisements are the alternative; the matching
        //  applicationId is the simpler bridge for a 1:1 paired sideload.)
        applicationId = "com.example.hevywatch"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "HEVY_PUBLIC_API_KEY",
            "\"${secrets.getProperty("HEVY_PUBLIC_API_KEY", "")}\"")
        buildConfigField("String", "HEVY_PRIVATE_API_KEY",
            "\"${secrets.getProperty("HEVY_PRIVATE_API_KEY", "")}\"")
        // Same debug webhook as the watch — one URL receives both, tagged by
        // device, so a resume can be compared across the two in one feed.
        // Bodies only; never headers. Empty (the default) disables it. Set via
        // secrets.properties / the HEVY_DEBUG_WEBHOOK_URL Actions secret, so a
        // dead bin is swapped in settings rather than in code.
        val debugWebhookUrl = secrets.getProperty("HEVY_DEBUG_WEBHOOK_URL", "").trim()
        require(!debugWebhookUrl.contains('"') && !debugWebhookUrl.contains('\\')) {
            "HEVY_DEBUG_WEBHOOK_URL must not contain quotes or backslashes — it is " +
                "embedded in a generated Java string literal."
        }
        buildConfigField("String", "HEVY_DEBUG_WEBHOOK_URL", "\"$debugWebhookUrl\"")
        // Fine-grained PAT used by HevyApiVersionSync to fetch
        // api-versions/active.json from the private repo on cold start. Scope:
        // Contents:Read on this repo only. Empty string disables the
        // auto-sync (manual SET_API_VERSION ADB broadcast remains the
        // fallback). The token leaks no information beyond what reverse-
        // engineering the (debuggable) APK would already give.
        buildConfigField("String", "HEVY_API_VERSION_GITHUB_TOKEN",
            "\"${secrets.getProperty("HEVY_API_VERSION_GITHUB_TOKEN", "")}\"")
    }

    // Sign with an EXPLICIT keystore path rather than leaving AGP to find one.
    //
    // AGP's implicit debug-keystore lookup resolves ANDROID_USER_HOME, then the
    // legacy ANDROID_SDK_HOME, then ~/.android. On a machine where either env
    // var is set — CI runners set Android paths liberally — a keystore dropped
    // in ~/.android is never read, and AGP silently generates its own instead.
    // Nothing fails, nothing is logged: the APK is simply signed with a key
    // nobody chose, and it stays invisible until an install dies with
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE against a device that has the real one.
    //
    // Defaults to exactly the path a local build has always used, so the Mac is
    // unaffected; HEVY_DEBUG_KEYSTORE (or -Phevy.debugKeystore) overrides it,
    // which is how CI pins it to the file it just materialized.
    signingConfigs {
        getByName("debug") {
            if (debugKeystore.isFile) {
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8 code + resource shrinking — purely to shrink the APK. Unminified
            // this module was ~39 MB, almost entirely unused dependency code
            // (play-services-wearable, Compose, Coil, Retrofit/OkHttp) shipped
            // whole; R8 tree-shakes it down. Keep-rules for the Gson models,
            // Retrofit, WorkManager, and Tink live in proguard-rules.pro; the
            // 606 dynamically-loaded liftoff_ex_* drawables are pinned in
            // res/raw/keep.xml so resource shrinking can't strip them.
            isMinifyEnabled = true
            isShrinkResources = true
            // STILL debuggable, unlike the watch. Companion is sideload-only
            // (never Play Store) and this preserves the diagnostic surface:
            // `run-as com.example.hevywatch` for inspecting EncryptedSharedPreferences
            // and hevy_api_version.xml, JDWP attach, and visible Log.d/w output
            // from HevyApiVersionSync + HevyCompanionApp. proguard-rules.pro sets
            // `-dontobfuscate` and omits the watch's Log-stripping rule precisely
            // so shrinking doesn't cost us any of that. The watch does the
            // OPPOSITE (isMinifyEnabled + isDebuggable = false in
            // app/build.gradle.kts) so ART can AOT-compile it — the two modules
            // still diverge on debuggability on purpose; only the size posture
            // is now shared.
            //
            // Debuggable is independent of signing: both modules sign release
            // with the *debug keystore* (~/.android/debug.keystore, password
            // "android") because these are sideloaded, never Play-distributed.
            // Signing with that keystore does not make a build debuggable.
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.play.services.wearable)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    // EncryptedSharedPreferences for at-rest token encryption
    implementation(libs.androidx.security.crypto)
    // Coil for loading Hevy CDN thumbnails at runtime (see HevyImageUrlMap).
    // Coil 2.x (stable) instead of 3.x — simpler API, includes its own OkHttp
    // network layer, and our minSdk=28 is well within Coil 2's API-21+ support.
    implementation("io.coil-kt:coil-compose:2.7.0")

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.gson)
    // WorkManagerTestInitHelper for unit tests that exercise code paths
    // calling WorkManager.getInstance() (e.g. the widget refresh handler
    // delegates to TokenRefreshWorker.runOnce, which would otherwise throw
    // "WorkManager is not initialized" in the Robolectric sandbox).
    testImplementation("androidx.work:work-testing:2.10.0")
}
