package com.livemap.homedeck.preset

import android.content.Context
import com.livemap.homedeck.ext.homeScreenGridItemsDB
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.support.FIRST_APPS_PAGE
import com.livemap.homedeck.support.ITEM_TYPE_ICON
import com.livemap.homedeck.entities.CoreHome
import com.livemap.homedeck.entities.MainDisplayLatticeElement
import com.livemap.homedeck.entities.appLauncherComparator
import timber.log.Timber

object MainProgramsStuffer {
    fun siteMissing(context: Context, launchers: List<CoreHome>): Int =
        place(context, launchers.sortedWith(appLauncherComparator), spread = true)

    fun perPage(count: Int, capacity: Int, pages: Int): Int {
        if (capacity <= 0 || pages <= 0) return capacity
        val even = (count + pages - 1) / pages
        return even.coerceIn(1, capacity)
    }

    fun place(context: Context, apps: List<CoreHome>, spread: Boolean = false): Int {
        val config = context.launcherConfig
        val columns = config.homeColumnCount

        val rows = config.homeRowCount - 1
        if (columns <= 0 || rows <= 0 || apps.isEmpty()) return 0

        val db = context.homeScreenGridItemsDB
        val items = db.getAllItems()

        val placedIds = HashSet<String>()
        val placedPackages = HashSet<String>()
        items.filter { it.type == ITEM_TYPE_ICON }.forEach {
            if (it.activityName.isEmpty()) placedPackages.add(it.packageName) else placedIds.add(it.getItemIdentifier())
        }

        val occupied = HashSet<Triple<Int, Int, Int>>()
        items.filter { it.parentId == null && !it.docked }.forEach { item ->
            for (x in item.left..item.right) for (y in item.top..item.bottom) {
                occupied.add(Triple(item.page, x, y))
            }
        }

        val own = context.packageName
        val missing = apps
            .filter { it.packageName != own }
            .filter { it.getLauncherIdentifier() !in placedIds && it.packageName !in placedPackages }
        if (missing.isEmpty()) return 0

        val capacity = columns * rows
        val pageCap = if (spread) {
            val pages = ((missing.size + capacity - 1) / capacity).coerceAtLeast(1)
            perPage(missing.size, capacity, pages)
        } else {
            null
        }

        var page = FIRST_APPS_PAGE
        var row = 0
        var col = 0
        fun advance() {
            col++
            if (col >= columns) {
                col = 0
                row++
                if (row >= rows) {
                    row = 0
                    page++
                }
            }
        }

        val added = ArrayList<MainDisplayLatticeElement>(missing.size)
        var pageOfCount = -1
        var placedOnPage = 0
        for (launcher in missing) {
            while (Triple(page, col, row) in occupied) advance()
            if (page != pageOfCount) {
                pageOfCount = page
                placedOnPage = 0
            }

            if (pageCap != null && placedOnPage >= pageCap) {
                page++
                row = 0
                col = 0
                pageOfCount = page
                placedOnPage = 0
                while (Triple(page, col, row) in occupied) advance()
            }
            added.add(
                MainDisplayLatticeElement(
                    id = null,
                    left = col,
                    top = row,
                    right = col,
                    bottom = row,
                    page = page,
                    packageName = launcher.packageName,
                    activityName = launcher.activityName,
                    title = launcher.title,
                    type = ITEM_TYPE_ICON,
                    className = "",
                    widgetId = -1,
                    shortcutId = "",
                    icon = null,
                    docked = false,
                    parentId = null,
                )
            )
            occupied.add(Triple(page, col, row))
            placedOnPage++
            advance()
        }
        db.insertAll(added)
        Timber.i("MainProgramsStuffer: placed ${added.size} apps on the workspace (pages up to $page)")
        return added.size
    }
}
