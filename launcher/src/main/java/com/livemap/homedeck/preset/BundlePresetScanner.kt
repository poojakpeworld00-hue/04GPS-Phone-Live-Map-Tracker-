package com.livemap.homedeck.preset

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.provider.Settings
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import kotlin.math.hypot

object BundlePresetScanner {
    private const val RES_AUTO_NS = "http://schemas.android.com/apk/res-auto"
    private const val DEVICE_PROFILES = "device_profiles"
    private const val TAG_GRID_OPTION = "grid-option"
    private const val TAG_DISPLAY_OPTION = "display-option"

    const val KEY_HOME_COLS = "home_cols"
    const val KEY_HOME_ROWS = "home_rows"
    const val KEY_DOCK_SIZE = "dock_size"
    const val KEY_DRAWER_COLS = "drawer_cols"
    const val KEY_ICON_SIZE_DP = "icon_size_dp"
    const val KEY_LABEL_SP = "label_sp"

    const val KEY_DRAWER_MODE = "drawer_mode"
    const val KEY_DRAWER_MODE_SETTING = "drawer_mode_setting"
    const val KEY_DRAWER_MODE_VALUES = "drawer_mode_values"

    const val DEFAULT_PROBES_JSON = """
{
  "com.miui.home": {
    "_verified": "HyperOS, com.miui.home RELEASE-7.50.06.2592 (Xiaomi 24090RA29I, Android 16)",
    "home_cols": ["config_cell_count_x"],
    "home_rows": ["config_cell_count_y"]
  },
  "com.mi.android.globallauncher": {
    "home_cols": ["config_cell_count_x"],
    "home_rows": ["config_cell_count_y"]
  },
  "com.android.launcher": {
    "_verified": "ColorOS/OxygenOS 14 (14.0.62, OnePlus CPH2469) and 16 (16.4.25, CPH2487); device_profiles.xml is read first, these are the fallback",
    "home_cols": ["device_profiles_display_option_oplusnumColumns"],
    "home_rows": ["device_profiles_display_option_oplusnumRows"],
    "drawer_mode": ["config_default_launcher_mode"],
    "drawer_mode_setting": "launcher_mode",
    "drawer_mode_values": { "0": "none", "2": "vertical", "3": "none" }
  },
  "com.oppo.launcher": {
    "_verified": "not yet - same ColorOS code base, name assumed; a miss keeps the rules-table mode",
    "drawer_mode": ["config_default_launcher_mode"],
    "drawer_mode_setting": "launcher_mode",
    "drawer_mode_values": { "0": "none", "2": "vertical", "3": "none" }
  },
  "net.oneplus.launcher": {
    "_verified": "not yet - pre-merge OxygenOS launcher",
    "drawer_mode": ["config_default_launcher_mode"],
    "drawer_mode_setting": "launcher_mode",
    "drawer_mode_values": { "0": "none", "2": "vertical", "3": "none" }
  },
  "com.oneplus.launcher": {
    "_verified": "not yet - pre-merge OxygenOS launcher",
    "drawer_mode": ["config_default_launcher_mode"],
    "drawer_mode_setting": "launcher_mode",
    "drawer_mode_values": { "0": "none", "2": "vertical", "3": "none" }
  }
}
"""

    class ApkFacts(val numbers: MainPreset.Numbers?, val sources: List<String>)

    fun read(context: Context, fp: HomeThumbprint, probesJson: String): ApkFacts? {
        val target = fp.homePackage
        if (target.isEmpty() || target == context.packageName) return null
        val packages = context.packageManager
        val apkRes = runCatching { packages.getResourcesForApplication(target) }.getOrElse { return null }
        val ledger = runCatching { packages.getApplicationInfo(target, 0).publicSourceDir }.getOrNull()
            ?.let { path -> runCatching { BundleResLedger.read(path) }.getOrNull() }
        val contributors = ArrayList<String>()
        var merged = MainPreset.Numbers()

        val fromProfiles = runCatching { readDeviceProfiles(context, apkRes, target) }
            .onFailure { Timber.d(it, "BundlePresetScanner: device_profiles unreadable for ${fp.homePackage}") }
            .getOrNull()
        if (fromProfiles != null) {
            merged = fromProfiles
            contributors.add("device_profiles")
        }

        if (ledger != null) {
            val generic = runCatching { CommonLatticeScanner.read(apkRes, target, ledger) }
                .onFailure { Timber.d(it, "BundlePresetScanner: generic read failed for ${fp.homePackage}") }
                .getOrNull()
            if (generic != null && !generic.isEmpty) {
                merged = merged.fill(generic)
                contributors.add("generic")
            }
        }

        val probed = runCatching { readProbes(context, apkRes, target, probesJson) }
            .onFailure { Timber.d(it, "BundlePresetScanner: probes failed for ${fp.homePackage}") }
            .getOrNull()
        if (probed != null && !probed.isEmpty) {
            merged = probed.fill(merged)
            contributors.add("probes")
        }

        return ApkFacts(if (merged.isEmpty) null else merged, contributors)
    }

