package com.find.phone.gps.live.location.tracker.store

enum class CallSort { INCOMING, OUTGOING, MISSED, SPAM }

data class CallPlatePayload(
    val name: String,
    val time: String,
    val info: String,
    val initials: String,
    val type: CallSort,
    val number: String = "",

    val identified: Boolean = true
)

data class HumanElement(
    val name: String,
    val detail: String,
    val initials: String,
    val photoUri: String? = null,
    val starred: Boolean = false,
    val lastContacted: Long = 0L,
    val inGroup: Boolean = false
)

object MockPayload {
    val recents: List<CallPlatePayload> = listOf(
        CallPlatePayload("Sarah Khan", "9:24", "Incoming · 4m 12s", "SK", CallSort.INCOMING),
        CallPlatePayload("+1 (800) 244-0199", "8:50", "Spam · Telemarketer", "!", CallSort.SPAM),
        CallPlatePayload("Dad Mobile", "7:32", "Missed call", "DM", CallSort.MISSED),
        CallPlatePayload("+44 20 7946 0321", "Tue", "Outgoing · London, UK", "+9", CallSort.OUTGOING),
        CallPlatePayload("Aisha Lawson", "Tue", "Incoming · 1m 03s", "AL", CallSort.INCOMING)
    )

    val homeRecent: List<CallPlatePayload> = recents.take(2)

    val contacts: List<HumanElement> = listOf(
        HumanElement("Aisha Lawson", "+1 (415) 555-0178", "AL"),
        HumanElement("Amir Raza", "Acme Corp", "AR"),
        HumanElement("Dad Mobile", "+1 (415) 555-0143", "DM"),
        HumanElement("Sarah Khan", "Brightline Bank", "SK")
    )
}
