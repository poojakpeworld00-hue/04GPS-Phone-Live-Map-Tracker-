package com.find.phone.gps.live.location.tracker.monetize.model

data class LiveNudge(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val description: String,
    val dateTime: Long,
    val color: Int
)

