package com.example.hevycompanion.util

/**
 * Thin re-export so the companion's existing
 * `com.example.hevycompanion.util.GsonHolder` call sites keep resolving after
 * the singleton moved to [com.example.hevycore.util.GsonHolder] in `:core`.
 * Watch and companion now share a single Gson instance.
 */
typealias GsonHolder = com.example.hevycore.util.GsonHolder
