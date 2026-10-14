package com.find.phone.gps.live.location.tracker.consent

data class ConsentClause(

    val key: String,

    val enabled: Boolean,

    val activities: List<String>,

    val delayMs: Long,

    val priority: Int,

    val showOnce: Boolean,
)

data class ConsentSchema(

    val key: String,

    val androidPermission: String,

    val minSdk: Int,

    val enabledPrefGate: String? = null,
)
