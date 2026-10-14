package com.livemap.homedeck.setup

import org.json.JSONObject

data class HomeBootstrap(

    val osStyle: Boolean = false,

    val searchWidget: String = SEARCH_WIDGET_GOOGLE,

    val showTimeWidget: Boolean = true,

    val showSearchWidget: Boolean = true,
    val defaultLauncherPrompt: Boolean = true,

    val panels: Map<String, Boolean> = emptyMap(),
    val guideEnabled: Boolean = true,

    val guideSteps: Map<String, Boolean> = emptyMap(),

    val gestures: Map<String, GestureSetup> = emptyMap(),

    val unlockAdsJson: String = "",

    val drawerAppAdEnabled: Boolean = false,
    val drawerAppAdEvery: Int = 8,
    val drawerAppAds: List<SponsoredApp> = emptyList(),
) {
    data class SponsoredApp(val logo: String, val landingUrl: String, val label: String)

    fun guideStepEnabled(configKey: String): Boolean = guideEnabled && (guideSteps[configKey] ?: true)

    fun panelEnabled(name: String): Boolean = panels[name] ?: true

    fun gesture(name: String): GestureSetup = gestures[name] ?: GestureSetup()

    data class GestureSetup(
        val interEnabled: Boolean = false,
        val interCounter: Int = 0,
        val urlEnabled: Boolean = false,
        val url: String = "",
    )

    companion object {
        const val KEY_DRAWER_APP_AD_ENABLED = "drawerAppAdEnabled"
        const val KEY_DRAWER_APP_AD_EVERY = "drawerAppAdEvery"
        const val KEY_DRAWER_APP_ADS = "drawerAppAds"
        const val KEY_OS_STYLE = "os_style"
        const val KEY_SEARCH_WIDGET = "search_widget"
        const val SEARCH_WIDGET_GOOGLE = "google"
        const val SEARCH_WIDGET_CHROME = "chrome"
        const val KEY_SHOW_TIME_WIDGET = "should_show_time_widget"
        const val KEY_SHOW_SEARCH_WIDGET = "should_show_search_widget"
        const val KEY_DEFAULT_PROMPT = "default_launcher_prompt"
        const val KEY_PANELS = "panels"
        const val PANEL_APPS = "apps"

        const val PANEL_HOST = "host"

        const val PANEL_HOST_LEGACY = "messages"
        const val KEY_GUIDE = "guide"
        const val KEY_GESTURES = "gestures"
        const val KEY_UNLOCK_ADS = "unlock_ads"
        const val KEY_ENABLED = "enabled"

        val DEFAULT = HomeBootstrap()

        fun parse(raw: String, organic: Boolean): HomeBootstrap {
            if (raw.isBlank()) return DEFAULT
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return DEFAULT
            val audience = root.optJSONObject(if (organic) "organic" else "marketing") ?: root

            val panelsObj = audience.optJSONObject(KEY_PANELS)
            val panels = mutableMapOf<String, Boolean>()
            panelsObj?.keys()?.forEach { k -> panels[k] = panelsObj.optBoolean(k, true) }

            panels.remove(PANEL_HOST_LEGACY)?.let { panels.putIfAbsent(PANEL_HOST, it) }

            val guide = audience.optJSONObject(KEY_GUIDE)
            val guideSteps = mutableMapOf<String, Boolean>()
            guide?.keys()?.forEach { k -> if (k != KEY_ENABLED) guideSteps[k] = guide.optBoolean(k, true) }

            val gestures = audience.optJSONObject(KEY_GESTURES)
            val gestureMap = mutableMapOf<String, GestureSetup>()
            gestures?.keys()?.forEach { name ->
                val g = gestures.optJSONObject(name) ?: return@forEach
                gestureMap[name] = GestureSetup(
                    interEnabled = g.optBoolean("inter_enabled", false),
                    interCounter = g.optInt("inter_counter", 0).coerceAtLeast(0),
                    urlEnabled = g.optBoolean("url_enabled", false),
                    url = g.optString("url", "").trim(),
                )
            }

            return HomeBootstrap(
                osStyle = audience.optBoolean(KEY_OS_STYLE, false),
                searchWidget = audience.optString(KEY_SEARCH_WIDGET).trim().lowercase()
                    .takeIf { it == SEARCH_WIDGET_CHROME } ?: SEARCH_WIDGET_GOOGLE,
                showTimeWidget = audience.optBoolean(KEY_SHOW_TIME_WIDGET, true),
                showSearchWidget = audience.optBoolean(KEY_SHOW_SEARCH_WIDGET, true),
                defaultLauncherPrompt = audience.optBoolean(KEY_DEFAULT_PROMPT, true),
                panels = panels,
                guideEnabled = guide?.optBoolean(KEY_ENABLED, true) ?: true,
                guideSteps = guideSteps,
                gestures = gestureMap,
                unlockAdsJson = audience.optJSONObject(KEY_UNLOCK_ADS)?.toString().orEmpty(),

                drawerAppAdEnabled = audience.optBoolean(KEY_DRAWER_APP_AD_ENABLED, false),
                drawerAppAdEvery = audience.optInt(KEY_DRAWER_APP_AD_EVERY, 8).coerceAtLeast(1),
                drawerAppAds = sponsoredApps(audience.optJSONArray(KEY_DRAWER_APP_ADS)),
            )
        }

        private fun sponsoredApps(array: org.json.JSONArray?): List<SponsoredApp> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("landing_url").trim()
                if (url.isEmpty()) null
                else SponsoredApp(o.optString("logo").trim(), url, o.optString("label").trim())
            }
        }
    }
}
