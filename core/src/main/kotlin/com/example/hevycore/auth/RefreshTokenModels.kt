package com.example.hevycore.auth

import com.google.gson.annotations.SerializedName

/**
 * `POST /auth/refresh_token` request body. Shared between the watch and the
 * companion so the wire shape can't drift.
 *
 * The Hevy server rotates the refresh token on every successful call; only
 * the RT in this body authenticates the rotation. The bearer header
 * accompanying the request may be an expired access token — the server just
 * uses it to identify the user shape.
 */
data class RefreshTokenRequest(
    @SerializedName("refresh_token") val refreshToken: String,
)

/**
 * Shared response shape for `POST /auth/refresh_token`. Nullable fields are a
 * safe superset — the watch's dispatcher rejects blank / missing fields
 * before consuming them; the companion's [RefreshTokenInteractor] does the
 * same via `if (at != null && rtNew != null && exp != null)`.
 *
 * Previously duplicated as `RefreshTokenResponse` on the watch (non-null) and
 * `AuthTokenResponse` on the companion (nullable). Consolidated here so the
 * `@SerializedName` bindings can't drift.
 */
data class RefreshTokenResponse(
    @SerializedName("access_token") val accessToken: String?,
    @SerializedName("refresh_token") val refreshToken: String?,
    @SerializedName("expires_at") val expiresAt: String?,
)
