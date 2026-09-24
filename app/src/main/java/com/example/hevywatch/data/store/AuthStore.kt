package com.example.hevywatch.data.store

import android.content.Context
import androidx.core.content.edit

class AuthStore(context: Context) {

    // Encrypted-at-rest via androidx.security:security-crypto. The encrypted
    // file lives at PREFS_NAME_ENCRYPTED; existing plain installs are
    // migrated from PREFS_NAME_LEGACY on first read. Falls back to plain
    // prefs if the device's keystore is in a bad state — the app stays
    // functional, the worst case is unencrypted-at-rest tokens (same as
    // before this change).
    private val prefs = SecurePrefs.open(
        context = context,
        name = PREFS_NAME_ENCRYPTED,
        legacyName = PREFS_NAME_LEGACY,
    )

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit {
            if (value != null) putString(KEY_API_KEY, value) else remove(KEY_API_KEY)
        }

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit {
            if (value != null) putString(KEY_ACCESS_TOKEN, value) else remove(KEY_ACCESS_TOKEN)
        }

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit {
            if (value != null) putString(KEY_REFRESH_TOKEN, value) else remove(KEY_REFRESH_TOKEN)
        }

    var tokenExpiresAt: String?
        get() = prefs.getString(KEY_TOKEN_EXPIRES_AT, null)
        set(value) = prefs.edit {
            if (value != null) putString(KEY_TOKEN_EXPIRES_AT, value) else remove(KEY_TOKEN_EXPIRES_AT)
        }

    /** sourceNodeId of the paired companion phone — pinned the first time we
     *  accept tokens (trust-on-first-use). Subsequent /auth_tokens or
     *  /watch_seed messages from a different node are rejected, so a malicious
     *  app on the watch (or a second misconfigured phone) can't overwrite
     *  stored credentials. Cleared on [clear] / explicit logout. */
    var trustedPhoneNodeId: String?
        get() = prefs.getString(KEY_TRUSTED_PHONE_NODE, null)
        set(value) = prefs.edit {
            if (value != null) putString(KEY_TRUSTED_PHONE_NODE, value)
            else remove(KEY_TRUSTED_PHONE_NODE)
        }

    val isLoggedIn: Boolean get() = !apiKey.isNullOrBlank() && !accessToken.isNullOrBlank()

    fun clear() {
        prefs.edit { clear() }
    }

    companion object {
        // Legacy plain-prefs filename; preserved as the migration source for
        // installs from before EncryptedSharedPreferences was wired in.
        private const val PREFS_NAME_LEGACY = "hevy_auth"
        // Encrypted-prefs filename. New name so the encryption layer doesn't
        // try to read pre-existing plaintext XML as ciphertext.
        private const val PREFS_NAME_ENCRYPTED = "hevy_auth_enc"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_TOKEN_EXPIRES_AT = "token_expires_at"
        private const val KEY_TRUSTED_PHONE_NODE = "trusted_phone_node"
    }
}
