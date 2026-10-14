package com.find.phone.gps.live.location.tracker.consent

class ConsentBacklog(rules: List<ConsentClause>) {
    private val items: ArrayDeque<ConsentClause> =
        ArrayDeque(rules.sortedBy { it.priority })

    fun poll(): ConsentClause? = items.removeFirstOrNull()

    fun isEmpty(): Boolean = items.isEmpty()

    fun size(): Int = items.size
}
