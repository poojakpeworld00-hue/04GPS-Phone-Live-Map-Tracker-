package com.find.phone.gps.live.location.tracker.monetize.logic

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.find.phone.gps.live.location.tracker.monetize.render.RibbonSpotSentinel
import com.find.phone.gps.live.location.tracker.monetize.render.RibbonSort
import com.find.phone.gps.live.location.tracker.monetize.render.RibbonTeaser
import com.find.phone.gps.live.location.tracker.monetize.render.RibbonSpan
import com.find.phone.gps.live.location.tracker.monetize.render.SliderSpotDriver
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaserRibbon
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.livemap.homedeck.ext.isDefaultLauncher
import org.json.JSONObject

object HomeSpotsProfile {
    private const val TAG = "HomeSpotsProfile"
    private const val CONFIG_KEY = "launcher_ads"

    private const val VARIANT_DEFAULT_HOME = "defaultHome"
    private const val VARIANT_NOT_DEFAULT_HOME = "notDefaultHome"

    enum class HomePlane(val label: String, val counterKey: String, val pointerKey: String) {
        APP_CLICK("app_click", "__launcher_app_click_count", "__launcher_app_click_ptr"),
        APP_CLOSE("app_close", "__launcher_app_close_count", "__launcher_app_close_ptr"),
        LEFT_PANEL("left_panel.open_ad", "__launcher_left_panel_count", "__launcher_left_panel_ptr"),
        RIGHT_PANEL("right_panel.open_ad", "__launcher_right_panel_count", "__launcher_right_panel_ptr"),

        HOME_CLICK("home_click", "__launcher_home_click_count", "__launcher_home_click_ptr"),

        DRAWER_CLICK("drawer_click", "__launcher_drawer_click_count", "__launcher_drawer_click_ptr"),

        HOME_BACK("home_back", "__launcher_home_back_count", "__launcher_home_back_ptr"),

        RECENT_CLICK("recent_click", "__launcher_recent_click_count", "__launcher_recent_click_ptr"),

        HOME_PRESS("home_press", "__launcher_home_press_count", "__launcher_home_press_ptr"),
    }

    private fun surfaceBlock(context: Context, surface: HomePlane): JSONObject? {
        val root = config(context)
        return when (surface) {
            HomePlane.APP_CLICK -> root.optJSONObject("app_click")
            HomePlane.APP_CLOSE -> root.optJSONObject("app_close")
            HomePlane.LEFT_PANEL -> root.optJSONObject("left_panel")?.optJSONObject("open_ad")
            HomePlane.RIGHT_PANEL -> root.optJSONObject("right_panel")?.optJSONObject("open_ad")
            HomePlane.HOME_CLICK -> root.optJSONObject("home_click")
            HomePlane.DRAWER_CLICK -> root.optJSONObject("drawer_click")
            HomePlane.HOME_BACK -> root.optJSONObject("home_back")
            HomePlane.RECENT_CLICK -> root.optJSONObject("recent_click")
            HomePlane.HOME_PRESS -> root.optJSONObject("home_press")
        }
    }

    fun clickSurface(context: Context, source: HomePlane): HomePlane =
        if (surfaceBlock(context, source) != null) source else HomePlane.APP_CLICK

    fun recentReturnWindowMs(context: Context): Long =
        (surfaceBlock(context, HomePlane.RECENT_CLICK)?.optLong("return_window_sec", 60L) ?: 60L)
            .coerceAtLeast(0L) * 1000L

    fun surfaceFlow(context: Context, surface: HomePlane): DrawerAdFlow {
        val block = surfaceBlock(context, surface) ?: return DrawerAdFlow(false, 0, emptyList())

        val normalised = JSONObject(block.toString())
        if (!normalised.has("ads")) {
            val own = block.optJSONObject("directlink")
            val urls = block.optJSONArray("directlink_urls")
            val link = when {
                own != null -> JSONObject(own.toString()).apply {
                    if (urls != null && !has("urls") && !has("links") && !has("landing_urls")) put("urls", urls)
                }
                urls != null -> JSONObject().put("urls", urls)
                else -> null
            }
            if (link != null) normalised.put("ads", JSONObject().put("directlink", link))
        }
        return decodeFlow(context, normalised, normalised, surface.label)
    }

