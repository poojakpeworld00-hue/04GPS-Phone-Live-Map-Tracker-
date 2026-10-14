package com.find.phone.gps.live.location.tracker.consent

/**
 * An ordered, drainable queue of the permissions to request on one Activity.
 *
 * Rules are ordered by [ConsentClause.priority] ascending (lower value first);
 * ties keep their incoming order (stable sort). The engine polls one rule at a
 * time and only advances after the previous request completes — giving the
 * required sequential flow.
 */
class ConsentBacklog(rules: List<ConsentClause>) {

    private val items: ArrayDeque<ConsentClause> =
        ArrayDeque(rules.sortedBy { it.priority })

    /** Removes and returns the next rule, or null when the queue is drained. */
    fun poll(): ConsentClause? = items.removeFirstOrNull()

    fun isEmpty(): Boolean = items.isEmpty()

    fun size(): Int = items.size
}