    fun drawerModeOf(value: Int, values: JSONObject?): SliderWay? {
        val label = values?.optString(value.toString())?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return SliderWay.entries.firstOrNull { it.name.equals(label, ignoreCase = true) }
    }

    private class DisplayOption(
        val minWidthDps: Float,
        val minHeightDps: Float,
        val canBeDefault: Boolean,
        val iconImageSize: Float?,
        val iconTextSize: Float?,
        val allAppsIconSize: Float?,
        val hotseatIconSize: Float?,
    )

    private class GridOption(
        val cols: Int,
        val rows: Int,
        val hotseat: Int?,
        val allAppsCols: Int?,
        val deviceCategory: String?,
        val displays: MutableList<DisplayOption> = mutableListOf(),
    )

    private fun iconDpOrNull(raw: Float?): Int? = raw?.toInt()?.takeIf { it in 24..120 }

    private fun readDeviceProfiles(context: Context, apkRes: Resources, packageName: String): MainPreset.Numbers? {
        val xmlId = apkRes.getIdentifier(DEVICE_PROFILES, "xml", packageName)
        if (xmlId == 0) return null
        val grids = apkRes.getXml(xmlId).let { xml ->
            try {
                parseGridOptions(xml)
            } finally {
                xml.close()
            }
        }
        if (grids.isEmpty()) return null

        val dm = context.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels) / dm.density
        val longSide = maxOf(dm.widthPixels, dm.heightPixels) / dm.density
        val category = when {
            context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }

        val pool = grids.filter { it.deviceCategory == null || it.deviceCategory.contains(category) }
            .ifEmpty { grids }
        var chosenGrid: GridOption? = null
        var chosenDisplay: DisplayOption? = null
        var nearest = 0.0
        for (grid in pool) {
            for (option in grid.displays) {
                if (!option.canBeDefault) continue
                val distance = hypot((option.minWidthDps - shortSide).toDouble(), (option.minHeightDps - longSide).toDouble())
                if (chosenGrid == null || distance.compareTo(nearest) < 0) {
                    chosenGrid = grid
                    chosenDisplay = option
                    nearest = distance
                }
            }
        }
        val grid = chosenGrid ?: return null
        val display = chosenDisplay ?: return null