    fun surfaceOn(context: Context, surface: HomePlane): Boolean =
        surfaceFlow(context, surface).let { it.enabled && it.sequence.isNotEmpty() }

    fun execSurface(activity: Activity, surface: HomePlane, proceed: () -> Unit) {
        if (!SpotsLocker.getInstance(activity).getBoolean("IsAdsON")) return proceed()
        val flow = surfaceFlow(activity, surface)
        if (!flow.enabled || flow.sequence.isEmpty()) {
            log("${surface.label}: off")
            return proceed()
        }
        if (!isDue(activity, surface.counterKey, flow.counter, surface.label)) return proceed()
        if (surface in CLICK_SURFACES) {
            SliderSpotDriver.runFor(
                activity, flow, surface.pointerKey,
                com.livemap.homedeck.contract.HomeBook.launchingPackage, proceed,
            )
        } else {
            SliderSpotDriver.run(activity, flow, surface.pointerKey, proceed)
        }
    }

    private val CLICK_SURFACES = setOf(
        HomePlane.APP_CLICK, HomePlane.HOME_CLICK, HomePlane.DRAWER_CLICK,
    )

    fun prefetchLauncherAds(context: Context) {
        if (!SpotsLocker.getInstance(context).getBoolean("IsAdsON")) return
        HomePlane.entries.map { surfaceFlow(context, it) }
            .filter { it.enabled && it.sequence.isNotEmpty() }
            .forEach { SliderSpotDriver.preload(context, it) }
    }

    fun panelEnabled(context: Context, left: Boolean): Boolean =
        config(context).optJSONObject(if (left) "left_panel" else "right_panel")?.optBoolean("enabled", true) ?: true

    enum class SlotAd { NATIVE, BANNER, NONE }

    data class Slot(
        val enabled: Boolean,
        val adType: SlotAd,
        val nativeType: String,
        val bannerType: String,
        val adUnitId: String,

        val position: Int = 0,
    ) {
        val visible: Boolean get() = enabled && adType != SlotAd.NONE

        val needsNativePreload: Boolean get() = visible && adType == SlotAd.NATIVE
    }

    fun leftPanelBottomSlot(context: Context): Slot = optionalSlot(
        config(context).optJSONObject("left_panel")?.optJSONObject("native_bottom"), "mid2", "left_panel.native_bottom",
    )

    fun leftPanelMidSlot(context: Context): Slot = optionalSlot(
        config(context).optJSONObject("left_panel")?.optJSONObject("native_mid"), "native_banner", "left_panel.native_mid",
    )

    fun rightPanelNativeSlot(context: Context): Slot = optionalSlot(
        config(context).optJSONObject("right_panel")?.optJSONObject("native"), "mid2", "right_panel.native",
    )

    private fun optionalSlot(block: JSONObject?, defaultNativeType: String, label: String): Slot =
        if (block == null) Slot(false, SlotAd.NONE, defaultNativeType, "adaptive", "")
        else slot(block, defaultNativeType = defaultNativeType, label = label)

    fun appDrawerSlot(context: Context): Slot {
        val block = config(context).optJSONObject("app_drawer")?.optJSONObject("bottom_native")
            ?: return Slot(false, SlotAd.NONE, "mid2", "adaptive", "")

        return slot(block, defaultNativeType = "mid2", label = "app_drawer.bottom_native")
    }

    data class PackageResultSettings(
        val enabled: Boolean,
        val minGapMs: Long,
        val bodyNative: Boolean,
    )

    fun packageResultSettings(context: Context): PackageResultSettings {
        val block = config(context).optJSONObject("package_result")
        return PackageResultSettings(
            enabled = block?.optBoolean("enabled", true) ?: true,
            minGapMs = block?.optLong("min_gap_ms", 0L) ?: 0L,
            bodyNative = block?.optBoolean("body_native", true) ?: true,
        ).also { log("package_result → $it") }
    }

