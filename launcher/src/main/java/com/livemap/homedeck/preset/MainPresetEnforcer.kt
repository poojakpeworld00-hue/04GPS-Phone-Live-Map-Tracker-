package com.livemap.homedeck.preset

import android.content.Context
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.support.Config
import com.livemap.homedeck.support.BadgeMolder
import com.livemap.homedeck.support.MAX_COLUMN_COUNT
import com.livemap.homedeck.support.MAX_DOCK_SLOTS
import com.livemap.homedeck.support.MAX_ROW_COUNT
import com.livemap.homedeck.support.MIN_COLUMN_COUNT
import com.livemap.homedeck.support.MIN_ROW_COUNT
import com.livemap.homedeck.contract.HomeBook
import org.json.JSONObject
import timber.log.Timber

object MainPresetEnforcer {
    private const val APPLIED_NONE = "{}"

    fun renewIconSizes(context: Context) {
        val config = context.launcherConfig
        val json = config.homeProfileJson.takeIf { it.isNotEmpty() && it != APPLIED_NONE } ?: return
        runCatching {
            val saved = MainPreset.fromJson(JSONObject(json), source = PresetFeed.STANDARD) ?: return

            val row = HomeThumbprint.stored(context)?.let { fp ->
                val remote = MainPresetLaws.parse(HomeBook.bridge.configString(MainPresetSettler.RC_RULES_KEY, ""), PresetFeed.RC_RULE)
                (remote?.select(fp) ?: MainPresetLaws.builtIn.select(fp))
            }?.takeIf { it.id == saved.id }
            val stored = row?.let {
                saved.copy(
                    iconWidthFraction = it.iconWidthFraction,
                    iconWidthFractionByDpi = it.iconWidthFractionByDpi,
                    dockIconWidthFraction = it.dockIconWidthFraction,
                    drawerIconWidthFraction = it.drawerIconWidthFraction,
                    iconInset = it.iconInset,
                )
            } ?: saved
            if (stored.iconWidthFraction <= 0f && stored.dockIconWidthFraction <= 0f && stored.drawerIconWidthFraction <= 0f) return
            val metrics = context.resources.displayMetrics
            val widthDp = (minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density).toInt()
            val profile = stored.settleFractions(widthDp, metrics.densityDpi)
            if (config.iconSizeDp != profile.homeArtDp) config.iconSizeDp = profile.homeArtDp
            if (config.dockIconSizeDp != profile.dockArtDp) config.dockIconSizeDp = profile.dockArtDp
            if (config.drawerIconSizeDp != profile.drawerArtDp) config.drawerIconSizeDp = profile.drawerArtDp
        }.onFailure { Timber.w(it, "MainPreset: icon refresh failed") }
    }

    const val STYLE_OS = "os"
    const val STYLE_DEFAULT = "default"
    const val DEFAULT_ICON_DP = 62

    val DEFAULT_SETUP: MainPreset = MainPreset.AOSP.copy(
        id = STYLE_DEFAULT,
        homeCols = 4,
        homeRows = 6,
        dockSize = 4,
        drawerCols = 4,
        iconDp = DEFAULT_ICON_DP,
        dockIconDp = DEFAULT_ICON_DP,
        drawerIconDp = DEFAULT_ICON_DP,
        drawerSearchPosition = SliderHuntPlacement.BOTTOM,
    )

    fun styleFor(osStyle: Boolean): String = if (osStyle) STYLE_OS else STYLE_DEFAULT

    fun enactStyle(context: Context): Boolean {
        val config = context.launcherConfig
        val want = styleFor(HomeBook.setup().osStyle)
        val built = config.wasHomeScreenInit || config.homeProfileJson.isNotEmpty()

        val have = config.launcherStyle.ifEmpty { if (built) STYLE_OS else "" }
        if (have == want) {
            if (config.launcherStyle.isEmpty()) config.launcherStyle = have
            return want == STYLE_DEFAULT && renewDefault(config)
        }

        if (want == STYLE_OS) {
            val profile = settleOs(context)
            if (profile == null) {
                config.homeProfileJson = APPLIED_NONE
                config.homeAndDrawer = false
            } else {
                write(config, profile, homeAndDrawer = profile.drawerMode != SliderWay.NONE &&
                    profile.sourceOf(MainPreset.KEY_DRAWER_MODE) == PresetFeed.APK)
            }
        } else {
            write(config, DEFAULT_SETUP, homeAndDrawer = true)
        }
        config.launcherStyle = want
        Timber.i("MainPreset: launcher style $have -> $want")
        if (!built) return false
        config.homeSeeded = false
        config.homeRebuildPending = true
        return true
    }

    private fun renewDefault(config: Config): Boolean {
        val json = DEFAULT_SETUP.toJson().toString()
        if (config.homeProfileJson == json) return false
        val saved = runCatching { MainPreset.fromJson(JSONObject(config.homeProfileJson), source = PresetFeed.STANDARD) }.getOrNull()
        write(config, DEFAULT_SETUP, homeAndDrawer = true)
        val reshaped = saved == null || saved.homeCols != DEFAULT_SETUP.homeCols ||
            saved.homeRows != DEFAULT_SETUP.homeRows || saved.dockSize != DEFAULT_SETUP.dockSize
        if (reshaped) {
            config.homeSeeded = false
            config.homeRebuildPending = true
        }
        return true
    }

    private fun settleOs(context: Context): MainPreset? {
        return runCatching {
            val fp = HomeThumbprint.stored(context)?.takeIf { it.homePackage.isNotEmpty() }
                ?: HomeThumbprint.grabAndStore(context)
            MainPresetSettler.resolve(context, fp)
        }.onFailure { Timber.w(it, "MainPreset: resolve failed, keeping defaults") }.getOrNull()
    }

    private fun write(config: Config, profile: MainPreset, homeAndDrawer: Boolean) {
        config.homeRowCount = (profile.homeRows + 1).coerceIn(MIN_ROW_COUNT, MAX_ROW_COUNT)
        config.homeColumnCount = profile.homeCols.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        config.drawerColumnCount = profile.drawerCols.coerceIn(MIN_COLUMN_COUNT, MAX_COLUMN_COUNT)
        config.showSearchBar = profile.drawerSearch
        config.showHomeAppLabels = profile.homeLabels
        config.showDrawerAppLabels = profile.drawerLabels
        config.drawerMode = profile.drawerMode
        if (profile.drawerMode != SliderWay.NONE) config.drawerStyleWhenOn = profile.drawerMode
        if (profile.drawerRows > 0) config.drawerRowCount = profile.drawerRows.coerceIn(MIN_ROW_COUNT, MAX_ROW_COUNT)

        config.dockColumnCount = profile.dockSize.coerceIn(1, MAX_DOCK_SLOTS)
        config.drawerSearchAtBottom = profile.drawerSearchPosition == SliderHuntPlacement.BOTTOM
        config.iconScale = profile.iconScale

        config.iconSizeDp = profile.homeArtDp
        config.dockIconSizeDp = profile.dockArtDp
        config.drawerIconSizeDp = profile.drawerArtDp
        config.labelTextSp = profile.labelSp
        config.legacyIconTray = BadgeMolder.Tray.parse(profile.legacyIconTray) ?: BadgeMolder.Tray.DOMINANT
        config.homeAndDrawer = homeAndDrawer
        config.homeProfileJson = profile.toJson().toString()
        Timber.i("MainPreset: applied ${profile.id} — home ${profile.homeCols}x${profile.homeRows}+dock, drawer ${profile.drawerCols} cols")
    }
}
