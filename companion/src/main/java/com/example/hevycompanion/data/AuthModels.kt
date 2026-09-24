package com.example.hevycompanion.data

/**
 * Thin re-exports so the companion's existing call sites keep resolving after
 * the shared shapes moved to [com.example.hevycore.auth.RefreshTokenRequest]
 * / [com.example.hevycore.auth.RefreshTokenResponse]. Watch and companion
 * now consume the exact same `@SerializedName` bindings.
 *
 * `AuthTokenResponse` stays as an alias so `HevyAuthApi.refreshToken` and
 * `RefreshTokenInteractor.classifyResponse` don't have to churn in the same
 * commit as the extraction.
 */
typealias RefreshTokenRequest = com.example.hevycore.auth.RefreshTokenRequest
typealias AuthTokenResponse = com.example.hevycore.auth.RefreshTokenResponse
