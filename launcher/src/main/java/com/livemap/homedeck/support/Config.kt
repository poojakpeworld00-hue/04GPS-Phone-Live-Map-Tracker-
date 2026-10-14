package com.livemap.homedeck.support

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import com.livemap.homedeck.R
import com.livemap.homedeck.teaser.HomeMentorPhase
import com.livemap.homedeck.preset.SliderWay

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    var wasHomeScreenInit: Boolean
        get() = prefs.getBoolean(WAS_HOME_SCREEN_INIT, false)
        set(wasHomeScreenInit) = prefs.edit().putBoolean(WAS_HOME_SCREEN_INIT, wasHomeScreenInit).apply()

    var dockHostPackage: String
        get() = prefs.getString(DOCK_HOST_PACKAGE, "") ?: ""
        set(dockHostPackage) = prefs.edit().putString(DOCK_HOST_PACKAGE, dockHostPackage).apply()

    var selfIconHidden: Boolean
        get() = prefs.getBoolean(SELF_ICON_HIDDEN, false)
        set(selfIconHidden) = prefs.edit().putBoolean(SELF_ICON_HIDDEN, selfIconHidden).apply()

    var wasOnboardingCompleted: Boolean
        get() = prefs.getBoolean(WAS_ONBOARDING_COMPLETED, false)
        set(wasOnboardingCompleted) = prefs.edit()
            .putBoolean(WAS_ONBOARDING_COMPLETED, wasOnboardingCompleted).apply()

    var wasSearchBarPurged: Boolean
        get() = prefs.getBoolean(WAS_SEARCH_BAR_PURGED, false)
        set(wasSearchBarPurged) = prefs.edit().putBoolean(WAS_SEARCH_BAR_PURGED, wasSearchBarPurged).apply()

    fun isGuideStepDone(step: HomeMentorPhase): Boolean =
        prefs.getBoolean(GUIDE_STEP_DONE_PREFIX + step.name, false)

    fun flagGuideStepDone(step: HomeMentorPhase) =
        prefs.edit().putBoolean(GUIDE_STEP_DONE_PREFIX + step.name, true).apply()

    var updateDeclinedAt: Long
        get() = prefs.getLong(UPDATE_DECLINED_AT, 0L)
        set(updateDeclinedAt) = prefs.edit().putLong(UPDATE_DECLINED_AT, updateDeclinedAt).apply()

    var homeFingerprintJson: String
        get() = prefs.getString(HOME_FINGERPRINT_JSON, "") ?: ""
        set(homeFingerprintJson) = prefs.edit().putString(HOME_FINGERPRINT_JSON, homeFingerprintJson).apply()

    var homeProfileJson: String
        get() = prefs.getString(HOME_PROFILE_JSON, "") ?: ""
        set(homeProfileJson) = prefs.edit().putString(HOME_PROFILE_JSON, homeProfileJson).apply()

    var drawerMode: SliderWay
        get() = SliderWay.entries.firstOrNull { it.name.equals(prefs.getString(DRAWER_MODE, ""), ignoreCase = true) }
            ?: SliderWay.VERTICAL
        set(drawerMode) = prefs.edit().putString(DRAWER_MODE, drawerMode.name.lowercase()).apply()

    val isDrawerEnabled: Boolean get() = drawerMode != SliderWay.NONE

    var drawerStyleWhenOn: SliderWay
        get() = if (prefs.getString(DRAWER_STYLE_WHEN_ON, "").equals(SliderWay.PAGED.name, ignoreCase = true)) SliderWay.PAGED else SliderWay.VERTICAL
        set(style) = prefs.edit().putString(DRAWER_STYLE_WHEN_ON, style.name.lowercase()).apply()

    var drawerRowCount: Int
        get() = prefs.getInt(DRAWER_ROW_COUNT, DRAWER_ROW_COUNT_DEFAULT)
        set(drawerRowCount) = prefs.edit().putInt(DRAWER_ROW_COUNT, drawerRowCount).apply()

    var dockColumnCount: Int
        get() = prefs.getInt(DOCK_COLUMN_COUNT_PREF, DOCK_COLUMN_COUNT)
        set(dockColumnCount) = prefs.edit().putInt(DOCK_COLUMN_COUNT_PREF, dockColumnCount).apply()

    var drawerSearchAtBottom: Boolean
        get() = prefs.getBoolean(DRAWER_SEARCH_AT_BOTTOM, false)
        set(drawerSearchAtBottom) = prefs.edit().putBoolean(DRAWER_SEARCH_AT_BOTTOM, drawerSearchAtBottom).apply()

    var iconScale: Float
        get() = prefs.getFloat(ICON_SCALE, 1f).let { if (it.isNaN()) 1f else it.coerceIn(0.5f, 1.5f) }
        set(iconScale) = prefs.edit().putFloat(ICON_SCALE, iconScale.coerceIn(0.5f, 1.5f)).apply()

    var iconSizeDp: Int
        get() = prefs.getInt(ICON_SIZE_DP, 0).let { if (it in 24..120) it else 0 }
        set(iconSizeDp) = prefs.edit().putInt(ICON_SIZE_DP, iconSizeDp).apply()

    var dockIconSizeDp: Int
        get() = prefs.getInt(DOCK_ICON_SIZE_DP, 0).let { if (it in 24..120) it else 0 }
        set(dockIconSizeDp) = prefs.edit().putInt(DOCK_ICON_SIZE_DP, dockIconSizeDp).apply()

    var drawerIconSizeDp: Int
        get() = prefs.getInt(DRAWER_ICON_SIZE_DP, 0).let { if (it in 24..120) it else 0 }
        set(drawerIconSizeDp) = prefs.edit().putInt(DRAWER_ICON_SIZE_DP, drawerIconSizeDp).apply()

    var legacyIconTray: BadgeMolder.Tray
        get() = BadgeMolder.Tray.parse(prefs.getString(LEGACY_ICON_TRAY, null)) ?: BadgeMolder.Tray.DOMINANT
        set(tray) = prefs.edit().putString(LEGACY_ICON_TRAY, tray.name.lowercase()).apply()

    var labelTextSp: Float
        get() = prefs.getFloat(LABEL_TEXT_SP, 0f).let { if (it.isNaN() || it < 8f || it > 20f) 0f else it }
        set(labelTextSp) = prefs.edit().putFloat(LABEL_TEXT_SP, labelTextSp).apply()

    var homeColumnCount: Int
        get() = prefs.getInt(HOME_COLUMN_COUNT, COLUMN_COUNT)
        set(homeColumnCount) = prefs.edit().putInt(HOME_COLUMN_COUNT, homeColumnCount).apply()

    var homeRowCount: Int
        get() = prefs.getInt(HOME_ROW_COUNT, ROW_COUNT)
        set(homeRowCount) = prefs.edit().putInt(HOME_ROW_COUNT, homeRowCount).apply()

    var drawerColumnCount: Int
        get() = prefs.getInt(DRAWER_COLUMN_COUNT, context.resources.getInteger(R.integer.launcher_portrait_column_count))
        set(drawerColumnCount) = prefs.edit().putInt(DRAWER_COLUMN_COUNT, drawerColumnCount).apply()

    var showSearchBar: Boolean
        get() = prefs.getBoolean(SHOW_SEARCH_BAR, true)
        set(showSearchBar) = prefs.edit().putBoolean(SHOW_SEARCH_BAR, showSearchBar).apply()

    var shutAppDrawer: Boolean
        get() = prefs.getBoolean(CLOSE_APP_DRAWER, false)
        set(shutAppDrawer) = prefs.edit().putBoolean(CLOSE_APP_DRAWER, shutAppDrawer).apply()

    var autoShowKeyboardInAppDrawer: Boolean
        get() = prefs.getBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, false)
        set(autoShowKeyboardInAppDrawer) = prefs.edit()
            .putBoolean(AUTO_SHOW_KEYBOARD_IN_APP_DRAWER, autoShowKeyboardInAppDrawer).apply()

    var showDrawerAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_DRAWER_APP_LABELS, true)
        set(showDrawerAppLabels) = prefs.edit().putBoolean(SHOW_DRAWER_APP_LABELS, showDrawerAppLabels).apply()

    var showHomeAppLabels: Boolean
        get() = prefs.getBoolean(SHOW_HOME_APP_LABELS, true)
        set(showHomeAppLabels) = prefs.edit().putBoolean(SHOW_HOME_APP_LABELS, showHomeAppLabels).apply()

    var homeSeeded: Boolean
        get() = prefs.getBoolean(HOME_SEEDED, false)
        set(homeSeeded) = prefs.edit().putBoolean(HOME_SEEDED, homeSeeded).apply()

    var lastInstallSeen: Long
        get() = prefs.getLong(LAST_INSTALL_SEEN, 0L)
        set(lastInstallSeen) = prefs.edit().putLong(LAST_INSTALL_SEEN, lastInstallSeen).apply()

    var launcherStyle: String
        get() = prefs.getString(LAUNCHER_STYLE, "") ?: ""
        set(launcherStyle) = prefs.edit().putString(LAUNCHER_STYLE, launcherStyle).apply()

    var homeRebuildPending: Boolean
        get() = prefs.getBoolean(HOME_REBUILD_PENDING, false)
        set(homeRebuildPending) = prefs.edit().putBoolean(HOME_REBUILD_PENDING, homeRebuildPending).apply()

    var searchWidget: String
        get() = prefs.getString(SEARCH_WIDGET, "") ?: ""
        set(searchWidget) = prefs.edit().putString(SEARCH_WIDGET, searchWidget).apply()

    var timeWidgetShown: Boolean
        get() = prefs.getBoolean(TIME_WIDGET_SHOWN, true)
        set(timeWidgetShown) = prefs.edit().putBoolean(TIME_WIDGET_SHOWN, timeWidgetShown).apply()

    var searchWidgetShown: Boolean
        get() = prefs.getBoolean(SEARCH_WIDGET_SHOWN, true)
        set(searchWidgetShown) = prefs.edit().putBoolean(SEARCH_WIDGET_SHOWN, searchWidgetShown).apply()

    var timeWidgetAtTop: Boolean
        get() = prefs.getBoolean(TIME_WIDGET_AT_TOP, false)
        set(timeWidgetAtTop) = prefs.edit().putBoolean(TIME_WIDGET_AT_TOP, timeWidgetAtTop).apply()

    var homeAndDrawer: Boolean
        get() = prefs.getBoolean(HOME_AND_DRAWER, false)
        set(homeAndDrawer) = prefs.edit().putBoolean(HOME_AND_DRAWER, homeAndDrawer).apply()
}
