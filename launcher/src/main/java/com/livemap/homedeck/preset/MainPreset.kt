package com.livemap.homedeck.preset

import org.json.JSONObject

/** How the app drawer is reached: a vertical scrolling list, Samsung-style horizontal pages, or not at all (every app on the workspace). */
enum class SliderWay { VERTICAL, PAGED, NONE }

/** Where the drawer's search bar sits - Samsung moved its Finder bar to the bottom with One UI 6.1. */
enum class SliderHuntPlacement { TOP, BOTTOM }

/**
 * Where a value came from - the tiers of the design: the launcher's own APK resources (B), a
 * documented OEM default from Remote Config or the built-in table (C), or our standard
 * configuration when nothing was known (STANDARD).
 */
enum class PresetFeed { APK, RC_RULE, BUILT_IN, STANDARD }

/**
 * The factory look of one launcher build on one device class: what the user saw before we became
 * the home app, expressed in our own knobs. Numbers are the OEM's — [homeRows] counts the rows
 * *above* the dock, the way every launcher advertises its grid ("4×5"), and the applier adds the
 * dock row when it writes `Config.homeRowCount`.
 */
data class MainPreset(
    val id: String,
    val homeCols: Int,
    val homeRows: Int,
    val dockSize: Int,
    val drawerMode: SliderWay,
    val drawerCols: Int,
    /** Meaningful for [SliderWay.PAGED] only. */
    val drawerRows: Int,
    val drawerSearch: Boolean,
    val homeLabels: Boolean,
    val drawerLabels: Boolean,
    val source: PresetFeed,
    val drawerSearchPosition: SliderHuntPlacement = SliderHuntPlacement.TOP,
    /** Icon size relative to ours at the same grid: 1.0 is what the grid draws by itself. Used only when [iconDp] is 0. */
    val iconScale: Float = 1f,
    /** The icon size the launcher we replaced drew on its workspace, in dp; 0 = unknown. Drawn exactly, centred in the cell. */
    val iconDp: Int = 0,
    /** Dock icon in dp; 0 = same as [iconDp]. One UI draws its label-less hotseat larger than the workspace. */
    val dockIconDp: Int = 0,
    /** Drawer icon in dp; 0 = same as [iconDp]. */
    val drawerIconDp: Int = 0,
    /**
     * The same three sizes as a fraction of the display's short side, the way Launcher3-free OEM
     * launchers state them in code (One UI: `width × 0.164`); resolved into the dp fields against
     * this device's width when the dp field is 0. 0 = not stated.
     */
    val iconWidthFraction: Float = 0f,
    val dockIconWidthFraction: Float = 0f,
    val drawerIconWidthFraction: Float = 0f,
    /**
     * [iconWidthFraction] per screen density, for a launcher that picks its icon ratio by screen
     * zoom (One UI's `PhoneItemStyleFactory.getDefaultIconSize`: 0.161 at 420 dpi up to 0.172 at
     * 540). The entry nearest the display's density wins over [iconWidthFraction]. Empty = none.
     */
    val iconWidthFractionByDpi: Map<Int, Float> = emptyMap(),
    /**
     * The share of its stated item size the launcher actually fills with icon art, for a launcher
     * that keeps a margin inside the item. We draw the art edge to edge, so the drawn dp is the item
     * dp times this. 0 = 1.0 (One UI: its width fraction is already the art).
     */
    val iconInset: Float = 0f,
    /** The tray behind a wrapped legacy icon: `white` (One UI) or `dominant` (Launcher3: a colour from the art). */
    val legacyIconTray: String = LEGACY_TRAY_DOMINANT,
    /** App-label text size in sp; 0 = our standard size (nothing known). One UI phones draw 11, Launcher3 states its own `iconTextSize`. */
    val labelSp: Float = 0f,
    /** Per field, which tier supplied it; fields not listed carry [source]. */
    val fieldSources: Map<String, PresetFeed> = emptyMap(),
) {
    /** The grid numbers a launcher's resources can supply; anything null keeps the base profile's value. */
    data class Numbers(
        val homeCols: Int? = null,
        val homeRows: Int? = null,
        val dockSize: Int? = null,
        val drawerCols: Int? = null,
        /** The launcher's workspace icon size in dp (Launcher3 `iconImageSize`). */
        val iconSizeDp: Int? = null,
        /** The launcher's workspace label text size in sp (Launcher3 `iconTextSize`, or a dimen read with its unit). */
        val labelSp: Float? = null,
        /** Drawer icon in dp when the launcher states one apart from the workspace (Launcher3 `allAppsIconSize`). */
        val drawerIconDp: Int? = null,
        /** Dock icon in dp when stated apart (Launcher3 `hotseatIconSize`, where a fork declares it). */
        val dockIconDp: Int? = null,
        /**
         * Whether the launcher we replaced keeps an app drawer, when its own build states it -
         * only launchers whose probe entry says where to read it (ColorOS / OxygenOS today).
         */
        val drawerMode: SliderWay? = null,
        /** Whether that launcher put each new install on the workspace, when its build states it. */
    ) {
        val isEmpty: Boolean
            get() = homeCols == null && homeRows == null && dockSize == null && drawerCols == null && iconSizeDp == null &&
                labelSp == null && drawerIconDp == null && dockIconDp == null && drawerMode == null

        /** This, with [other] filling whatever this leaves null. */
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

    /**
     * Applies an APK's own [numbers] on top of this profile. [ownIconDp] gives the icon size our grid
     * draws at a column count, turning an OEM icon dp into a relative scale.
     */
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

    /**
     * Resolves width fractions into dp for a display [widthDp] wide. A stated fraction beats an
     * APK dp because it is the launcher's actual sizing rule. Calling it twice changes nothing.
     */
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

    /** Workspace icon fraction for [densityDpi]: closest per-density entry, otherwise the flat value. */
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

    /** The tier behind one field, for the log. */
    fun sourceOf(key: String): PresetFeed = fieldSources[key] ?: source

    /** One line naming every field and its tier. Local log only; nothing leaves the device. */
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
        /** A fraction of the short side outside this cannot be an app icon (One UI phones sit at 0.14 - 0.20). */
        const val MIN_ICON_FRACTION = 0.05f
        const val MAX_ICON_FRACTION = 0.5f
        const val MIN_ICON_DP = 24
        const val MAX_ICON_DP = 120
        const val MIN_ICON_SCALE = 0.5f
        const val MAX_ICON_SCALE = 1.5f
        /** A label smaller than 8 sp is unreadable, larger than 20 sp is not an app label; 0 means "ours". */
        const val MIN_LABEL_SP = 8f
        const val MAX_LABEL_SP = 20f
        const val KEY_SOURCE = "source"

        /** Our standard configuration: what a launcher gets when neither its APK nor a documented OEM default says otherwise. */
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

        /**
         * Builds a profile from a rule's `profile` object; unspecified fields come from [defaults].
         * Null when the id is blank or a value falls outside what the grid can draw.
         */
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

        /** Bounds the grid can actually draw; a console typo must not produce a 0-column home. */
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
