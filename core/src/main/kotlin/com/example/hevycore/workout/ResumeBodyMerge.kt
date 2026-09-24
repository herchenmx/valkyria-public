package com.example.hevycore.workout

import com.google.gson.JsonObject

/**
 * Carries workout-level fields from a fetched workout into the replacement body
 * that resume posts back.
 *
 * Resume is meant to take the workout it is resuming, add the new sets, post it
 * as a new workout and delete the original. In practice the fetched workout was
 * parsed into a typed DTO first, and Gson discards every field that DTO does not
 * declare — so anything Hevy added after the DTO was written vanished on the
 * round trip. A normal workout POST is unaffected because it never echoes; only
 * resume does. That asymmetry is why resume can break while posting works.
 *
 * The merge is deliberately **additive onto the known-good body** rather than
 * subtractive from the fetched one. The typed body is the shape Hevy demonstrably
 * accepts on every normal POST, so it stays authoritative: a key we already set
 * always wins, and only keys absent from it are carried over. That way an
 * unfamiliar field is preserved without a guess about the whole response shape
 * being required.
 *
 * [SERVER_OWNED] is excluded because those identify or describe the *original*
 * record. Echoing them into a POST that is supposed to create a new workout
 * either conflicts with the record being replaced or asserts values only the
 * server may set.
 */
object ResumeBodyMerge {

    /**
     * Fields that belong to the fetched record rather than to the workout's
     * content. Never carried into the replacement.
     */
    val SERVER_OWNED: Set<String> = setOf(
        // identity of the record being replaced
        "id", "short_id",
        // ownership / authorship
        "user_id", "username", "user",
        // server-maintained timestamps and counters
        "created_at", "updated_at", "index",
        "like_count", "comment_count", "likes", "comments",
        // server-computed, and already carried inside our biometrics block
        "average_heart_rate",
    )

    /**
     * Returns [body] with any workout-level field present in [rawOriginal] but
     * absent from [body] copied across.
     *
     * @param body the request body we would otherwise post, as a JSON tree
     *   (the `{"workout": {...}}` envelope).
     * @param rawOriginal the unparsed workout as Hevy returned it, or null when
     *   the raw response wasn't captured — in which case [body] is returned
     *   unchanged, so this can never make the current behaviour worse.
     * @param envelopeKey the property holding the workout object in [body].
     */
    fun carryUnmodelledFields(
        body: JsonObject,
        rawOriginal: JsonObject?,
        envelopeKey: String = "workout",
    ): JsonObject {
        if (rawOriginal == null) return body
        val workout = body.getAsJsonObject(envelopeKey) ?: return body
        for ((key, value) in rawOriginal.entrySet()) {
            if (key in SERVER_OWNED) continue
            // Ours wins: the typed body is the shape known to be accepted.
            if (workout.has(key)) continue
            workout.add(key, value)
        }
        return body
    }

    /**
     * Keys present in [rawOriginal] that were withheld because they identify or
     * describe the fetched record. Logged so the exclusion list is visible
     * rather than an invisible policy — if Hevy never returns these the list is
     * inert, and if it does you can see exactly what was held back.
     */
    fun withheldKeys(rawOriginal: JsonObject?): List<String> =
        rawOriginal?.entrySet()?.map { it.key }?.filter { it in SERVER_OWNED }?.sorted() ?: emptyList()

    /**
     * The workout-level keys carried for a given original — useful for logging
     * what the old typed round trip was dropping, without posting anything.
     */
    fun carriedKeys(body: JsonObject, rawOriginal: JsonObject?, envelopeKey: String = "workout"): List<String> {
        if (rawOriginal == null) return emptyList()
        val workout = body.getAsJsonObject(envelopeKey) ?: return emptyList()
        return rawOriginal.entrySet()
            .map { it.key }
            .filterNot { it in SERVER_OWNED }
            .filterNot { workout.has(it) }
            .sorted()
    }
}
