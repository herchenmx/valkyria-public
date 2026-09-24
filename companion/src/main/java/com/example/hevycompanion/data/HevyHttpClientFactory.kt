package com.example.hevycompanion.data

import com.example.hevycompanion.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * R7 — single OkHttp client builder for every Hevy-API entry point on the
 * companion side. Previously HevyAuthApi and HevyPublicApi each built their
 * own client with subtly different (and drift-prone) timeout / interceptor
 * configurations; centralizing here means a future hardening change (SSL
 * pinning, certificate transparency, retry policy) updates both call sites
 * at once.
 *
 * The default 10 s connect / 15 s read+write mirrors the watch's settings;
 * the companion is on phone data so a longer write timeout isn't necessary.
 */
object HevyHttpClientFactory {

    fun build(
        configureBuilder: OkHttpClient.Builder.() -> OkHttpClient.Builder = { this },
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BASIC
            else
                HttpLoggingInterceptor.Level.NONE
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .configureBuilder()
            .addInterceptor(logging)
            .build()
    }
}
