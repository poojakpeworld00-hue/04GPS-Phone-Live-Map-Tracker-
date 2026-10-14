package com.livemap.homedeck.widgetry

import org.junit.Assert.assertEquals
import org.junit.Test

class OrphanAppletViewsTest {
    @Test
    fun viewWithoutItsRowIsOrphan() {
        val views = mapOf("search" to 7L, "time" to 3L)
        assertEquals(listOf("search"), MainDisplayLattice.orphanWidgetViews(views, liveItemIds = setOf(3L)))
    }

    @Test
    fun viewWithLiveRowIsKept() {
        val views = mapOf("search" to 7L, "time" to 3L)
        assertEquals(emptyList<String>(), MainDisplayLattice.orphanWidgetViews(views, liveItemIds = setOf(3L, 7L)))
    }

    @Test
    fun placedViewIsFoundByRowIdWhenWidgetIdIsStale() {
        val views = listOf("search" to (42 to 7L), "time" to (5 to 3L))
        val found = MainDisplayLattice.placedWidgetView(views, tagOf = { it.second.first }, itemIdOf = { it.second.second }, widgetId = -1, itemId = 7L)
        assertEquals("search", found?.first)
    }

    @Test
    fun placedViewFallsBackToWidgetId() {
        val views = listOf("search" to (42 to null as Long?))
        val found = MainDisplayLattice.placedWidgetView(views, tagOf = { it.second.first }, itemIdOf = { it.second.second }, widgetId = 42, itemId = 7L)
        assertEquals("search", found?.first)
    }

    @Test
    fun untrackedViewIsKept() {
        val views = mapOf<String, Long?>("unknown" to null)
        assertEquals(emptyList<String>(), MainDisplayLattice.orphanWidgetViews(views, liveItemIds = emptySet()))
    }
}
