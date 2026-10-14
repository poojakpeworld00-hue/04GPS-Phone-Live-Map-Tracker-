package com.livemap.homedeck.teaser

import com.livemap.homedeck.contract.HomeSpots
import com.livemap.homedeck.contract.HomeTokens
import com.livemap.homedeck.contract.HomeBook
import com.livemap.homedeck.setup.HomeBootstrap
import org.json.JSONObject

object HomeSpotsProfile {
    const val LEFT_SWIPE = "leftSwipe"
    const val RIGHT_SWIPE = "rightSwipe"
    const val DRAWER_OPEN = "drawerOpen"

    const val APP_LAUNCH = "appLaunch"

    const val HOME_LAUNCH = "homeLaunch"
    const val DRAWER_LAUNCH = "drawerLaunch"

    const val HOME_BACK = "homeBack"

    const val HOME_PRESS = "homePress"

    fun isAppLaunch(gesture: String): Boolean =
        gesture == APP_LAUNCH || gesture == HOME_LAUNCH || gesture == DRAWER_LAUNCH

    const val KEY_PANEL_MIDDLE = HomeSpots.SLOT_PANEL_MID
    const val KEY_PANEL_BOTTOM = HomeSpots.SLOT_PANEL_BOTTOM
    const val KEY_APP_DRAWER = HomeSpots.SLOT_DRAWER_TOP

    fun guideStepEnabled(step: HomeMentorPhase): Boolean {
        val setup = HomeBook.setup()

        val panelOn = when (step) {
            HomeMentorPhase.SWIPE_LEFT_APPS -> setup.panelEnabled(HomeBootstrap.PANEL_APPS)
            HomeMentorPhase.SWIPE_RIGHT_HOST -> setup.panelEnabled(HomeBootstrap.PANEL_HOST)
            else -> true
        }
        return panelOn && setup.guideStepEnabled(step.configKey)
    }

    fun panelEnabled(gesture: String): Boolean = when (gesture) {
        LEFT_SWIPE -> HomeBook.setup().panelEnabled(HomeBootstrap.PANEL_APPS)
        RIGHT_SWIPE -> HomeBook.setup().panelEnabled(HomeBootstrap.PANEL_HOST)
        else -> true
    }

    fun defaultLauncherPromptEnabled(): Boolean = HomeBook.setup().defaultLauncherPrompt

    fun drawerAdRowPosition(): Int = entry(KEY_APP_DRAWER).optInt(FIELD_AD_ROW_POSITION, 0).coerceAtLeast(0)

    fun reloadAdOnOpen(key: String): Boolean = entry(key).optBoolean(FIELD_RELOAD_AD_ON_OPEN, true)

    private var parsedRaw: String? = null
    private var parsedRoot: JSONObject? = null

    private fun entry(key: String): JSONObject {
        val raw = HomeBook.bridge.configString(HomeTokens.ADS_CONFIG, "")
        val root = synchronized(this) {
            if (raw != parsedRaw) {
                parsedRaw = raw
                parsedRoot = runCatching { JSONObject(raw) }.getOrNull()
            }
            parsedRoot
        } ?: return JSONObject()
        return screenWiseAd(root, HomeBook.bridge.isOrganicAudience(), key)
    }

    internal fun screenWiseAd(raw: String, organic: Boolean, key: String): JSONObject =
        runCatching { JSONObject(raw) }.getOrNull()?.let { screenWiseAd(it, organic, key) } ?: JSONObject()

    private fun screenWiseAd(root: JSONObject, organic: Boolean, key: String): JSONObject = runCatching {
        val branch = root.optJSONObject(if (organic) "organic" else "marketing") ?: root
        branch.optJSONObject(FIELD_SCREEN_WISE_ADS)?.optJSONObject(key)
    }.getOrNull() ?: JSONObject()

    private const val FIELD_SCREEN_WISE_ADS = "screenWiseAds"
    private const val FIELD_AD_ROW_POSITION = "ad_row_position"
    private const val FIELD_RELOAD_AD_ON_OPEN = "reload_ad_on_open"

    fun interEnabled(gesture: String): Boolean = gestureSetup(gesture).interEnabled

    fun interCounter(gesture: String): Int = gestureSetup(gesture).interCounter

    fun promoUrl(gesture: String): String = gestureSetup(gesture).let { if (it.urlEnabled) it.url else "" }

    private fun gestureSetup(gesture: String): HomeBootstrap.GestureSetup {
        val setup = HomeBook.setup()
        return when (gesture) {
            LEFT_SWIPE -> setup.gesture("left_swipe")
            RIGHT_SWIPE -> setup.gesture("right_swipe")
            DRAWER_OPEN -> setup.gesture("drawer_open")
            APP_LAUNCH -> appLaunchSetup(setup)
            HOME_LAUNCH -> setup.gestures["home_launch"] ?: appLaunchSetup(setup)
            DRAWER_LAUNCH -> setup.gestures["drawer_launch"] ?: appLaunchSetup(setup)
            HOME_BACK -> setup.gesture("home_back")
            HOME_PRESS -> setup.gesture("home_press")
            else -> setup.gesture(gesture)
        }
    }

    private fun appLaunchSetup(setup: HomeBootstrap): HomeBootstrap.GestureSetup =
        setup.gestures["app_launch"] ?: setup.gesture("drawer_open")
}
