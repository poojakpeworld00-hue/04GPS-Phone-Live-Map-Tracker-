package com.livemap.homedeck.preset

import android.content.res.Resources
import android.util.TypedValue

object CommonLatticeScanner {
    enum class Field { HOME_COLS, HOME_ROWS, DRAWER_COLS, ICON_DP, LABEL_SP }

    private val INCLUDE = mapOf(
        Field.HOME_COLS to Regex("(cell_?count_?x|num_?columns|columns|column_?count|grid_?x|cells?_?x|col_?num)$", RegexOption.IGNORE_CASE),
        Field.HOME_ROWS to Regex("(cell_?count_?y|num_?rows|rows|row_?count|grid_?y|cells?_?y|row_?num)$", RegexOption.IGNORE_CASE),
        Field.DRAWER_COLS to Regex("(all_?apps|drawer|apps?_?list|apps?_?page).*(columns?|cell_?count_?x|num_?columns|cols?)$", RegexOption.IGNORE_CASE),
        Field.ICON_DP to Regex("^(app_?icon_?size|icon_?size|icon_?image_?size|workspace_?icon_?size|home_?icon_?size)$", RegexOption.IGNORE_CASE),

        Field.LABEL_SP to Regex(
            "^((workspace|home|app|launcher)_?)?icon_?(label|title|text)_?(text_?)?size$|^(workspace|home|app)_?(label|title)_?(text_?)?size$",
            RegexOption.IGNORE_CASE,
        ),
    )

    private val EXCLUDE_LABEL = Regex(
        "(max|min|fold|land|tablet|pad|large|small|folder|widget|recent|task|preview|notif|badge|popup|shortcut|search|" +
            "anim|duration|percent|guide|sheet|thumbnail|bubble|tutorial|gesture|dynamic|extra|elderly|old|lite|edit|" +
            "zoom|scale|ratio|margin|padding|gap|offset|index|height|width|line|stroke|easy|simple|kids|demo|retail|" +
            "dock|hotseat|assistant|weather|clock|calendar|contact|split|window|freeform|desk|dex|suggest|card|taskbar|" +
            "all_?apps|drawer|apps?_?list|apps?_?page|picker|dialog|setting|bixby|edge|quick|panel|menu|hint|tip|history)",
        RegexOption.IGNORE_CASE,
    )

    private val EXCLUDE = Regex(
        "(max|min|fold|land|tablet|pad|large|small|folder|widget|recent|task|preview|notif|badge|popup|shortcut|search|" +
            "anim|duration|percent|guide|sheet|responsive|thumbnail|cache|bubble|overflow|tutorial|gesture|dynamic|extra|" +
            "vertical|horizontal|elderly|old|lite|edit|zoom|scale|ratio|margin|padding|gap|offset|index|flag|progress|" +
            "mode|type|state|level|limit|threshold|delay|time|speed|height|width|radius|alpha|color|text|font|line|stroke|" +
            "blur|mishow|easy|simple|kids|demo|retail|dock|hotseat|assistant|weather|clock|calendar|contact|split|window|freeform|" +
            "desk|dex|suggest|card|taskbar|directory|list_card|version)",
        RegexOption.IGNORE_CASE,
    )

    private val NOT_HOME = Regex("(all_?apps|drawer|apps?_?list|apps?_?page|folder)", RegexOption.IGNORE_CASE)

    private val PREFERRED = Regex("(^|_)(config|default)(_|$)", RegexOption.IGNORE_CASE)
    private val CORE = Regex("(cell_?count|num_?columns|num_?rows|numColumns|numRows|workspace|home|grid)", RegexOption.IGNORE_CASE)

    fun rowSibling(name: String): String? {
        val swaps = listOf(
            Regex("(?i)cell_?count_?x") to { m: MatchResult -> m.value.replace(Regex("(?i)x$"), if (m.value.last().isUpperCase()) "Y" else "y") },
            Regex("(?i)num_?columns") to { m: MatchResult -> m.value.replace(Regex("(?i)columns"), if (m.value.contains("Columns")) "Rows" else "rows") },
            Regex("(?i)column_?count") to { m: MatchResult -> m.value.replace(Regex("(?i)column"), if (m.value.contains("Column")) "Row" else "row") },
            Regex("(?i)columns") to { m: MatchResult -> if (m.value.contains("Columns")) "Rows" else "rows" },
            Regex("(?i)grid_?x") to { m: MatchResult -> m.value.replace(Regex("(?i)x$"), if (m.value.last().isUpperCase()) "Y" else "y") },
            Regex("(?i)cells?_?x") to { m: MatchResult -> m.value.replace(Regex("(?i)x$"), if (m.value.last().isUpperCase()) "Y" else "y") },
            Regex("(?i)col_?num") to { m: MatchResult -> m.value.replace(Regex("(?i)col"), if (m.value.contains("Col")) "Row" else "row") },
        )
        for ((rx, swap) in swaps) {
            val m = rx.find(name) ?: continue
            return name.replaceRange(m.range, swap(m))
        }
        return null
    }