    data class RecentAdSettings(
        val enabled: Boolean,
        val nativeType: String,
        val closeAd: String,
        val minGapMs: Long,
    ) {
        val showsBodyNative: Boolean
            get() = nativeType.isNotBlank() && !nativeType.equals("off", ignoreCase = true)

        val closeShowsInterstitial: Boolean get() = closeAd.equals("inter", ignoreCase = true)

        val closeShowsLink: Boolean
            get() = closeAd.equals("directlink", ignoreCase = true) ||
                closeAd.equals("direct_link", ignoreCase = true) ||
                closeAd.equals("link", ignoreCase = true)
    }

    fun recentAdSettings(context: Context): RecentAdSettings =
        eventAdSettings(config(context).optJSONObject("recent_ad"), "recent_ad")

    fun systemAdSettings(context: Context, trigger: String): RecentAdSettings =
        eventAdSettings(config(context).optJSONObject("system_ads")?.optJSONObject(trigger), "system_ads.$trigger")

    private fun eventAdSettings(block: JSONObject?, label: String): RecentAdSettings {
        val off = RecentAdSettings(false, "off", "none", 0L)
        if (block == null) return off
        return runCatching {
            RecentAdSettings(
                enabled = block.optBoolean("enabled", false),
                nativeType = block.optString("native", "off").ifBlank { "off" },
                closeAd = block.optString("close_ad", "none").ifBlank { "none" },
                minGapMs = block.optLong("min_gap_sec", 0L).coerceAtLeast(0L) * 1000L,
            )
        }.getOrDefault(off).also { log("$label → $it") }
    }

    fun directLink(context: Context): String =
        SpotsLocker.getInstance(context).getString("DirectLink").orEmpty().trim()

    data class DrawerPromo(
        val position: Int,
        val title: String,
        val icon: String,
        val link: String,
    )

