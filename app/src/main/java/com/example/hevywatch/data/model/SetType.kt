package com.example.hevywatch.data.model

enum class SetType(val apiValue: String) {
    NORMAL("normal"),
    WARMUP("warmup"),
    FAILURE("failure"),
    DROPSET("dropset");

    companion object {
        fun fromApiValue(value: String): SetType =
            entries.firstOrNull { it.apiValue == value } ?: NORMAL
    }
}
