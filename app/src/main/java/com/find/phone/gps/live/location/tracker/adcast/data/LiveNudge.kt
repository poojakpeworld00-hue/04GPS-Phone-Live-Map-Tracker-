package com.find.phone.gps.live.location.tracker.adcast.data

data class LiveNudge(
    val id: Long = System.currentTimeMillis(),
    val title: String,
    val description: String,
    val dateTime: Long,
    val color: Int // Add this// optional
)