        return MainPreset.Numbers(
            homeCols = grid.cols,
            homeRows = grid.rows,
            dockSize = grid.hotseat,
            drawerCols = grid.allAppsCols ?: grid.cols,
            iconSizeDp = iconDpOrNull(display.iconImageSize),
            labelSp = display.iconTextSize?.takeIf { it in MainPreset.MIN_LABEL_SP..MainPreset.MAX_LABEL_SP },
            drawerIconDp = iconDpOrNull(display.allAppsIconSize),
            dockIconDp = iconDpOrNull(display.hotseatIconSize),
        )
    }

    private fun parseGridOptions(xml: XmlResourceParser): List<GridOption> {
        val found = mutableListOf<GridOption>()
        var open: GridOption? = null
        var token = xml.eventType
        while (token != XmlPullParser.END_DOCUMENT) {
            when {
                token == XmlPullParser.START_TAG && xml.name == TAG_GRID_OPTION -> {
                    val c = xml.attrInt("numColumns")
                    val r = xml.attrInt("numRows")
                    open = null
                    if (c != null && r != null) {
                        open = GridOption(
                            cols = c,
                            rows = r,
                            hotseat = xml.attrInt("numHotseatIcons"),
                            allAppsCols = xml.attrInt("numAllAppsColumns"),
                            deviceCategory = xml.attr("deviceCategory"),
                        )
                        found.add(open)
                    }
                }

                token == XmlPullParser.START_TAG && xml.name == TAG_DISPLAY_OPTION -> {
                    val owner = open
                    val minW = xml.attrFloat("minWidthDps")
                    val minH = xml.attrFloat("minHeightDps")
                    if (owner != null && minW != null && minH != null) {
                        owner.displays += DisplayOption(
                            minWidthDps = minW,
                            minHeightDps = minH,
                            canBeDefault = xml.attrBool("canBeDefault") ?: true,
                            iconImageSize = xml.attrFloat("iconImageSize"),
                            iconTextSize = xml.attrFloat("iconTextSize"),
                            allAppsIconSize = xml.attrFloat("allAppsIconSize"),
                            hotseatIconSize = xml.attrFloat("hotseatIconSize"),
                        )
                    }
                }

                token == XmlPullParser.END_TAG && xml.name == TAG_GRID_OPTION -> open = null
            }
            token = xml.next()
        }
        return found
    }

    private fun XmlResourceParser.attr(name: String): String? =
        getAttributeValue(RES_AUTO_NS, name) ?: getAttributeValue(null, name)

    private fun XmlResourceParser.attrInt(name: String): Int? = attr(name)?.trim()?.toIntOrNull()
    private fun XmlResourceParser.attrFloat(name: String): Float? = attr(name)?.trim()?.toFloatOrNull()
    private fun XmlResourceParser.attrBool(name: String): Boolean? = attr(name)?.trim()?.toBooleanStrictOrNull()

    private fun JSONObject.nameList(key: String): Sequence<String>? =
        optJSONArray(key)?.let { arr ->
            (0 until arr.length()).asSequence().map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        }

    private fun readNumber(apkRes: Resources, packageName: String, name: String): Int? {
        val integerId = apkRes.getIdentifier(name, "integer", packageName)
        if (integerId != 0) return runCatching { apkRes.getInteger(integerId) }.getOrNull()
        val dimenId = apkRes.getIdentifier(name, "dimen", packageName)
        return when (dimenId) {
            0 -> null
            else -> runCatching { (apkRes.getDimension(dimenId) / apkRes.displayMetrics.density).toInt() }.getOrNull()
        }
    }

    private fun readProbes(context: Context, apkRes: Resources, packageName: String, probesJson: String): MainPreset.Numbers? {
        val body = probesJson.trim().takeUnless { it.isEmpty() } ?: return null
        val entry = JSONObject(body).optJSONObject(packageName) ?: return null

        fun firstInRange(key: String, range: IntRange = MainPreset.MIN_CELLS..MainPreset.MAX_CELLS): Int? =
            entry.nameList(key)?.firstNotNullOfOrNull { name ->
                readNumber(apkRes, packageName, name)?.takeIf { it in range }
            }

        val mode = entry.nameList(KEY_DRAWER_MODE)?.let { candidates ->
            val table = entry.optJSONObject(KEY_DRAWER_MODE_VALUES)
            val settingKey = entry.optString(KEY_DRAWER_MODE_SETTING).trim().takeIf { it.isNotEmpty() }
            val switched = settingKey?.let { key ->
                runCatching { Settings.Global.getInt(context.contentResolver, key) }.getOrNull()
            }
            switched?.let { drawerModeOf(it, table) }
                ?: candidates.firstNotNullOfOrNull { name ->
                    readNumber(apkRes, packageName, name)?.let { drawerModeOf(it, table) }
                }
        }

        val textSp = entry.nameList(KEY_LABEL_SP)?.firstNotNullOfOrNull { name ->
            apkRes.getIdentifier(name, "dimen", packageName).takeIf { it != 0 }?.let { dimenId ->
                CommonLatticeScanner.pullTextSizeSp(apkRes, dimenId)
                    ?.takeIf { it in MainPreset.MIN_LABEL_SP..MainPreset.MAX_LABEL_SP }
            }
        }
        return MainPreset.Numbers(
            homeCols = firstInRange(KEY_HOME_COLS),
            homeRows = firstInRange(KEY_HOME_ROWS),
            dockSize = firstInRange(KEY_DOCK_SIZE),
            drawerCols = firstInRange(KEY_DRAWER_COLS),
            iconSizeDp = firstInRange(KEY_ICON_SIZE_DP, 24..120),
            labelSp = textSp,
            drawerMode = mode,
        )
    }
}
