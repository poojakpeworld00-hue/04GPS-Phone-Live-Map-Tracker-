package com.find.phone.gps.live.location.tracker.monetize.model

import kotlin.text.lowercase

enum class SpotSort {
    GOOGLE,
    FACEBOOK,
    CUSTOM,
    UNKNOWN;

    companion object {
        fun fromString(value: String?): SpotSort {
            return when (value?.lowercase()) {
                "google" -> GOOGLE
                "facebook", "fb" -> FACEBOOK
                "custom" -> CUSTOM
                else -> UNKNOWN
            }
        }
    }
}