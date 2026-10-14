package com.livemap.homedeck.support

import android.content.pm.ApplicationInfo
import com.livemap.homedeck.entities.CoreHome
import com.livemap.homedeck.entities.CoreStats
import kotlin.math.ln
import kotlin.math.pow

object CorePicks {
    const val LIMIT = 8

    const val MIN_TO_SHOW = 4

    private const val FRESH_INSTALL_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    private const val DECAY_HALF_LIFE_MS = 3 * 24 * 60 * 60 * 1000.0

    val PADDING_CATEGORIES = listOf(
        ApplicationInfo.CATEGORY_SOCIAL,
        ApplicationInfo.CATEGORY_NEWS,
        ApplicationInfo.CATEGORY_VIDEO,
        ApplicationInfo.CATEGORY_AUDIO,
        ApplicationInfo.CATEGORY_MAPS,
        ApplicationInfo.CATEGORY_PRODUCTIVITY,
    )

    class Facts(val firstInstallTime: Long, val category: Int)

    fun score(usage: CoreStats, now: Long): Double {
        val elapsed = maxOf(0L, now - usage.lastLaunched)
        val countBonus = 0.35 * ln(1.0 + usage.launchCount)
        return 0.5.pow(elapsed / DECAY_HALF_LIFE_MS) + countBonus
    }

    fun rank(
        installed: List<CoreHome>,
        usage: List<CoreStats>,
        facts: (packageName: String) -> Facts?,
        now: Long,
        limit: Int = LIMIT,
    ): List<CoreHome> {
        val lookup = installed.associateBy { it.packageName }
        val chosen = LinkedHashMap<String, CoreHome>()

        fun saturated() = chosen.size >= limit
        fun admit(app: CoreHome) {
            if (!saturated()) chosen.putIfAbsent(app.packageName, app)
        }

        usage.filter { it.packageName in lookup }
            .map { row -> row to score(row, now) }
            .sortedByDescending { it.second }
            .forEach { (row, _) -> chosen.putIfAbsent(row.packageName, lookup.getValue(row.packageName)) }

        if (!saturated()) {
            val window = 0..FRESH_INSTALL_WINDOW_MS
            installed.filterNot { it.packageName in chosen }
                .mapNotNull { app ->
                    facts(app.packageName)
                        ?.takeIf { now - it.firstInstallTime in window }
                        ?.let { app to it.firstInstallTime }
                }
                .sortedByDescending { it.second }
                .forEach { admit(it.first) }
        }

        for (category in PADDING_CATEGORIES) {
            if (saturated()) break
            installed.filter { it.packageName !in chosen && facts(it.packageName)?.category == category }
                .sortedBy { it.title.lowercase() }
                .forEach(::admit)
        }
        return chosen.values.take(limit)
    }
}
