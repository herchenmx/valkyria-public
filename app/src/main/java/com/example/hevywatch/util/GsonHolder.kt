package com.example.hevywatch.util

/**
 * Thin re-export so the watch's existing `com.example.hevywatch.util.GsonHolder`
 * call sites keep resolving after the singleton moved to
 * [com.example.hevycore.util.GsonHolder] in `:core`. Watch and companion now
 * share a single Gson instance — no separate heap pressure per module.
 */
typealias GsonHolder = com.example.hevycore.util.GsonHolder
