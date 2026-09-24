package com.example.hevywatch.data

import java.util.Collections

/**
 * An access-ordered LRU map that evicts the least-recently-used entry once
 * [maxEntries] is exceeded — a hard RAM cap for session caches on the
 * 512 MB-class watch (e.g. `HevyApp.exerciseHistoryCache`, which would
 * otherwise retain every browsed exercise's full history for the session).
 *
 * Wrapped in [Collections.synchronizedMap] because background loaders and UI
 * readers touch these caches concurrently. Safe as long as call sites use only
 * point operations (get / put / remove / containsKey) — access-ordered
 * iteration would need external locking, but nothing iterates these caches.
 *
 * Pure factory — covered by [com.example.hevywatch.BoundedLruTest].
 */
fun <V> boundedLruMap(maxEntries: Int): MutableMap<String, V> =
    Collections.synchronizedMap(
        object : LinkedHashMap<String, V>(16, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean =
                size > maxEntries
        }
    )
