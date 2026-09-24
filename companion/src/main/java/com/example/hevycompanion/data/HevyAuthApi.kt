package com.example.hevycompanion.data

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface HevyAuthApi {
    @POST("auth/refresh_token")
    suspend fun refreshToken(
        @Header("Authorization") authorization: String?,
        @Body body: RefreshTokenRequest
    ): Response<AuthTokenResponse>
}

/**
 * [baseUrl] is overridable only so unit tests can point the client at a
 * MockWebServer instance; production callers should stick with the default.
 */
fun buildHevyAuthApi(baseUrl: String = "https://api.hevyapp.com/"): HevyAuthApi {
    val client = HevyHttpClientFactory.build {
        addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    // The Android app's API key is embedded in the Hermes JS bundle and
                    // not recoverable without a proper Hermes decompiler.
                    // We use the web key here — closest known key for a non-watch client.
                    // The watch's own key is a different value for the wearos platform
                    // (BuildConfig.HEVY_PRIVATE_API_KEY, from secrets.properties); it is
                    // deliberately not reproduced here. The web key is redacted in this public copy.
                    // If the server rejects this, the manual-token paste fallback still works.
                    .header("x-api-key", "YOUR_HEVY_WEB_CLIENT_KEY")
                    .header("Hevy-Platform", "web")
                    .header("Content-Type", "application/json")
                    .build()
            )
        }
    }
    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(HevyAuthApi::class.java)
}
