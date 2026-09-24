package com.example.hevywatch.data.api.model

/**
 * Thin re-exports so the watch's existing call sites keep resolving after
 * the shared shapes moved to [com.example.hevycore.auth.RefreshTokenRequest]
 * / [com.example.hevycore.auth.RefreshTokenResponse].
 *
 * The response's fields are nullable at the wire boundary; callers
 * (`PhoneAuthDispatcher.handleTokens`, Retrofit call site) validate before
 * consuming so a partial payload never lands in `AuthStore`.
 */
typealias RefreshTokenRequest = com.example.hevycore.auth.RefreshTokenRequest
typealias RefreshTokenResponse = com.example.hevycore.auth.RefreshTokenResponse
