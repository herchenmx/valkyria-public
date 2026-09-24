package com.example.hevywatch.data.store

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tries to open [name] as an [EncryptedSharedPreferences]; on any keystore /
 * crypto failure (rare but documented — corrupted master key, locked-screen
 * changes on certain OEM ROMs), falls back to a plain [SharedPreferences]
 * so the app keeps working instead of bricking on startup.
 *
 * Migration: if a same-named legacy plain prefs file exists with data, its
 * keys are copied into the encrypted instance and the legacy file is wiped.
 * This is a one-shot read so subsequent launches go straight to encrypted.
 *
 * The legacy/plain prefs file is named [name] + LEGACY_SUFFIX so the
 * encrypted file (which uses [name] verbatim) doesn't try to parse plaintext
 * XML as ciphertext. Callers migrating an existing pref must pass the
 * pre-existing name as `legacyName`.
 */
internal object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val MIGRATION_FLAG_KEY = "__migrated_v1__"

    fun open(
        context: Context,
        name: String,
        legacyName: String = name,
    ): SharedPreferences {
        val encrypted = tryOpenEncrypted(context, name)
        if (encrypted == null) {
            Log.w(TAG, "Encrypted prefs unavailable, falling back to plain for $name")
            return context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        }

        // One-shot migration from the legacy plain file (if any). The flag
        // sits inside the encrypted file so we don't attempt to migrate
        // twice even if the legacy file is later recreated by some path.
        if (legacyName != name && !encrypted.getBoolean(MIGRATION_FLAG_KEY, false)) {
            val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                val edit = encrypted.edit()
                legacy.all.forEach { (k, v) ->
                    when (v) {
                        is String -> edit.putString(k, v)
                        is Int -> edit.putInt(k, v)
                        is Long -> edit.putLong(k, v)
                        is Float -> edit.putFloat(k, v)
                        is Boolean -> edit.putBoolean(k, v)
                        // SharedPreferences also supports StringSet; not used here.
                    }
                }
                edit.putBoolean(MIGRATION_FLAG_KEY, true).commit()
                legacy.edit().clear().commit()
            } else {
                encrypted.edit().putBoolean(MIGRATION_FLAG_KEY, true).apply()
            }
        }
        return encrypted
    }

    private fun tryOpenEncrypted(context: Context, name: String): SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure { e ->
        Log.w(TAG, "EncryptedSharedPreferences.create failed for $name: $e")
    }.getOrNull()
}
