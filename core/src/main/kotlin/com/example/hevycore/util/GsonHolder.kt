package com.example.hevycore.util

import com.google.gson.Gson

/**
 * Process-wide [Gson] instance shared by watch and companion. Gson is
 * thread-safe and stateless once configured; the per-store instances we used
 * to spawn just multiplied heap pressure on a watch with little benefit.
 *
 * Lives in `:core` so both modules use the exact same default configuration
 * (no `serializeNulls`, no custom adapters). If a specific call site needs a
 * differently-configured Gson (e.g. `serializeNulls()` for the private API
 * request bodies), it builds its own with `GsonBuilder()` — that stays a
 * per-caller decision.
 */
object GsonHolder {
    val gson: Gson = Gson()
}
