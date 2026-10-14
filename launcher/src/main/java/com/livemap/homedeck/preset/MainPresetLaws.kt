package com.livemap.homedeck.preset

import org.json.JSONArray
import org.json.JSONObject

object MainPresetLaws {
    class Rule(val match: Match, val profile: MainPreset)

    class Match(
        val packages: Set<String>,
        val manufacturers: Set<String>,
        val modelPrefix: String?,
        val versionCodeMin: Long?,
        val versionCodeMax: Long?,
        val sdkMin: Int?,
        val sdkMax: Int?,
        val swDpMin: Int?,
        val swDpMax: Int?,
        val models: Set<String> = emptySet(),
        val devices: Set<String> = emptySet(),
        val products: Set<String> = emptySet(),
        val osVersionMin: Long? = null,
        val osVersionMax: Long? = null,
        val osVersionPrefix: String? = null,
        val densityMin: Int? = null,
        val densityMax: Int? = null,
        val widthDpMin: Int? = null,
        val widthDpMax: Int? = null,
        val heightDpMin: Int? = null,
        val heightDpMax: Int? = null,
        val navMode: Int? = null,
    ) {
        val specificity: Int = booleanArrayOf(
            packages.isNotEmpty(),
            manufacturers.isNotEmpty(),
            modelPrefix != null,
            models.isNotEmpty(),
            devices.isNotEmpty(),
            products.isNotEmpty(),
            versionCodeMin != null || versionCodeMax != null,
            osVersionMin != null || osVersionMax != null || osVersionPrefix != null,
            sdkMin != null || sdkMax != null,
            swDpMin != null || swDpMax != null,
            densityMin != null || densityMax != null,
            widthDpMin != null || widthDpMax != null,
            heightDpMin != null || heightDpMax != null,
            navMode != null,
        ).count { it }

        fun matches(fp: HomeThumbprint): Boolean {
            fun <T : Comparable<T>> bounded(value: T, low: T?, high: T?): Boolean =
                (low == null || value >= low) && (high == null || value <= high)

            fun Set<String>.accepts(value: String): Boolean =
                isEmpty() || any { it.equals(value, ignoreCase = true) }

            return when {
                packages.isNotEmpty() && fp.homePackage !in packages -> false
                manufacturers.isNotEmpty() && fp.manufacturer !in manufacturers && fp.brand !in manufacturers -> false
                modelPrefix != null && !fp.model.startsWith(modelPrefix, ignoreCase = true) -> false
                !models.accepts(fp.model) -> false
                !devices.accepts(fp.device) -> false
                !products.accepts(fp.product) -> false
                !bounded(fp.homeVersionCode, versionCodeMin, versionCodeMax) -> false
                (osVersionMin != null || osVersionMax != null) &&
                    !bounded(fp.osVersionNumber, osVersionMin, osVersionMax) -> false
                osVersionPrefix != null && !fp.osVersion.startsWith(osVersionPrefix, ignoreCase = true) -> false
                !bounded(fp.sdk, sdkMin, sdkMax) -> false
                !bounded(fp.swDp, swDpMin, swDpMax) -> false
                !bounded(fp.densityDpi, densityMin, densityMax) -> false
                !bounded(fp.widthDp, widthDpMin, widthDpMax) -> false
                !bounded(fp.heightDp, heightDpMin, heightDpMax) -> false
                navMode != null && fp.navMode != navMode -> false
                else -> true
            }
        }
    }

    class Table(val disabled: Boolean, val rules: List<Rule>) {
        fun select(fp: HomeThumbprint): MainPreset? {
            var winner: Rule? = null
            for (candidate in rules) {
                if (!candidate.match.matches(fp)) continue
                if (winner == null || candidate.match.specificity > winner.match.specificity) winner = candidate
            }
            return winner?.profile
        }
    }

    const val KEY_DISABLED = "disabled"
    const val KEY_RULES = "rules"
    const val KEY_MATCH = "match"
    const val KEY_PROFILE = "profile"
    const val KEY_PACKAGE = "package"
    const val KEY_MANUFACTURER = "manufacturer"
    const val KEY_MODEL_PREFIX = "model_prefix"
    const val KEY_VERSION_CODE_MIN = "version_code_min"
    const val KEY_VERSION_CODE_MAX = "version_code_max"
    const val KEY_SDK_MIN = "sdk_min"
    const val KEY_SDK_MAX = "sdk_max"
    const val KEY_SW_DP_MIN = "sw_dp_min"
    const val KEY_SW_DP_MAX = "sw_dp_max"
    const val KEY_MODEL = "model"
    const val KEY_DEVICE = "device"
    const val KEY_PRODUCT = "product"
    const val KEY_OS_VERSION_MIN = "os_version_min"
    const val KEY_OS_VERSION_MAX = "os_version_max"
    const val KEY_OS_VERSION_PREFIX = "os_version_prefix"
    const val KEY_DENSITY_MIN = "density_min"
    const val KEY_DENSITY_MAX = "density_max"
    const val KEY_WIDTH_DP_MIN = "width_dp_min"
    const val KEY_WIDTH_DP_MAX = "width_dp_max"
    const val KEY_HEIGHT_DP_MIN = "height_dp_min"
    const val KEY_HEIGHT_DP_MAX = "height_dp_max"
    const val KEY_NAV_MODE = "nav_mode"