    fun drawerPromoItems(context: Context): List<DrawerPromo> {
        val block = config(context).optJSONObject("app_drawer")?.optJSONObject("promo")
            ?: return emptyList()
        if (!block.optBoolean("enabled", true)) return emptyList()
        val items = block.optJSONArray("items") ?: return emptyList()

        val out = ArrayList<DrawerPromo>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (!item.optBoolean("enabled", true)) continue

            val icon = item.optString("icon").ifBlank { item.optString("logo") }.trim()
            val link = item.optString("link").ifBlank { item.optString("landing_url") }.trim()
            val title = item.optString("title").ifBlank { item.optString("label") }.trim()
            if (icon.isBlank() || link.isBlank()) continue
            out += DrawerPromo(
                position = item.optInt("position", 0).coerceAtLeast(0),
                title = title,
                icon = icon,
                link = link,
            )
        }
        log("app_drawer.promo → ${out.size} item(s)")
        return out
    }

    enum class DrawerAdType(val key: String, val idFallbackKey: String) {
        INTER("inter", "googleInter"),
        APPOPEN("appopen", "googleAppopen"),
        DIRECTLINK("directlink", "DirectLink"),
        REWARDED("rewarded", "googleRewarded"),
        FULLSCREEN_NATIVE("fullscreen_native", "googleNative"),

        CUSTOM("custom", "");

        companion object {
            fun from(raw: String?): DrawerAdType? {
                val key = when (val k = raw?.trim()?.lowercase()) {
                    "app_open", "appopen" -> "appopen"
                    "interstitial" -> "inter"
                    "reward" -> "rewarded"
                    "native", "full_native", "fullscreen_native" -> "fullscreen_native"
                    "link" -> "directlink"
                    else -> k
                }
                return entries.firstOrNull { it.key == key }
            }
        }
    }

    data class DrawerAdSpec(
        val type: DrawerAdType,
        val adUnitId: String,
        val urls: List<String> = emptyList(),
        val openType: String = "",
    )

    data class DrawerAdFlow(
        val enabled: Boolean,
        val counter: Int,
        val sequence: List<DrawerAdSpec>,
        val showAll: Boolean = false,
        val startFromFirst: Boolean = false,

        val onDemand: Boolean = false,

        val loaderTimeoutMs: Long = DEFAULT_LOADER_TIMEOUT_MS,
    )

    const val DEFAULT_LOADER_TIMEOUT_MS = 8_000L

    private const val MIN_LOADER_TIMEOUT_MS = 1_000L
    private const val MAX_LOADER_TIMEOUT_MS = 20_000L

    private fun loaderTimeoutMs(block: JSONObject): Long =
        block.optLong("loader_timeout_ms", block.optLong("on_demand_timeout_ms", DEFAULT_LOADER_TIMEOUT_MS))
            .coerceIn(MIN_LOADER_TIMEOUT_MS, MAX_LOADER_TIMEOUT_MS)

    private val DEFAULT_DRAWER_SEQUENCE = listOf(
        DrawerAdType.INTER,
        DrawerAdType.APPOPEN,
        DrawerAdType.DIRECTLINK,
        DrawerAdType.REWARDED,
        DrawerAdType.FULLSCREEN_NATIVE,
    )

    private fun decodeFlow(
        context: Context,
        block: JSONObject,
        idsFrom: JSONObject,
        label: String,
    ): DrawerAdFlow {
        val enabled = block.optBoolean("enabled", block.optBoolean("fallback_enabled", false))
        val counter = block.optInt("ad_counter", block.optInt("counter", 0)).coerceAtLeast(0)
        val showAll = block.optBoolean("show_all_ads", block.optBoolean("show_all", false))
        val startFromFirst =
            block.optBoolean("always_start_first", block.optBoolean("restart_sequence", false))
        val ads = block.optJSONObject("ads") ?: idsFrom.optJSONObject("ads")

        val listed = block.optJSONArray("sequence")
        val order = if (listed == null) DEFAULT_DRAWER_SEQUENCE else
            (0 until listed.length()).mapNotNull { DrawerAdType.from(listed.optString(it)) }
                .ifEmpty { DEFAULT_DRAWER_SEQUENCE }

        val seen = LinkedHashSet<DrawerAdType>()
        val specs = ArrayList<DrawerAdSpec>(order.size)
        order.forEach { type ->
            if (!seen.add(type)) return@forEach
            val ad = ads?.optJSONObject(type.key)
                ?: block.optJSONObject(type.key)
                ?: idsFrom.optJSONObject(type.key)
            if (ad != null && !ad.optBoolean("enabled", true)) return@forEach
            val id = settleDrawerAdId(context, type, ad)
            val urls = if (type == DrawerAdType.DIRECTLINK) directLinkUrls(ad) else emptyList()
            if (id.isBlank() && urls.isEmpty()) {
                log("$label: ${type.key} dropped — no ad_unit_id (nor '${type.idFallbackKey}')")
                return@forEach
            }
            val openType = if (type == DrawerAdType.DIRECTLINK) {
                ad?.optString("open_type", "").orEmpty().trim()
            } else {
                ""
            }
            specs += DrawerAdSpec(type, id, urls, openType)
        }

        val onDemand = block.optBoolean("on_demand", false)
        val loaderTimeout = loaderTimeoutMs(block)

        return DrawerAdFlow(enabled, counter, specs, showAll, startFromFirst, onDemand, loaderTimeout).also {
            log(
                "$label ad flow → enabled=$enabled counter=$counter " +
                    "mode=${if (showAll) "show_all" else if (startFromFirst) "first_ready" else "rotate"} " +
                    "on_demand=$onDemand loader_timeout=${loaderTimeout}ms " +
                    "seq=${specs.map { s -> s.type.key }}"
            )
        }
    }

    private fun directLinkUrls(ad: JSONObject?): List<String> {
        val array = ad?.optJSONArray("urls")
            ?: ad?.optJSONArray("links")
            ?: ad?.optJSONArray("landing_urls")
            ?: return emptyList()

        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i)
            val url = if (item != null) {
                if (!item.optBoolean("enabled", true)) continue
                item.optString("url").ifBlank { item.optString("link") }
            } else {
                array.optString(i)
            }.trim()
            if (url.isNotEmpty()) out += url
        }
        return out
    }

    private fun settleDrawerAdId(context: Context, type: DrawerAdType, ad: JSONObject?): String {
        val keys = if (type == DrawerAdType.DIRECTLINK) {
            listOf("ad_unit_id", "url", "link", "landing_url")
        } else {
            listOf("ad_unit_id")
        }
        keys.forEach { key ->
            val configured = ad?.optString(key, "").orEmpty().trim()
            if (configured.isNotBlank()) return configured
        }
        return SpotsLocker.getInstance(context).getString(type.idFallbackKey).orEmpty().trim()
    }

    fun presentSlot(
        activity: Activity,
        slot: Slot,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (!slot.visible) {
            log("slot hidden (enabled=${slot.enabled}, ad_type=${slot.adType})")
            container.removeAllViews()
            container.visibility = View.GONE
            shimmer?.stopShimmer()
            shimmer?.visibility = View.GONE
            return
        }

        if (slot.adType == SlotAd.BANNER) {
            val (size, collapsible) = when (slot.bannerType.lowercase()) {
                "inline" -> RibbonSpan.INLINE to false
                "normal" -> RibbonSpan.NORMAL to false
                "collapsible" -> RibbonSpan.ADAPTIVE to true
                else -> RibbonSpan.ADAPTIVE to false
            }
            log("slot: banner type=${slot.bannerType} collapsible=$collapsible")

            RibbonTeaser().presentBanner(
                activity = activity,
                container = container,
                type = RibbonSort.AUTO,
                size = size,
                isCollapsable = collapsible,
                shimmer = shimmer,
                customAdUnitId = slot.adUnitId.takeIf { it.isNotBlank() },
                disableInternalFallback = true,
                observer = object : RibbonSpotSentinel {
                    override fun onAdFailed() {
                        log("slot: banner failed, falling back to native banner")
                        EmbeddedTeaserRibbon().presentNativeBannerNative(activity, container, shimmer)
                    }
                }
            )
            return
        }

        log("slot: native type=${slot.nativeType}")
        val render = {
            when (slot.nativeType.lowercase()) {
                "big" -> EmbeddedTeaser().presentBigNative(activity, container, shimmer)
                "mid" -> EmbeddedTeaser().presentMidNative(activity, container, shimmer)
                "native_banner" -> EmbeddedTeaserRibbon().presentNativeBannerNative(activity, container, shimmer)
                else -> EmbeddedTeaser().presentMidNative2(activity, container, shimmer)
            }
        }

        if (!slot.nativeType.equals("native_banner", ignoreCase = true) && !EmbeddedTeaser.hasPreloadedNative()) {
            log("slot: native pool empty — waiting for the load")
            container.visibility = View.VISIBLE
            shimmer?.visibility = View.VISIBLE
            shimmer?.startShimmer()
            val renderIfAlive = { if (!activity.isFinishing && !activity.isDestroyed) render() }
            EmbeddedTeaser().fetchNativeADs(activity, object : EmbeddedTeaser.NativeAdObserver {
                override fun onNativeAdLoaded() = renderIfAlive()
                override fun onNativeAdFailed() = renderIfAlive()
            })
            return
        }
        render()
    }

    fun renewSlot(
        activity: Activity,
        slot: Slot,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
    ) {
        val holdsAnAd = (0 until container.childCount).any { container.getChildAt(it) !== shimmer }
        if (!slot.needsNativePreload || !holdsAnAd || EmbeddedTeaser.hasPreloadedNative()) {
            presentSlot(activity, slot, container, shimmer)
            return
        }

        log("slot: no fresh native — keeping the one on screen, warming the next")
        EmbeddedTeaser().fetchNativeADs(activity)
    }

    private fun slot(block: JSONObject?, defaultNativeType: String, label: String): Slot {
        if (block == null) {
            return Slot(true, SlotAd.NATIVE, defaultNativeType, "adaptive", "")
        }

        val adType = when (block.optString("ad_type").trim().lowercase()) {
            "banner" -> SlotAd.BANNER
            "none" -> SlotAd.NONE
            else -> SlotAd.NATIVE
        }

        return Slot(
            enabled = block.optBoolean("enabled", true),
            adType = adType,
            nativeType = block.optString("native_type").ifBlank { defaultNativeType },
            bannerType = block.optString("banner_type").ifBlank { "adaptive" },
            adUnitId = block.optString("ad_unit_id", ""),

            position = block.optInt("position", 0).coerceAtLeast(0),
        ).also { log("$label → $it") }
    }

    enum class HintDirection(val key: String) {
        RIGHT("right"), LEFT("left"), UP("up"), DOWN("down");

        companion object {
            fun from(raw: String?): HintDirection? =
                entries.firstOrNull { it.key == raw?.trim()?.lowercase() }
        }
    }

    enum class HintMode { ONCE, ALWAYS, APP_LAUNCHES }

    data class HomeHint(
        val enabled: Boolean,
        val directions: List<HintDirection>,
        val mode: HintMode,
        val interval: Int,
        val autoHideSec: Int,
    ) {
        val visible: Boolean get() = enabled && directions.isNotEmpty()
    }

    private val DEFAULT_HINT = HomeHint(
        enabled = true,
        directions = listOf(HintDirection.RIGHT),
        mode = HintMode.ONCE,
        interval = 0,
        autoHideSec = 0,
    )

    fun homeHint(context: Context): HomeHint {
        val block = config(context).optJSONObject("home_hint") ?: return DEFAULT_HINT

        val listed = block.optJSONArray("swipeHints")
        val directions = when {
            listed == null -> DEFAULT_HINT.directions

            else -> (0 until listed.length()).mapNotNull { HintDirection.from(listed.optString(it)) }
        }

        return HomeHint(
            enabled = block.optBoolean("enabled", true),
            directions = directions,
            mode = when (block.optString("show_mode").trim().lowercase()) {
                "always" -> HintMode.ALWAYS
                "app_launches" -> HintMode.APP_LAUNCHES
                else -> HintMode.ONCE
            },
            interval = block.optInt("interval", 0),
            autoHideSec = block.optInt("auto_hide_sec", 0),
        ).also { log("home_hint → $it") }
    }

    fun isHintDue(context: Context, hint: HomeHint): Boolean =
        isDue(context, HINT_COUNTER_KEY, hint.interval, "home_hint")

    private const val HINT_COUNTER_KEY = "__launcher_ads_home_hint_count"

    enum class OnboardScreen(
        val key: String,
        private val interByDefault: Boolean,
        private val nativeByDefault: String,
    ) {
        WELCOME("welcome", false, "mid"),
        SET_DEFAULT("set_default", false, "big"),
        INTRO("intro", true, "mid2"),
        LANGUAGE("language", true, "big"),

        REMINDERS("reminders", false, "mid");

        val counterKey: String get() = "__launcher_ads_onboarding_${key}_count"

        internal fun defaults(): Pair<Boolean, String> = interByDefault to nativeByDefault

        companion object {
            fun from(raw: String?): OnboardScreen? =
                entries.firstOrNull { it.key == raw?.trim()?.lowercase() }
        }
    }

    fun onboardingSlot(context: Context, screen: OnboardScreen): Slot = slot(
        block = onboardingBlock(context, screen)?.optJSONObject("slot"),
        defaultNativeType = screen.defaults().second,
        label = "onboarding.${screen.key}.slot",
    )

    data class ScreenUi(val skipEnabled: Boolean, val backAdvances: Boolean)

    fun onboardingUi(context: Context, screen: OnboardScreen): ScreenUi {
        val block = onboardingBlock(context, screen)
        return ScreenUi(
            skipEnabled = block?.optBoolean("skip_enabled", true) ?: true,
            backAdvances = block?.optString("back_action")
                ?.trim()?.lowercase() == "next_screen",
        ).also { log("onboarding.${screen.key}.ui → $it") }
    }

    fun execOnboardingInter(activity: Activity, screen: OnboardScreen, proceed: () -> Unit) {
        val block = onboardingBlock(activity, screen)
        val enabled = block?.optBoolean("inter_enabled", screen.defaults().first)
            ?: screen.defaults().first

        if (!enabled) {
            log("onboarding.${screen.key}: interstitial off")
            return proceed()
        }

        val counter = block?.optInt("ads_counter", 0) ?: 0
        if (!isDue(activity, screen.counterKey, counter, "onboarding.${screen.key}")) {
            return proceed()
        }

        if (!HomeSeatSpots.placementEnabled(activity, "onboarding_${screen.key}")) {
            log("onboarding.${screen.key}: ads_on=false")
            return proceed()
        }

        if (HomeSeatSpots.presentLinkFirst(activity, "onboarding_${screen.key}", proceed)) {
            log("onboarding.${screen.key}: link-first")
            return
        }
        log("onboarding.${screen.key}: showing interstitial")
        TakeoverStandard().presentInterAds(activity) { proceed() }
    }

    fun onboardingOrder(context: Context): List<OnboardScreen> {
        val listed = config(context).optJSONObject("onboarding")?.optJSONArray("order")
            ?: return DEFAULT_ORDER

        val order = (0 until listed.length()).mapNotNull { OnboardScreen.from(listed.optString(it)) }
        return withReminders(order.ifEmpty { DEFAULT_ORDER })
            .also { log("onboarding.order → ${it.map(OnboardScreen::key)}") }
    }

    private fun withReminders(order: List<OnboardScreen>): List<OnboardScreen> {
        if (OnboardScreen.REMINDERS in order) return order
        val at = order.indexOf(OnboardScreen.WELCOME).let { if (it < 0) order.size else it + 1 }
        return order.toMutableList().apply { add(at, OnboardScreen.REMINDERS) }
    }

    private val DEFAULT_ORDER = listOf(
        OnboardScreen.SET_DEFAULT,
        OnboardScreen.LANGUAGE,
        OnboardScreen.WELCOME,
        OnboardScreen.REMINDERS,
        OnboardScreen.INTRO,
    )

    fun onboardingAutoNext(context: Context, screen: OnboardScreen): Pair<Boolean, Long> {
        val block = onboardingBlock(context, screen)
        val auto = block?.optBoolean("auto_next", true) ?: true
        val delay = (block?.optLong("auto_next_delay_ms", 650L) ?: 650L).coerceIn(0L, 5_000L)
        return auto to delay
    }

    private fun onboardingBlock(context: Context, screen: OnboardScreen): JSONObject? =
        config(context).optJSONObject("onboarding")?.optJSONObject(screen.key)

    data class DefaultHomeStep(
        val enabled: Boolean,
        val skipIfDefault: Boolean,
        val skipRestOnGrant: Boolean,
    )

    fun defaultHomeStep(context: Context): DefaultHomeStep {
        val block = config(context).optJSONObject("default_home_screen")
        return DefaultHomeStep(
            enabled = block?.optBoolean("enabled", true) ?: true,
            skipIfDefault = block?.optBoolean("skip_if_default", true) ?: true,
            skipRestOnGrant = block?.optBoolean("skip_rest_on_grant", true) ?: true,
        )
    }

    @Volatile
    private var cache: Pair<String, JSONObject>? = null

    private fun config(context: Context): JSONObject {
        val raw = SpotsLocker.getInstance(context).getString(CONFIG_KEY, "").orEmpty()
        if (raw.isBlank()) return JSONObject()

        val isDefaultHome = runCatching { context.isDefaultLauncher() }.getOrDefault(false)
        val cacheKey = "${isDefaultHome}|$raw"
        cache?.let { (key, value) -> if (key == cacheKey) return value }

        val base = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONObject()
        val variantKey = if (isDefaultHome) VARIANT_DEFAULT_HOME else VARIANT_NOT_DEFAULT_HOME
        val variant = base.optJSONObject(variantKey)

        val merged = runCatching { JSONObject(base.toString()) }.getOrNull() ?: JSONObject()
        merged.remove(VARIANT_DEFAULT_HOME)
        merged.remove(VARIANT_NOT_DEFAULT_HOME)
        if (variant != null) deepMerge(merged, variant)

        log("config resolved with $variantKey (${variant?.length() ?: 0} override(s))")
        cache = cacheKey to merged
        return merged
    }

    private fun deepMerge(base: JSONObject, overlay: JSONObject) {
        overlay.keys().forEach { key ->
            val overlayValue = overlay.opt(key)
            val baseValue = base.opt(key)
            if (overlayValue is JSONObject && baseValue is JSONObject) {
                deepMerge(baseValue, overlayValue)
            } else {
                base.put(key, overlayValue)
            }
        }
    }

    private fun isDue(context: Context, counterKey: String, target: Int, label: String): Boolean {
        if (target <= 0) return true

        val pref = SpotsLocker.getInstance(context)
        val seen = pref.getInt(counterKey, 0)

        return if (seen < target) {
            pref.putInt(counterKey, seen + 1)
            log("$label: counter ${seen + 1}/$target, skipping")
            false
        } else {
            pref.putInt(counterKey, 0)
            true
        }
    }

    private fun log(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }
}
