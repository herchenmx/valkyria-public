package com.example.hevycompanion.muscle

import android.content.Context

/**
 * Normalizes a Hevy exercise title to a slug that can be matched against the
 * bundled Liftoff avatar drawables (`liftoff_ex_<slug>`).
 *
 * Hevy titles look like "Bench Press (Barbell)" or "Dumbbell Curl".
 * Liftoff slugs look like "bench_press", "dumbbell_curl", "barbell_bench_press".
 *
 * Resolution strategy:
 *  1. strip any parenthetical suffix like " (Barbell)" to get the base name
 *  2. lowercase + replace non-alphanumeric with underscore + trim underscores
 *  3. when the title HAS a parenthetical (= equipment is specified), try the
 *     equipment-qualified slugs FIRST (`equipment_base`, then `base_equipment`).
 *     If neither matches, do NOT fall back to the bare base — that would
 *     return the wrong-equipment image (e.g. "Bench Press (Cable)" would
 *     resolve to the barbell `bench_press` avatar, misleading the user).
 *     Returning 0 makes the UI show its first-letter placeholder, which is
 *     an honest "we don't have this specific variant" signal.
 *  4. when the title has NO parenthetical, fall back to the bare slug —
 *     equipment-agnostic exercise names ("Push-Up", "Plank") legitimately
 *     resolve to the bare avatar.
 */
object LiftoffSlug {

    fun normalize(title: String): String {
        val noParens = title.substringBefore('(').trim()
        return slugify(noParens)
    }

    /** Avatar slug candidates, in resolution order (first match wins). */
    fun candidateSlugs(title: String): List<String> {
        val noParens = title.substringBefore('(').trim()
        val parenthetical = title.substringAfter('(', "")
            .substringBefore(')').trim()
        val base = slugify(noParens)
        if (parenthetical.isBlank()) return listOf(base)
        val equip = slugify(parenthetical)
        // Equipment-qualified slugs ONLY — no bare-base fallback. See the
        // resolution strategy above for why we'd rather show a placeholder
        // than a misleading wrong-equipment image.
        return listOf("${equip}_$base", "${base}_$equip")
    }

    private fun slugify(s: String): String = s
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '_' }
        .joinToString("")
        .trim('_')
        .replace(Regex("_+"), "_")

    /**
     * Resolves a Hevy exercise title to a bundled `liftoff_ex_<slug>` drawable
     * resource id, or 0 if no match exists.
     */
    fun resolveAvatarResId(context: Context, title: String): Int {
        val pkg = context.packageName
        for (slug in candidateSlugs(title)) {
            if (slug.isBlank()) continue
            val id = context.resources.getIdentifier("liftoff_ex_$slug", "drawable", pkg)
            if (id != 0) return id
        }
        return 0
    }
}