    fun parse(raw: String, source: PresetFeed): Table? {
        val text = raw.trim().takeUnless { it.isEmpty() } ?: return null
        return runCatching {
            val wrapper: JSONObject? = if (text.startsWith("[")) null else JSONObject(text)
            val entries = if (wrapper == null) JSONArray(text) else wrapper.optJSONArray(KEY_RULES) ?: JSONArray()
            val off = wrapper?.optBoolean(KEY_DISABLED, false) ?: false
            val collected = buildList {
                for (index in 0 until entries.length()) {
                    val entry = entries.optJSONObject(index) ?: continue
                    val preset = entry.optJSONObject(KEY_PROFILE)?.let { MainPreset.fromJson(it, source = source) } ?: continue
                    add(Rule(readMatch(entry.optJSONObject(KEY_MATCH) ?: JSONObject()), preset))
                }
            }
            Table(off, collected)
        }.getOrNull()
    }

    private fun readMatch(node: JSONObject) = Match(
        packages = stringSet(node, KEY_PACKAGE),
        manufacturers = stringSet(node, KEY_MANUFACTURER).mapTo(LinkedHashSet()) { it.lowercase() },
        modelPrefix = trimmedOrNull(node, KEY_MODEL_PREFIX),
        versionCodeMin = optionalLong(node, KEY_VERSION_CODE_MIN),
        versionCodeMax = optionalLong(node, KEY_VERSION_CODE_MAX),
        sdkMin = optionalInt(node, KEY_SDK_MIN),
        sdkMax = optionalInt(node, KEY_SDK_MAX),
        swDpMin = optionalInt(node, KEY_SW_DP_MIN),
        swDpMax = optionalInt(node, KEY_SW_DP_MAX),
        models = stringSet(node, KEY_MODEL),
        devices = stringSet(node, KEY_DEVICE),
        products = stringSet(node, KEY_PRODUCT),
        osVersionMin = optionalLong(node, KEY_OS_VERSION_MIN),
        osVersionMax = optionalLong(node, KEY_OS_VERSION_MAX),
        osVersionPrefix = trimmedOrNull(node, KEY_OS_VERSION_PREFIX),
        densityMin = optionalInt(node, KEY_DENSITY_MIN),
        densityMax = optionalInt(node, KEY_DENSITY_MAX),
        widthDpMin = optionalInt(node, KEY_WIDTH_DP_MIN),
        widthDpMax = optionalInt(node, KEY_WIDTH_DP_MAX),
        heightDpMin = optionalInt(node, KEY_HEIGHT_DP_MIN),
        heightDpMax = optionalInt(node, KEY_HEIGHT_DP_MAX),
        navMode = optionalInt(node, KEY_NAV_MODE),
    )

    private fun trimmedOrNull(node: JSONObject, key: String): String? =
        node.optString(key).trim().takeIf { it.isNotEmpty() }

    private fun stringSet(node: JSONObject, key: String): Set<String> {
        val raw = node.optJSONArray(key)?.let { arr -> List(arr.length()) { arr.optString(it) } }
            ?: listOf(node.optString(key))
        return raw.map { it.trim() }.filterTo(LinkedHashSet()) { it.isNotEmpty() }
    }

    private fun stated(node: JSONObject, key: String) = node.has(key) && !node.isNull(key)
    private fun optionalLong(node: JSONObject, key: String): Long? = node.optLong(key).takeIf { stated(node, key) }
    private fun optionalInt(node: JSONObject, key: String): Int? = node.optInt(key).takeIf { stated(node, key) }

    val knownLaunchers: Set<String> by lazy {
        builtIn.rules.flatMap { it.match.packages }.toSet()
    }

    fun isKnownLauncher(packageName: String): Boolean = packageName in knownLaunchers

    val builtIn: Table by lazy {
        parse(DEFAULT_RULES_JSON, PresetFeed.BUILT_IN) ?: Table(disabled = false, rules = emptyList())
    }

