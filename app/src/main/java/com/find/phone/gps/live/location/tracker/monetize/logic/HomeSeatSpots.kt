package com.find.phone.gps.live.location.tracker.monetize.logic

import android.app.Activity
import android.content.Context
import android.util.Log
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile.DrawerAdFlow
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile.DrawerAdSpec
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile.DrawerAdType
import com.find.phone.gps.live.location.tracker.monetize.render.StraightHrefOpener
import com.find.phone.gps.live.location.tracker.monetize.render.SliderSpotDriver
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

object HomeSeatSpots {
    private const val TAG = "HomeSeatSpots"

    private val PLACEMENTS = listOf(
        "leftPanel", "rightPanel", "drawer", "onboarding", "recent", "appExit", "unlock",
        "splash", "back", "appOpen", "install", "uninstall", "charge", "discharge",
    )

    private val GLOBAL_KEYS = setOf(
        "link_first_then", "link_first_show_all", "link_open_in", "inter_fallback", "RewardedAds",
        "googleFullNative", "ad_flow", "ad_flow_show_all",
    )

    private val NOT_PLACEMENT_KEYS = setOf("recent_playstore", "recent_playstore_window_sec", "onboarding_home")

    fun isPlacementKey(key: String): Boolean =
        key !in NOT_PLACEMENT_KEYS && (key in GLOBAL_KEYS || PLACEMENTS.any { key.startsWith("${it}_") })

    private var placementsRaw: String? = null
    private var placementsJson: JSONObject? = null

    private fun placementsBlock(vault: SpotsLocker): JSONObject? {
        val raw = vault.getString(PLACEMENTS_KEY).orEmpty()
        synchronized(this) {
            if (raw != placementsRaw) {
                placementsRaw = raw
                placementsJson = raw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            }
            return placementsJson
        }
    }

    private fun own(vault: SpotsLocker, name: String, key: String): String? {
        val nested = placementsBlock(vault)?.optJSONObject(name)
            ?.takeIf { it.has(key) && !it.isNull(key) }?.get(key)?.toString()
        return nested?.takeIf { it.isNotBlank() }
            ?: vault.getString("${name}_$key")?.takeIf { it.isNotBlank() }
    }

    const val PLACEMENTS_KEY = "placements"

    private fun normalize(placement: String?): String? = when (placement) {
        "leftSwipe", "leftPanel" -> "leftPanel"
        "rightSwipe", "rightPanel" -> "rightPanel"
        "appLaunch", "drawerOpen", "drawer" -> "drawer"
        else -> placement
    }

    private fun names(placement: String?): List<String> {
        val name = normalize(placement)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return if (name.startsWith("onboarding_")) listOf(name, "onboarding") else listOf(name)
    }

    private fun resolve(vault: SpotsLocker, placement: String?, key: String): String {
        names(placement).forEach { name -> own(vault, name, key)?.let { return it } }
        return vault.getString(key).orEmpty()
    }

    private fun settleFullNative(vault: SpotsLocker, placement: String?): String =
        resolve(vault, placement, "googleFullNative").ifBlank { vault.getString("googleNative").orEmpty() }

    fun placementEnabled(context: Context, placement: String?): Boolean {
        val vault = SpotsLocker.getInstance(context)
        val value = names(placement).firstNotNullOfOrNull { name -> own(vault, name, "ads_on")?.trim() }
        return value?.lowercase() != "false"
    }