    fun rank(field: Field, names: Collection<String>): List<String> {
        val include = INCLUDE.getValue(field)
        val exclude = if (field == Field.LABEL_SP) EXCLUDE_LABEL else EXCLUDE
        return names.asSequence()
            .filter { include.containsMatchIn(it) }
            .filter { !exclude.containsMatchIn(it) }
            .filter { field == Field.DRAWER_COLS || !NOT_HOME.containsMatchIn(it) }
            .map { it to score(it) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.length }.thenBy { it.first })
            .map { it.first }
            .toList()
    }

    private fun score(name: String): Int {
        var s = 0
        if (PREFERRED.containsMatchIn(name)) s += 3
        if (CORE.containsMatchIn(name)) s += 2
        s -= name.length / 20
        return s
    }

    fun read(resources: Resources, packageName: String, index: BundleResLedger): MainPreset.Numbers {
        val integers = index.names("integer")
        val dimens = index.names("dimen")
        fun pullInt(name: String): Int? {
            val id = resources.getIdentifier(name, "integer", packageName)
            if (id == 0) return null
            return runCatching { resources.getInteger(id) }.getOrNull()
        }
        fun firstSane(field: Field, range: IntRange): Int? {
            for (name in rank(field, integers)) {
                val value = pullInt(name) ?: continue
                if (value in range) return value
            }
            return null
        }

        var homeCols: Int? = null
        var homeRows: Int? = null
        for (colsName in rank(Field.HOME_COLS, integers)) {
            val rowsName = rowSibling(colsName) ?: continue
            if (rowsName !in integers) continue
            val cols = pullInt(colsName) ?: continue
            val rows = pullInt(rowsName) ?: continue
            if (cols in 3..8 && rows in 3..9) { homeCols = cols; homeRows = rows; break }
        }
        val iconDp = run {
            val density = resources.displayMetrics.density
            for (name in rank(Field.ICON_DP, dimens)) {
                val id = resources.getIdentifier(name, "dimen", packageName)
                if (id == 0) continue
                val dp = runCatching { (resources.getDimension(id) / density).toInt() }.getOrNull() ?: continue
                if (dp in 32..96) return@run dp
            }
            for (name in rank(Field.ICON_DP, integers)) {
                val id = resources.getIdentifier(name, "integer", packageName)
                if (id == 0) continue
                val dp = runCatching { resources.getInteger(id) }.getOrNull() ?: continue
                if (dp in 32..96) return@run dp
            }
            null
        }
        val labelSp = rank(Field.LABEL_SP, dimens).firstNotNullOfOrNull { name ->
            val id = resources.getIdentifier(name, "dimen", packageName)
            if (id == 0) null else pullTextSizeSp(resources, id)?.takeIf { it in MainPreset.MIN_LABEL_SP..MainPreset.MAX_LABEL_SP }
        }
        return MainPreset.Numbers(
            homeCols = homeCols,
            homeRows = homeRows,
            drawerCols = firstSane(Field.DRAWER_COLS, 3..8),
            iconSizeDp = iconDp,
            labelSp = labelSp,
        )
    }

    fun pullTextSizeSp(resources: Resources, id: Int): Float? = runCatching {
        val value = TypedValue()
        resources.getValue(id, value, true)
        if (value.type != TypedValue.TYPE_DIMENSION) return null
        when (value.data and TypedValue.COMPLEX_UNIT_MASK) {
            TypedValue.COMPLEX_UNIT_SP, TypedValue.COMPLEX_UNIT_DIP -> TypedValue.complexToFloat(value.data)
            else -> TypedValue.complexToDimension(value.data, resources.displayMetrics) / resources.displayMetrics.scaledDensity
        }
    }.getOrNull()
}