    const val DEFAULT_RULES_JSON = """
[
  { "match": { "sw_dp_min": 720 },
    "profile": { "id": "aosp_tablet", "home_cols": 6, "home_rows": 5, "drawer_cols": 6 } },
  { "match": { "sw_dp_min": 600, "sw_dp_max": 719 },
    "profile": { "id": "aosp_fold_inner", "home_cols": 6, "home_rows": 5, "drawer_cols": 6 } },

  { "match": { "package": "com.sec.android.app.launcher", "sw_dp_min": 720 },
    "profile": { "id": "samsung_tablet", "home_cols": 6, "home_rows": 5, "drawer_mode": "paged", "drawer_cols": 8, "drawer_rows": 6, "legacy_icon_tray": "white" } },
  { "match": { "package": "com.sec.android.app.launcher", "sw_dp_min": 600, "sw_dp_max": 719 },
    "profile": { "id": "samsung_fold_inner", "home_cols": 6, "home_rows": 5, "drawer_mode": "paged", "drawer_cols": 6, "drawer_rows": 5, "legacy_icon_tray": "white" } },
  { "match": { "package": "com.sec.android.app.launcher", "os_version_min": 70000 },
    "profile": { "id": "samsung_phone_oneui7", "home_cols": 4, "home_rows": 6, "dock_size": 4, "drawer_mode": "paged", "drawer_cols": 4, "drawer_rows": 6, "drawer_search_position": "bottom", "label_sp": 11, "icon_width_fraction": 0.164, "icon_width_fraction_by_dpi": { "420": 0.161, "450": 0.164, "480": 0.168, "510": 0.170, "540": 0.172 }, "icon_inset": 0.95, "legacy_icon_tray": "white" } },
  { "match": { "package": "com.sec.android.app.launcher" },
    "profile": { "id": "samsung_phone", "home_cols": 4, "home_rows": 5, "dock_size": 4, "drawer_mode": "paged", "drawer_cols": 4, "drawer_rows": 6, "legacy_icon_tray": "white" } },

  { "match": { "package": "com.google.android.apps.nexuslauncher" },
    "profile": { "id": "pixel_phone", "home_cols": 5, "home_rows": 5, "drawer_cols": 5 } },
  { "match": { "package": ["com.android.launcher3", "com.motorola.launcher3", "com.nothing.launcher", "com.sonymobile.home", "com.fairphone.launcher3"] },
    "profile": { "id": "launcher3", "home_cols": 5, "home_rows": 5, "drawer_cols": 5 } },

  { "match": { "package": "com.miui.home" },
    "profile": { "id": "miui_classic", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },
  { "match": { "package": "com.mi.android.globallauncher" },
    "profile": { "id": "poco", "home_cols": 4, "home_rows": 6, "drawer_cols": 4 } },

  { "match": { "package": ["com.android.launcher", "com.oppo.launcher", "net.oneplus.launcher", "com.oneplus.launcher"] },
    "profile": { "id": "coloros", "home_cols": 4, "home_rows": 6, "drawer_cols": 4 } },
  { "match": { "package": ["com.android.launcher", "com.oppo.launcher", "net.oneplus.launcher", "com.oneplus.launcher"], "os_version_min": 150000 },
    "profile": { "id": "coloros15", "home_cols": 4, "home_rows": 6, "drawer_cols": 4, "drawer_search_position": "bottom" } },

  { "match": { "package": "com.bbk.launcher2" },
    "profile": { "id": "vivo_classic", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },

  { "match": { "package": "com.huawei.android.launcher" },
    "profile": { "id": "huawei_classic", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },
  { "match": { "package": "com.hihonor.android.launcher" },
    "profile": { "id": "honor_magicos", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },

  { "match": { "package": ["com.transsion.hilauncher", "com.transsion.XOSLauncher", "com.transsion.itel.launcher"] },
    "profile": { "id": "transsion", "home_cols": 4, "home_rows": 6, "drawer_cols": 4 } },

  { "match": { "package": ["com.teslacoilsw.launcher", "com.microsoft.launcher", "app.lawnchair", "bitpit.launcher", "ginlemon.flowerfree", "com.actionlauncher.playstore"] },
    "profile": { "id": "third_party" } },

  { "match": { "manufacturer": "samsung" },
    "profile": { "id": "samsung_phone", "home_cols": 4, "home_rows": 5, "dock_size": 4, "drawer_mode": "paged", "drawer_cols": 4, "drawer_rows": 6, "legacy_icon_tray": "white" } },
  { "match": { "manufacturer": ["xiaomi", "redmi", "poco"] },
    "profile": { "id": "miui_classic", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },
  { "match": { "manufacturer": ["oppo", "realme", "oneplus"] },
    "profile": { "id": "coloros", "home_cols": 4, "home_rows": 6, "drawer_cols": 4 } },
  { "match": { "manufacturer": ["oppo", "realme", "oneplus"], "os_version_min": 150000 },
    "profile": { "id": "coloros15", "home_cols": 4, "home_rows": 6, "drawer_cols": 4, "drawer_search_position": "bottom" } },
  { "match": { "manufacturer": ["vivo", "iqoo"] },
    "profile": { "id": "vivo_classic", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },
  { "match": { "manufacturer": "huawei" },
    "profile": { "id": "huawei_classic", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },
  { "match": { "manufacturer": "honor" },
    "profile": { "id": "honor_magicos", "home_cols": 4, "home_rows": 6, "drawer_mode": "none", "drawer_cols": 4 } },
  { "match": { "manufacturer": ["tecno", "infinix", "itel"] },
    "profile": { "id": "transsion", "home_cols": 4, "home_rows": 6, "drawer_cols": 4 } }
]
"""
}