    private fun directLinks(vault: SpotsLocker, placement: String?): List<String> {
        val raw = resolve(vault, placement, "DirectLink").trim()
        if (raw.isEmpty()) return emptyList()
        if (!raw.startsWith("[")) return listOf(raw)
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    private fun followUp(vault: SpotsLocker, placement: String?, step: String): DrawerAdSpec? =
        when (step.trim().lowercase()) {
            "reward", "rewarded" ->
                if (vault.getString("RewardedAds")?.trim()?.lowercase() == "false") null
                else DrawerAdSpec(DrawerAdType.REWARDED, resolve(vault, placement, "googleRewarded"))
            "inter", "interstitial" -> DrawerAdSpec(DrawerAdType.INTER, resolve(vault, placement, "googleInter"))
            "app_open", "appopen" -> DrawerAdSpec(DrawerAdType.APPOPEN, resolve(vault, placement, "googleAppopen"))
            "full_native", "fullscreen_native", "fullnative", "full" ->
                DrawerAdSpec(DrawerAdType.FULLSCREEN_NATIVE, settleFullNative(vault, placement))

            "custom", "custom_ads" ->
                if (vault.getBoolean("IsCustomADS")) DrawerAdSpec(DrawerAdType.CUSTOM, "custom") else null
            "directlink", "direct_link", "browser" -> directLinks(vault, placement).takeIf { it.isNotEmpty() }
                ?.let { DrawerAdSpec(DrawerAdType.DIRECTLINK, it.first(), it, resolve(vault, placement, "link_open_in")) }
            else -> null
        }?.takeIf { it.adUnitId.isNotBlank() }

    private class LinkFirst(val links: List<String>, val openIn: StraightHrefOpener.Mode?, val then: DrawerAdFlow)

    private fun linkFirst(vault: SpotsLocker, placement: String?): LinkFirst? {
        val order = resolve(vault, placement, "link_first_then")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val links = directLinks(vault, placement)
        if (order.isEmpty() || links.isEmpty() || !vault.getBoolean("IsCustomADS")) return null
        val openIn = StraightHrefOpener.modeOf(resolve(vault, placement, "link_open_in"))
        val showAll = resolve(vault, placement, "link_first_show_all").trim().toBoolean()
        val then = DrawerAdFlow(
            enabled = true,
            counter = 0,
            sequence = order.mapNotNull { followUp(vault, placement, it) },
            showAll = showAll,
            startFromFirst = true,
        )
        return LinkFirst(links, openIn, then)
    }

    private fun fallbacks(vault: SpotsLocker, placement: String?): List<String> =
        resolve(vault, placement, "inter_fallback").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

    private fun adFlow(vault: SpotsLocker, placement: String?): DrawerAdFlow? {
        val steps = resolve(vault, placement, "ad_flow").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (steps.isEmpty()) return null
        val sequence = steps.mapNotNull { followUp(vault, placement, it) }
        val showAll = resolve(vault, placement, "ad_flow_show_all").trim().toBoolean()
        return DrawerAdFlow(true, 0, sequence, showAll = showAll, startFromFirst = true, onDemand = true)
    }

    fun hasOwnFlow(context: Context, placement: String): Boolean {
        val vault = SpotsLocker.getInstance(context)
        fun set(key: String) = names(placement).any { own(vault, it, key) != null }
        return set("ad_flow") || (set("link_first_then") && linkFirst(vault, placement) != null)
    }

    private fun interFlow(vault: SpotsLocker, placement: String?): DrawerAdFlow {
        adFlow(vault, placement)?.let { return it }
        val sequence = buildList {
            resolve(vault, placement, "googleInter").takeIf { it.isNotBlank() }
                ?.let { add(DrawerAdSpec(DrawerAdType.INTER, it)) }
            fallbacks(vault, placement).forEach { name -> followUp(vault, placement, name)?.let { add(it) } }
        }
        return DrawerAdFlow(true, 0, sequence, showAll = false, startFromFirst = true, onDemand = true)
    }

    private fun fullNativeFlow(vault: SpotsLocker, placement: String?): DrawerAdFlow {
        adFlow(vault, placement)?.let { return it }
        val sequence = buildList {
            settleFullNative(vault, placement).takeIf { it.isNotBlank() }
                ?.let { add(DrawerAdSpec(DrawerAdType.FULLSCREEN_NATIVE, it)) }
            resolve(vault, placement, "googleInter").takeIf { it.isNotBlank() }
                ?.let { add(DrawerAdSpec(DrawerAdType.INTER, it)) }
        }
        return DrawerAdFlow(true, 0, sequence, showAll = false, startFromFirst = true, onDemand = true)
    }

    fun hasOwnInter(context: Context, placement: String?): Boolean {
        val vault = SpotsLocker.getInstance(context)
        if (linkFirst(vault, placement) != null) return true
        if (adFlow(vault, placement) != null) return true
        val unit = resolve(vault, placement, "googleInter")
        return (unit.isNotBlank() && unit != vault.getString("googleInter").orEmpty()) ||
            fallbacks(vault, placement).any { followUp(vault, placement, it) != null }
    }

    fun showInterstitial(activity: Activity, placement: String?, proceed: () -> Unit) {
        val vault = SpotsLocker.getInstance(activity)
        if (!vault.getBoolean("IsAdsON") || !placementEnabled(activity, placement)) return proceed()

        if (execLinkFirst(activity, vault, placement, proceed)) return

        val flow = interFlow(vault, placement)
        log("$placement: inter chain ${flow.sequence.map { "${it.type.key}(${it.adUnitId})" }}")
        SliderSpotDriver.run(activity, flow, pointerKey(placement), proceed)
    }

    fun showFullNative(activity: Activity, placement: String?, proceed: () -> Unit) {
        val vault = SpotsLocker.getInstance(activity)
        if (!vault.getBoolean("IsAdsON") || !placementEnabled(activity, placement)) return proceed()

        if (execLinkFirst(activity, vault, placement, proceed)) return
        val flow = fullNativeFlow(vault, placement)
        log("$placement: full-native chain ${flow.sequence.map { "${it.type.key}(${it.adUnitId})" }}")
        SliderSpotDriver.run(activity, flow, pointerKey(placement), proceed)
    }

    private fun execLinkFirst(activity: Activity, vault: SpotsLocker, placement: String?, proceed: () -> Unit): Boolean {
        val chain = linkFirst(vault, placement) ?: return false

        SliderSpotDriver.preload(activity, chain.then)
        log("$placement: link-first → ${chain.links.size} link(s), then ${chain.then.sequence.map { it.type.key }}")
        val afterLinks = {
            CoreOpenSpotBook.skipNextAppOpenAd = false
            if (chain.then.sequence.isEmpty()) proceed()
            else SliderSpotDriver.run(activity, chain.then, pointerKey(placement), proceed)
        }

        CoreOpenSpotBook.skipNextAppOpenAd = true
        val opened = chain.links.asReversed().count { StraightHrefOpener.open(activity, it, chain.openIn) }
        if (opened > 0) SliderSpotDriver.onReturnTo(activity, afterLinks) else afterLinks()
        return true
    }

    fun presentStep(activity: Activity, placement: String?, step: String, proceed: () -> Unit) {
        val vault = SpotsLocker.getInstance(activity)
        val spec = followUp(vault, placement, step) ?: return proceed()
        SliderSpotDriver.run(activity, DrawerAdFlow(true, 0, listOf(spec), startFromFirst = true, onDemand = true), pointerKey(placement), proceed)
    }

    fun hasFallback(context: Context, placement: String?): Boolean {
        val vault = SpotsLocker.getInstance(context)
        return fallbacks(vault, placement).any { followUp(vault, placement, it) != null }
    }

    fun execFallback(activity: Activity, placement: String?, proceed: () -> Unit) {
        val vault = SpotsLocker.getInstance(activity)
        val sequence = fallbacks(vault, placement).mapNotNull { followUp(vault, placement, it) }
        if (sequence.isEmpty()) return proceed()
        log("$placement: inter_fallback ${sequence.map { it.type.key }}")
        SliderSpotDriver.run(activity, DrawerAdFlow(true, 0, sequence, startFromFirst = true), pointerKey(placement), proceed)
    }

    fun presentAdFlow(activity: Activity, placement: String, proceed: () -> Unit): Boolean {
        val vault = SpotsLocker.getInstance(activity)
        val flow = adFlow(vault, placement) ?: return false
        log("$placement: ad_flow ${flow.sequence.map { it.type.key }}${if (flow.showAll) " [show_all]" else ""}")
        SliderSpotDriver.run(activity, flow, pointerKey(placement), proceed)
        return true
    }

    fun presentLinkFirst(activity: Activity, placement: String, proceed: () -> Unit): Boolean {
        val vault = SpotsLocker.getInstance(activity)
        if (!vault.getBoolean("IsAdsON") || !placementEnabled(activity, placement)) return false
        return execLinkFirst(activity, vault, placement, proceed)
    }

    private fun pointerKey(placement: String?) = "__launcher_placement_ptr_${normalize(placement)}"

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
