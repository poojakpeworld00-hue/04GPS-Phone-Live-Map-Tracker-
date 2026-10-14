package com.livemap.homedeck.preset

import org.json.JSONObject

enum class SliderWay { VERTICAL, PAGED, NONE }

enum class SliderHuntPlacement { TOP, BOTTOM }

enum class PresetFeed { APK, RC_RULE, BUILT_IN, STANDARD }

data class MainPreset(
    val id: String,
    val homeCols: Int,
    val homeRows: Int,
    val dockSize: Int,
    val drawerMode: SliderWay,
    val drawerCols: Int,

    val drawerRows: Int,
    val drawerSearch: Boolean,
    val homeLabels: Boolean,
    val drawerLabels: Boolean,
    val source: PresetFeed,
    val drawerSearchPosition: SliderHuntPlacement = SliderHuntPlacement.TOP,

    val iconScale: Float = 1f,

    val iconDp: Int = 0,

    val dockIconDp: Int = 0,

    val drawerIconDp: Int = 0,

    val iconWidthFraction: Float = 0f,
    val dockIconWidthFraction: Float = 0f,
    val drawerIconWidthFraction: Float = 0f,

    val iconWidthFractionByDpi: Map<Int, Float> = emptyMap(),

    val iconInset: Float = 0f,

    val legacyIconTray: String = LEGACY_TRAY_DOMINANT,

    val labelSp: Float = 0f,

    val fieldSources: Map<String, PresetFeed> = emptyMap(),
) {
    data class Numbers(
        val homeCols: Int? = null,
        val homeRows: Int? = null,
        val dockSize: Int? = null,
        val drawerCols: Int? = null,

        val iconSizeDp: Int? = null,

        val labelSp: Float? = null,

        val drawerIconDp: Int? = null,

        val dockIconDp: Int? = null,

        val drawerMode: SliderWay? = null,

    ) {
        val isEmpty: Boolean
            get() = homeCols == null && homeRows == null && dockSize == null && drawerCols == null && iconSizeDp == null &&
                labelSp == null && drawerIconDp == null && dockIconDp == null && drawerMode == null

        fun fill(other: Numbers) = Numbers(
            homeCols = homeCols ?: other.homeCols,
            homeRows = homeRows ?: other.homeRows,
            dockSize = dockSize ?: other.dockSize,
            drawerCols = drawerCols ?: other.drawerCols,
            iconSizeDp = iconSizeDp ?: other.iconSizeDp,
            labelSp = labelSp ?: other.labelSp,
            drawerIconDp = drawerIconDp ?: other.drawerIconDp,
            dockIconDp = dockIconDp ?: other.dockIconDp,
            drawerMode = drawerMode ?: other.drawerMode,
        )
    }

    fun overlay(numbers: Numbers, ownIconDp: (cols: Int) -> Float = { 0f }): MainPreset {
        if (numbers.isEmpty) return this
        val columns = numbers.homeCols ?: homeCols
        val relativeScale = numbers.iconSizeDp?.let { oemDp ->
            val baseline = ownIconDp(columns)
            when {
                baseline > 0f -> (oemDp / baseline).coerceIn(MIN_ICON_SCALE, MAX_ICON_SCALE)
                else -> null
            }
        }
        val labelSize = numbers.labelSp?.takeIf { it in MIN_LABEL_SP..MAX_LABEL_SP }
        val workspaceDp = drawableDpOrNull(numbers.iconSizeDp)
        val hotseatDp = drawableDpOrNull(numbers.dockIconDp)
        val trayDp = drawableDpOrNull(numbers.drawerIconDp)

        val fromApk = buildList {
            if (numbers.homeCols != null) add(KEY_HOME_COLS)
            if (numbers.homeRows != null) add(KEY_HOME_ROWS)
            if (numbers.dockSize != null) add(KEY_DOCK_SIZE)
            if (numbers.drawerCols != null) add(KEY_DRAWER_COLS)
            if (relativeScale != null) add(KEY_ICON_SCALE)
            if (labelSize != null) add(KEY_LABEL_SP)
            if (workspaceDp != null) add(KEY_ICON_DP)
            if (hotseatDp != null) add(KEY_DOCK_ICON_DP)
            if (trayDp != null) add(KEY_DRAWER_ICON_DP)
            if (numbers.drawerMode != null) add(KEY_DRAWER_MODE)
        }
        val tiers = FIELD_KEYS.associateWith { sourceOf(it) }.toMutableMap()
        fromApk.forEach { tiers[it] = PresetFeed.APK }

        return copy(
            homeCols = columns,
            homeRows = numbers.homeRows ?: homeRows,
            dockSize = numbers.dockSize ?: dockSize,
            drawerCols = numbers.drawerCols ?: drawerCols,
            iconScale = relativeScale ?: iconScale,
            labelSp = labelSize ?: labelSp,
            iconDp = workspaceDp ?: iconDp,
            dockIconDp = hotseatDp ?: dockIconDp,
            drawerIconDp = trayDp ?: drawerIconDp,
            drawerMode = numbers.drawerMode ?: drawerMode,
            source = PresetFeed.APK,
            fieldSources = tiers,
        )
    }

    private fun drawableDpOrNull(value: Int?): Int? = value?.takeIf { it in MIN_ICON_DP..MAX_ICON_DP }

    fun settleFractions(widthDp: Int, densityDpi: Int = 0): MainPreset {
        if (widthDp <= 0) return this
        fun fromFraction(fallback: Int, fraction: Float): Int =
            fraction.takeIf { it > 0f }
                ?.let { Math.round(it * widthDp) }
                ?.takeIf { it in MIN_ICON_DP..MAX_ICON_DP }
                ?: fallback

        val settled = copy(
            iconDp = fromFraction(iconDp, iconFractionFor(densityDpi)),
            dockIconDp = fromFraction(dockIconDp, dockIconWidthFraction),
            drawerIconDp = fromFraction(drawerIconDp, drawerIconWidthFraction),
        )
        val tiers = fieldSources.toMutableMap()
        listOf(
            Triple(KEY_ICON_DP, KEY_ICON_WIDTH_FRACTION, iconDp to settled.iconDp),
            Triple(KEY_DOCK_ICON_DP, KEY_DOCK_ICON_WIDTH_FRACTION, dockIconDp to settled.dockIconDp),
            Triple(KEY_DRAWER_ICON_DP, KEY_DRAWER_ICON_WIDTH_FRACTION, drawerIconDp to settled.drawerIconDp),
        ).forEach { (dpKey, fractionKey, change) ->
            if (change.first != change.second) tiers[dpKey] = sourceOf(fractionKey)
        }
        return settled.copy(fieldSources = tiers)
    }

    fun iconFractionFor(densityDpi: Int): Float {
        if (densityDpi <= 0 || iconWidthFractionByDpi.isEmpty()) return iconWidthFraction
        return iconWidthFractionByDpi.entries.minBy { kotlin.math.abs(it.key - densityDpi) }.value
    }

    private fun insetDp(itemDp: Int): Int {
        if (itemDp == 0) return 0
        val share = iconInset.takeIf { it > 0f } ?: 1f
        return Math.round(itemDp * share).coerceIn(MIN_ICON_DP, MAX_ICON_DP)
    }
    val homeArtDp: Int get() = insetDp(iconDp)
    val dockArtDp: Int get() = insetDp(dockIconDp.takeIf { it > 0 } ?: iconDp)
    val drawerArtDp: Int get() = insetDp(drawerIconDp.takeIf { it > 0 } ?: iconDp)

    fun sourceOf(key: String): PresetFeed = fieldSources[key] ?: source

    fun describeSources(): String = FIELD_KEYS.joinToString(" ") { "$it=${sourceOf(it).name.lowercase()}" }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_HOME_COLS, homeCols)
        put(KEY_HOME_ROWS, homeRows)
        put(KEY_DOCK_SIZE, dockSize)
        put(KEY_DRAWER_MODE, drawerMode.name.lowercase())
        put(KEY_DRAWER_COLS, drawerCols)
        put(KEY_DRAWER_ROWS, drawerRows)
        put(KEY_DRAWER_SEARCH, drawerSearch)
        put(KEY_HOME_LABELS, homeLabels)
        put(KEY_DRAWER_LABELS, drawerLabels)
        put(KEY_DRAWER_SEARCH_POSITION, drawerSearchPosition.name.lowercase())
        put(KEY_ICON_SCALE, iconScale.toDouble())
        put(KEY_LABEL_SP, labelSp.toDouble())
        put(KEY_ICON_DP, iconDp)
        put(KEY_DOCK_ICON_DP, dockIconDp)
        put(KEY_DRAWER_ICON_DP, drawerIconDp)
        put(KEY_ICON_WIDTH_FRACTION, iconWidthFraction.toDouble())
        put(KEY_DOCK_ICON_WIDTH_FRACTION, dockIconWidthFraction.toDouble())
        put(KEY_DRAWER_ICON_WIDTH_FRACTION, drawerIconWidthFraction.toDouble())
        if (iconWidthFractionByDpi.isNotEmpty()) {
            put(KEY_ICON_WIDTH_FRACTION_BY_DPI, JSONObject().apply { iconWidthFractionByDpi.forEach { (dpi, f) -> put(dpi.toString(), f.toDouble()) } })
        }
        put(KEY_ICON_INSET, iconInset.toDouble())
        put(KEY_LEGACY_ICON_TRAY, legacyIconTray)
        put(KEY_SOURCE, source.name.lowercase())
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_HOME_COLS = "home_cols"
        const val KEY_HOME_ROWS = "home_rows"
        const val KEY_DOCK_SIZE = "dock_size"
        const val KEY_DRAWER_MODE = "drawer_mode"
        const val KEY_DRAWER_COLS = "drawer_cols"
        const val KEY_DRAWER_ROWS = "drawer_rows"
        const val KEY_DRAWER_SEARCH = "drawer_search"
        const val KEY_HOME_LABELS = "home_labels"
        const val KEY_DRAWER_LABELS = "drawer_labels"
        const val KEY_DRAWER_SEARCH_POSITION = "drawer_search_position"
        const val KEY_ICON_SCALE = "icon_scale"
        const val KEY_LABEL_SP = "label_sp"
        const val KEY_ICON_DP = "icon_dp"
        const val KEY_DOCK_ICON_DP = "dock_icon_dp"
        const val KEY_DRAWER_ICON_DP = "drawer_icon_dp"
        const val KEY_ICON_WIDTH_FRACTION = "icon_width_fraction"
        const val KEY_ICON_WIDTH_FRACTION_BY_DPI = "icon_width_fraction_by_dpi"
        const val KEY_DOCK_ICON_WIDTH_FRACTION = "dock_icon_width_fraction"
        const val KEY_DRAWER_ICON_WIDTH_FRACTION = "drawer_icon_width_fraction"
        const val KEY_ICON_INSET = "icon_inset"
        const val KEY_LEGACY_ICON_TRAY = "legacy_icon_tray"
        const val LEGACY_TRAY_WHITE = "white"
        const val LEGACY_TRAY_DOMINANT = "dominant"
        val FIELD_KEYS = listOf(
            KEY_HOME_COLS, KEY_HOME_ROWS, KEY_DOCK_SIZE, KEY_DRAWER_MODE, KEY_DRAWER_COLS, KEY_DRAWER_ROWS,
            KEY_DRAWER_SEARCH, KEY_DRAWER_SEARCH_POSITION, KEY_HOME_LABELS, KEY_DRAWER_LABELS, KEY_ICON_SCALE, KEY_ICON_DP,
            KEY_DOCK_ICON_DP, KEY_DRAWER_ICON_DP, KEY_LABEL_SP, KEY_LEGACY_ICON_TRAY,
        )

        const val MIN_ICON_FRACTION = 0.05f
        const val MAX_ICON_FRACTION = 0.5f
        const val MIN_ICON_DP = 24
        const val MAX_ICON_DP = 120
        const val MIN_ICON_SCALE = 0.5f
        const val MAX_ICON_SCALE = 1.5f

        const val MIN_LABEL_SP = 8f
        const val MAX_LABEL_SP = 20f
        const val KEY_SOURCE = "source"

        val AOSP = MainPreset(
            id = "aosp",
            homeCols = 5,
            homeRows = 5,
            dockSize = 5,
            drawerMode = SliderWay.VERTICAL,
            drawerCols = 5,
            drawerRows = 0,
            drawerSearch = true,
            homeLabels = true,
            drawerLabels = true,
            source = PresetFeed.STANDARD,
        )

        fun fromJson(o: JSONObject, defaults: MainPreset = AOSP, source: PresetFeed): MainPreset? {
            val id = o.optString(KEY_ID).trim().takeUnless { it.isEmpty() } ?: return null
            fun dbl(key: String, fallback: Float): Float = o.optDouble(key, fallback.toDouble()).toFloat()
            val candidate = MainPreset(
                id = id,
                homeCols = o.optInt(KEY_HOME_COLS, defaults.homeCols),
                homeRows = o.optInt(KEY_HOME_ROWS, defaults.homeRows),
                dockSize = o.optInt(KEY_DOCK_SIZE, defaults.dockSize),
                drawerMode = enumNamed<SliderWay>(o.optString(KEY_DRAWER_MODE)) ?: defaults.drawerMode,
                drawerCols = o.optInt(KEY_DRAWER_COLS, defaults.drawerCols),
                drawerRows = o.optInt(KEY_DRAWER_ROWS, defaults.drawerRows),
                drawerSearch = o.optBoolean(KEY_DRAWER_SEARCH, defaults.drawerSearch),
                homeLabels = o.optBoolean(KEY_HOME_LABELS, defaults.homeLabels),
                drawerLabels = o.optBoolean(KEY_DRAWER_LABELS, defaults.drawerLabels),
                source = source,
                drawerSearchPosition = enumNamed<SliderHuntPlacement>(o.optString(KEY_DRAWER_SEARCH_POSITION))
                    ?: defaults.drawerSearchPosition,
                iconScale = dbl(KEY_ICON_SCALE, defaults.iconScale),
                labelSp = dbl(KEY_LABEL_SP, defaults.labelSp),
                iconDp = o.optInt(KEY_ICON_DP, defaults.iconDp),
                dockIconDp = o.optInt(KEY_DOCK_ICON_DP, defaults.dockIconDp),
                drawerIconDp = o.optInt(KEY_DRAWER_ICON_DP, defaults.drawerIconDp),
                iconWidthFraction = dbl(KEY_ICON_WIDTH_FRACTION, defaults.iconWidthFraction),
                dockIconWidthFraction = dbl(KEY_DOCK_ICON_WIDTH_FRACTION, defaults.dockIconWidthFraction),
                drawerIconWidthFraction = dbl(KEY_DRAWER_ICON_WIDTH_FRACTION, defaults.drawerIconWidthFraction),
                iconWidthFractionByDpi = o.optJSONObject(KEY_ICON_WIDTH_FRACTION_BY_DPI)?.let(::dpiTable)
                    ?: defaults.iconWidthFractionByDpi,
                iconInset = dbl(KEY_ICON_INSET, defaults.iconInset),
                legacyIconTray = o.optString(KEY_LEGACY_ICON_TRAY).trim().lowercase().ifEmpty { defaults.legacyIconTray },
            )
            return if (candidate.isSane()) candidate else null
        }

        private fun dpiTable(node: JSONObject): Map<Int, Float> {
            val out = LinkedHashMap<Int, Float>()
            for (key in node.keys()) {
                val dpi = key.trim().toIntOrNull() ?: continue
                out[dpi] = node.optDouble(key, Double.NaN).toFloat()
            }
            return out
        }

        private inline fun <reified E : Enum<E>> enumNamed(raw: String): E? {
            val wanted = raw.trim()
            return enumValues<E>().firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        }

        const val MIN_CELLS = 2
        const val MAX_CELLS = 12
    }

    fun isSane(): Boolean {
        val cells = MIN_CELLS..MAX_CELLS
        val optionalCells = 0..MAX_CELLS
        fun dpOk(v: Int) = v == 0 || v in MIN_ICON_DP..MAX_ICON_DP
        fun fractionOk(v: Float) = !v.isNaN() && (v == 0f || v in MIN_ICON_FRACTION..MAX_ICON_FRACTION)
        val fractions = listOf(iconWidthFraction, dockIconWidthFraction, drawerIconWidthFraction) + iconWidthFractionByDpi.values
        return when {
            homeCols !in cells || homeRows !in cells -> false
            dockSize !in optionalCells || drawerCols !in cells || drawerRows !in optionalCells -> false
            iconScale.isNaN() || iconScale !in MIN_ICON_SCALE..MAX_ICON_SCALE -> false
            labelSp.isNaN() || (labelSp != 0f && labelSp !in MIN_LABEL_SP..MAX_LABEL_SP) -> false
            !dpOk(iconDp) || !dpOk(dockIconDp) || !dpOk(drawerIconDp) -> false
            !fractions.all(::fractionOk) -> false
            iconWidthFractionByDpi.keys.any { it !in 100..1000 } -> false
            iconInset.isNaN() || (iconInset != 0f && iconInset !in 0.5f..1f) -> false
            else -> legacyIconTray == LEGACY_TRAY_WHITE || legacyIconTray == LEGACY_TRAY_DOMINANT
        }
    }
}
