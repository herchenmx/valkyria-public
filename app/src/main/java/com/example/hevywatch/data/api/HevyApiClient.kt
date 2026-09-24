package com.example.hevywatch.data.api

import com.example.hevycore.debug.DebugWebhookInterceptor
import com.example.hevywatch.BuildConfig
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object HevyApiClient {

    private const val BASE_URL = "https://api.hevyapp.com/"
    private val PRIVATE_API_KEY = BuildConfig.HEVY_PRIVATE_API_KEY

    // Wear OS BT-tethered network is intermittent — the OkHttp default of 60 s
    // per phase means a stalled request can hang the UI for a full minute.
    // 10 s connect / 15 s read/write is enough for healthy responses while
    // failing fast on dead connections, so the user sees an error and can
    // retry instead of staring at a spinner.
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 15L
    private const val WRITE_TIMEOUT_S = 15L

    // Visible to tests so the bearer-redaction invariant can be locked in:
    // [createPrivate] must never attach a logging interceptor — even at BASIC
    // level — because that's the only path that carries an Authorization
    // header. BASIC only logs method/URL/status (no headers, no bodies), so
    // [create] and [createAuth] are safe to log even in debug.
    internal fun loggingInterceptor(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        // BASIC in debug builds; NONE in release so per-request log writes
        // don't cost CPU/IO on a battery-constrained watch. Toggle via
        // BuildConfig.DEBUG (no separate flag needed).
        level = if (BuildConfig.DEBUG)
            HttpLoggingInterceptor.Level.BASIC
        else
            HttpLoggingInterceptor.Level.NONE
    }

    private fun newClient(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)

    fun create(apiKey: String): HevyApiService =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(buildPublicClient(apiKey))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HevyApiService::class.java)

    fun createAuth(
        versionSupplier: () -> Pair<String, String> = DEFAULT_VERSION_SUPPLIER,
    ): HevyApiService =
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(buildAuthClient(versionSupplier))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HevyApiService::class.java)

    fun createPrivate(
        accessToken: String,
        versionSupplier: () -> Pair<String, String> = DEFAULT_VERSION_SUPPLIER,
    ): HevyApiService {
        val gson = GsonBuilder().serializeNulls().create()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(buildPrivateClient(accessToken, versionSupplier))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(HevyApiService::class.java)
    }

    internal fun buildPublicClient(apiKey: String): OkHttpClient {
        val authInterceptor = Interceptor { chain ->
            chain.proceed(chain.request().newBuilder().addHeader("api-key", apiKey).build())
        }
        return newClient()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor())
            .addInterceptor(DebugWebhookInterceptor("public"))
            .build()
    }

    internal fun buildAuthClient(
        versionSupplier: () -> Pair<String, String> = DEFAULT_VERSION_SUPPLIER,
    ): OkHttpClient {
        val headersInterceptor = Interceptor { chain ->
            val (versionName, versionCode) = versionSupplier()
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("X-Api-Key", PRIVATE_API_KEY)
                    .addHeader("Hevy-App-Version", versionName)
                    .addHeader("Hevy-App-Build", versionCode)
                    .addHeader("Hevy-Platform", "wearos")
                    .build()
            )
        }
        return newClient()
            .addInterceptor(headersInterceptor)
            .addInterceptor(loggingInterceptor())
            .build()
    }

    internal fun buildPrivateClient(
        accessToken: String,
        versionSupplier: () -> Pair<String, String> = DEFAULT_VERSION_SUPPLIER,
    ): OkHttpClient {
        // INVARIANT — NO LOGGING INTERCEPTOR.
        // This is the only path that carries an Authorization: Bearer header.
        // BASIC level only logs URL+status (no headers), but pinning to no
        // logging at all on the bearer path means a future bump to HEADERS or
        // BODY can't accidentally leak the token. Tests assert this directly.
        val authInterceptor = Interceptor { chain ->
            val (versionName, versionCode) = versionSupplier()
            chain.proceed(
                chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("X-Api-Key", PRIVATE_API_KEY)
                    .addHeader("Hevy-App-Version", versionName)
                    .addHeader("Hevy-App-Build", versionCode)
                    .addHeader("Hevy-Platform", "wearos")
                    .build()
            )
        }
        // The webhook interceptor is NOT a logging interceptor and does not
        // breach the invariant above: it reads no headers at all (DebugWebhook
        // has no parameter one could be passed through), so the bearer token
        // cannot reach it. It mirrors workout request/response bodies only,
        // and only when a webhook URL was built in.
        return newClient()
            .addInterceptor(authInterceptor)
            .addInterceptor(DebugWebhookInterceptor("private"))
            .build()
    }

    // Without the Hevy-App-Version / Hevy-App-Build headers the server gates
    // several private v2 routes (`GET /workout/{id}`, `DELETE /workout/{id}`)
    // and returns 404 as if they didn't exist. Values mirror what the official
    // Hevy Wear OS app sends — see decompiled smali at
    // hevy-wear-os/base/smali_classes3/com/hevy/api/APIClient.smali line ~140.
    //
    // The supplier is invoked on every request so a runtime override via
    // [HevyAppVersionStore] (set by SetApiVersionReceiver or the companion's
    // /api_version DataClient push) takes effect on the next call without
    // an app restart. The default falls back to BuildConfig values baked in
    // at assemble time, matched to whatever apkmirror showed when this APK
    // was built.
    private val DEFAULT_VERSION_SUPPLIER: () -> Pair<String, String> = {
        BuildConfig.DEFAULT_HEVY_APP_VERSION to BuildConfig.DEFAULT_HEVY_APP_BUILD
    }
}
