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
    namespace = "com.example.hevywatch"
    // compileSdk can be modern — only the device-side API floor (minSdk) is
    // hardware-bound. Keeping compileSdk current lets us use newer AndroidX
    // source-level symbols even though runtime calls stay within minSdk.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.hevywatch"
        // HARDWARE CONSTRAINT — DO NOT RAISE.
        // The target device is a Kate Spade Scallop 2 running Wear OS 2.5
        // (API 28). minSdk MUST stay at 28; bumping it bricks the install.
        // The companion app at /companion can target a modern SDK because it
        // runs on a phone — these two app modules intentionally differ.
        minSdk = 28
        // targetSdk MUST stay at 28 too. Bumping it to 35 (which had drifted
        // for a while) silently disables `WifiManager.setWifiEnabled` from
        // sideloaded apps (the framework restricts the call to apps with
        // targetSdk < Q regardless of device API). Phase E's WiFi-suppression
        // during workouts depends on this API working. The companion app at
        // /companion is unconstrained — it runs on a phone.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "HEVY_PUBLIC_API_KEY",
            "\"${secrets.getProperty("HEVY_PUBLIC_API_KEY", "")}\"")
        buildConfigField("String", "HEVY_PRIVATE_API_KEY",
            "\"${secrets.getProperty("HEVY_PRIVATE_API_KEY", "")}\"")
        // Debug webhook for mirroring workout request/response pairs off the
        // watch, so a failed save can be read from a phone browser instead of
        // adb logcat at a desk. Bodies only; never headers — see DebugWebhook's
        // class comment. Empty (the default) disables it entirely.
        //
        // Kept in secrets.properties, and mirrored as the Actions secret
        // HEVY_DEBUG_WEBHOOK_URL, so a bin that goes dead can be swapped in
        // GitHub's settings without a code change and a republish.
        val debugWebhookUrl = secrets.getProperty("HEVY_DEBUG_WEBHOOK_URL", "").trim()
        require(!debugWebhookUrl.contains('"') && !debugWebhookUrl.contains('\\')) {
            "HEVY_DEBUG_WEBHOOK_URL must not contain quotes or backslashes — it is " +
                "embedded in a generated Java string literal."
        }
        buildConfigField("String", "HEVY_DEBUG_WEBHOOK_URL", "\"$debugWebhookUrl\"")
        // (Wi-Fi steering priority moved to WifiPriorityStore + the
        // SET_WIFI_PRIORITY ADB broadcast — change without rebuilding.)

        // Default Hevy-App-Version / Hevy-App-Build header values used by the
        // private v2 interceptor on a fresh install (before any sharedPrefs
        // override has been written). Bump these when the apkmirror archive
        // workflow detects a new version AND that new version has been probed
        // and promoted to api-versions/active.json — the values here become the
        // baked-in fallback for fresh installs and the matching watch APK
        // build. Runtime override lives in HevyAppVersionStore (sharedPrefs),
        // settable via the SET_API_VERSION ADB broadcast or the /api_version
        // DataClient push from the companion.
        buildConfigField("String", "DEFAULT_HEVY_APP_VERSION", "\"3.0.12\"")
        buildConfigField("String", "DEFAULT_HEVY_APP_BUILD", "\"2032997\"")
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
            // R8 code + resource shrinking. The watch runs on Snapdragon Wear
            // 2100-class hardware (~512 MB RAM); minify strips unused classes
            // (most of Guava, Compose tooling, play-services) so far fewer
            // classes load and the dex/APK shrink dramatically. Keep rules for
            // the Gson models + Retrofit + Tink live in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            // NON-debuggable so ART can fully AOT-compile the app — a
            // debuggable build is locked to the interpreter/JIT, the single
            // biggest CPU penalty on the old watch. Tradeoff: `run-as` /
            // JDWP diagnostics no longer work on the installed APK; build a
            // throwaway debuggable variant manually if you need to dump the
            // bearer token.
            //
            // Signing is a separate axis: release is signed with the *debug
            // keystore* (~/.android/debug.keystore, password "android") because
            // the app is sideloaded via adb, never Play-distributed. That does
            // NOT make the build debuggable — this module is genuinely
            // non-debuggable, unlike the companion, which sets
            // isDebuggable = true on purpose.
            isDebuggable = false
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

    lint {
        // This is a sideloaded Wear OS 2 app, never published to Play. The
        // hardware floor (Kate Spade Scallop 2, API 28) pins targetSdk to 28
        // for runtime-behaviour reasons (Phase E's WifiManager.setWifiEnabled
        // needs targetSdk < Q). Suppress the Play-Store-policy lint check
        // that would otherwise fail the release build.
        disable += "ExpiredTargetSdkVersion"
    }
}

// Gate: assembleRelease requires all unit tests to pass first.
// This prevents shipping an APK when tests are broken.
afterEvaluate {
    tasks.named("assembleRelease") {
        dependsOn("testDebugUnitTest")
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
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Wear OS
    implementation(libs.play.services.wearable)

    // Compose for Wear OS
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.input)
    implementation(libs.wear.legacy)
    implementation(libs.fragment)

    // Horologist (Wear OS layout utilities)
    implementation(libs.horologist.compose.layout)

    // Tiles
    implementation(libs.wear.tiles)
    implementation(libs.guava)

    // EncryptedSharedPreferences for at-rest token encryption
    implementation(libs.androidx.security.crypto)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // ViewModel + Compose
    implementation(libs.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.gson)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // JVM-runnable Compose UI tests via Robolectric. ui-test-junit4 provides
    // createComposeRule(); ui-test-manifest contributes the
    // ComponentActivity entry the rule needs. compose-bom keeps these in
    // sync with the implementation-side Compose libraries.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.wear.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
