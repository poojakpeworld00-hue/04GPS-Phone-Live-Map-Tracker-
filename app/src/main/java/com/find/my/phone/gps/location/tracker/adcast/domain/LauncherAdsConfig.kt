package com.find.my.phone.gps.location.tracker.adcast.domain

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.shimmer.ShimmerFrameLayout
import com.find.my.phone.gps.location.tracker.adcast.presentation.BannerAdWatcher
import com.find.my.phone.gps.location.tracker.adcast.presentation.BannerKind
import com.find.my.phone.gps.location.tracker.adcast.presentation.BannerPromo
import com.find.my.phone.gps.location.tracker.adcast.presentation.BannerScale
import com.find.my.phone.gps.location.tracker.adcast.presentation.DrawerAdRunner
import com.find.my.phone.gps.location.tracker.adcast.presentation.NativePromo
import com.find.my.phone.gps.location.tracker.adcast.presentation.NativePromoBanner
import com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds.InterstitialNormal
import com.find.my.phone.gps.location.tracker.BuildConfig
import io.launcher.home.extensions.isDefaultLauncher
import org.json.JSONObject

/**
 * The `launcher_ads` Remote Config block — everything the launcher does around ads, the
 * home-screen coach mark and the first-run route. The full schema lives in
 * `docs/launcher-ads-config.md`; the short version:
 *
 * ```json
 * {
 *   "app_click":   { "enabled": true, "counter": 0, "sequence": ["directlink","inter"],
 *                    "show_all": true, "directlink_urls": ["https://…"] },
 *   "app_close":   { … same fields … },
 *   "left_panel":  { "enabled": true, "open_ad": { … same fields … },
 *                    "native_mid": { "enabled": true, "native_type": "native_banner" },
 *                    "native_bottom": { "enabled": true, "native_type": "mid2" } },
 *   "right_panel": { "enabled": true, "open_ad": { … }, "native": { "enabled": true, "native_type": "mid2" } },
 *   "app_drawer":  { "bottom_native": { … } },
 *   "home_hint":   { "enabled": true, "swipeHints": ["right","left","up"], "show_mode": "once" },
 *   "default_home_screen": { "enabled": true, "skip_if_default": true, "skip_rest_on_grant": true },
 *   "onboarding":  { "order": [ … ], "welcome": { "inter_enabled": false, "slot": { … } }, … },
 *   "defaultHome":    { … },
 *   "notDefaultHome": { … }
 * }
 * ```
 *
 * Stored as a JSON string in AdsVault and read back with [JSONObject], the same route
 * `intro_display` and `ScreenAds` take — see `AdBeaconActivity.ingestConfig`.
 *
 * ## Variants
 *
 * Everything outside `defaultHome` / `notDefaultHome` is the base. Exactly one variant is
 * merged on top of it per read, picked by whether we hold the HOME role, and the merge is
 * deep: a key present in the variant wins, a key absent inherits, at every level. Arrays are
 * replaced whole. So `"defaultHome": { "app_click": { "counter": 1 } }` changes the one
 * counter and leaves the rest of `app_click` alone.
 *
 * ## Pacing
 *
 * Each launcher surface (`app_click`, `app_close`, `left_panel.open_ad`, `right_panel.open_ad`)
 * has one `counter`: how many events are SKIPPED before its ad moment lands. `3` skips three and
 * fires on the fourth; `0` fires every time. Each counts in its own pref, so the pacing survives
 * the launcher process being killed, and it never advances while there is nothing to show.
 *
 * Note: the native renderers draw from one preloaded pool and take no per-call ad unit,
 * so a slot's `ad_unit_id` is honoured for `banner` only — a native slot inherits the global
 * `googleNative` (or its screen-wise id) as it always has.
 *
 * In DEBUG every decision is logged under the tag `LauncherAdsConfig`.
 */
object LauncherAdsConfig {

    private const val TAG = "LauncherAdsConfig"
    private const val CONFIG_KEY = "launcher_ads"

    private const val VARIANT_DEFAULT_HOME = "defaultHome"
    private const val VARIANT_NOT_DEFAULT_HOME = "notDefaultHome"

    // ===================== launcher surfaces =====================

    /**
     * The four launcher moments, each configured by ONE block with the same fields:
     *
     * - [APP_CLICK]  `app_click` — an app opened from the drawer, the home grid or the dock
     *   (the launcher's one `appLaunch` gesture); the ad runs before the app opens.
     * - [APP_CLOSE]  `app_close` — back on the launcher from an app it opened.
     * - [LEFT_PANEL] `left_panel.open_ad` — the swipe-left apps panel opening.
     * - [RIGHT_PANEL] `right_panel.open_ad` — the swipe-right app panel opening.
     * - [HOME_PRESS] `home_press` — the navigation-bar Home pressed while already on the launcher
     *   home with nothing open (absent = off).
     *
     * Fields: `enabled`, `counter` (skip N, show on the N+1th; saved, so it survives restarts),
     * `sequence` (`directlink` `inter` `app_open` `rewarded` `native` `custom`), `show_all` (every
     * ready ad in order vs. the first ready one), `directlink_urls`, and optional `on_demand`
     * (load the first ad at tap time when it isn't ready). Unit ids are the global
     * `googleInter` / `googleAppopen` / `googleRewarded` / `googleNative`. This block is the only
     * gate: the launcher module's own `launcher_config.gestures` / `panels` are derived from it
     * (CallerLauncherBridge), so there is one counter per moment, not two stacked.
     */
    enum class LauncherSurface(val label: String, val counterKey: String, val pointerKey: String) {
        APP_CLICK("app_click", "__launcher_app_click_count", "__launcher_app_click_ptr"),
        APP_CLOSE("app_close", "__launcher_app_close_count", "__launcher_app_close_ptr"),
        LEFT_PANEL("left_panel.open_ad", "__launcher_left_panel_count", "__launcher_left_panel_ptr"),
        RIGHT_PANEL("right_panel.open_ad", "__launcher_right_panel_count", "__launcher_right_panel_ptr"),

        /** An app tapped on the home screen (grid, dock, folders). Absent → [APP_CLICK]. */
        HOME_CLICK("home_click", "__launcher_home_click_count", "__launcher_home_click_ptr"),

        /** An app tapped in the drawer. Absent → [APP_CLICK]. */
        DRAWER_CLICK("drawer_click", "__launcher_drawer_click_count", "__launcher_drawer_click_ptr"),

        /** Back on the home screen with nothing open to close. Absent = off. */
        HOME_BACK("home_back", "__launcher_home_back_count", "__launcher_home_back_ptr"),

        /**
         * Recents pressed on the launcher; runs when the user comes back to it within the block's
         * `return_window_sec` (see RecentsWatcher). Absent = off.
         */
        RECENT_CLICK("recent_click", "__launcher_recent_click_count", "__launcher_recent_click_ptr"),

        /**
         * The navigation-bar Home pressed while already on the launcher home with nothing open.
         * A return from another app stays [APP_CLOSE]. Absent = off.
         */
        HOME_PRESS("home_press", "__launcher_home_press_count", "__launcher_home_press_ptr"),
    }

    private fun surfaceBlock(context: Context, surface: LauncherSurface): JSONObject? {
        val root = config(context)
        return when (surface) {
            LauncherSurface.APP_CLICK -> root.optJSONObject("app_click")
            LauncherSurface.APP_CLOSE -> root.optJSONObject("app_close")
            LauncherSurface.LEFT_PANEL -> root.optJSONObject("left_panel")?.optJSONObject("open_ad")
            LauncherSurface.RIGHT_PANEL -> root.optJSONObject("right_panel")?.optJSONObject("open_ad")
            LauncherSurface.HOME_CLICK -> root.optJSONObject("home_click")
            LauncherSurface.DRAWER_CLICK -> root.optJSONObject("drawer_click")
            LauncherSurface.HOME_BACK -> root.optJSONObject("home_back")
            LauncherSurface.RECENT_CLICK -> root.optJSONObject("recent_click")
            LauncherSurface.HOME_PRESS -> root.optJSONObject("home_press")
        }
    }

    /**
     * The surface an app tap from [source] ([LauncherSurface.HOME_CLICK] / [LauncherSurface.DRAWER_CLICK])
     * actually runs: its own block when Remote Config has one, else the shared `app_click`.
     */
    fun clickSurface(context: Context, source: LauncherSurface): LauncherSurface =
        if (surfaceBlock(context, source) != null) source else LauncherSurface.APP_CLICK

    /** `recent_click.return_window_sec`: how long after Recents a return still counts. Default 60s. */
    fun recentReturnWindowMs(context: Context): Long =
        (surfaceBlock(context, LauncherSurface.RECENT_CLICK)?.optLong("return_window_sec", 60L) ?: 60L)
            .coerceAtLeast(0L) * 1000L

    /** [surface]'s ad flow; a missing block is off. */
    fun surfaceFlow(context: Context, surface: LauncherSurface): DrawerAdFlow {
        val block = surfaceBlock(context, surface) ?: return DrawerAdFlow(false, 0, emptyList())
        // `directlink_urls` sits on the block itself; the shared parser reads links from
        // `ads.directlink.urls`, so hand it that shape. A `directlink` object of the block's own
        // (its `url`, `open_type`, `enabled`) is kept: the list is added to it, never swapped in
        // over it, and nothing is synthesised when neither is set, so the global `DirectLink`
        // fallback still applies.
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
        return parseFlow(context, normalised, normalised, surface.label)
    }

    /** Whether [surface] would show anything at all (used to switch the launcher's own gate). */
    fun surfaceOn(context: Context, surface: LauncherSurface): Boolean =
        surfaceFlow(context, surface).let { it.enabled && it.sequence.isNotEmpty() }

    /**
     * Runs [surface]'s ads, then [proceed] — exactly once on every path (ads off, block off,
     * counter skip, nothing ready, failure), so the gesture always completes.
     */
    fun runSurface(activity: Activity, surface: LauncherSurface, proceed: () -> Unit) {
        if (!AdsVault.getInstance(activity).getBoolean("IsAdsON")) return proceed()
        val flow = surfaceFlow(activity, surface)
        if (!flow.enabled || flow.sequence.isEmpty()) {
            log("${surface.label}: off")
            return proceed()
        }
        if (!isDue(activity, surface.counterKey, flow.counter, surface.label)) return proceed()
        if (surface in CLICK_SURFACES) {
            // The tapped app's icon goes on the loader shown while an `on_demand` ad is fetched.
            DrawerAdRunner.runFor(
                activity, flow, surface.pointerKey,
                io.launcher.home.api.LauncherRegistry.launchingPackage, proceed,
            )
        } else {
            DrawerAdRunner.run(activity, flow, surface.pointerKey, proceed)
        }
    }

    private val CLICK_SURFACES = setOf(
        LauncherSurface.APP_CLICK, LauncherSurface.HOME_CLICK, LauncherSurface.DRAWER_CLICK,
    )

    /** Preloads every surface's formats so each moment has an ad ready (one load per format). */
    fun preloadLauncherAds(context: Context) {
        if (!AdsVault.getInstance(context).getBoolean("IsAdsON")) return
        LauncherSurface.entries.map { surfaceFlow(context, it) }
            .filter { it.enabled && it.sequence.isNotEmpty() }
            .forEach { DrawerAdRunner.preload(context, it) }
    }

    /** `left_panel.enabled` / `right_panel.enabled`: whether the swipe opens the panel. Absent = on. */
    fun panelEnabled(context: Context, left: Boolean): Boolean =
        config(context).optJSONObject(if (left) "left_panel" else "right_panel")?.optBoolean("enabled", true) ?: true

    // ===================== ad slots =====================

    /** What fills an on-screen ad frame. */
    enum class SlotAd { NATIVE, BANNER, NONE }

    data class Slot(
        val enabled: Boolean,
        val adType: SlotAd,
        val nativeType: String,
        val bannerType: String,
        val adUnitId: String,
        /**
         * Where the slot sits in a *list* that carries it, counted in *rows* from the top.
         *
         * Only the app drawer reads this — its grid scrolls the ad along with the apps, so the
         * ad needs a place in the list rather than a fixed edge. `0` is the top of the list and
         * the behaviour that shipped before this field existed; a row past the end of the list
         * clamps to the end, so a deliberately large number reads as "last".
         *
         * Rows, not item indexes: the drawer is a grid, and dropping a full-width ad between
         * two icons of the same row would leave a hole in it.
         */
        val position: Int = 0,
    ) {
        val visible: Boolean get() = enabled && adType != SlotAd.NONE
        /** True when the slot needs the native pool warmed before it can render. */
        val needsNativePreload: Boolean get() = visible && adType == SlotAd.NATIVE
    }

    /** `left_panel.native_bottom`: the frame at the bottom of the swipe-left apps panel. Absent = off. */
    fun leftPanelBottomSlot(context: Context): Slot = optionalSlot(
        config(context).optJSONObject("left_panel")?.optJSONObject("native_bottom"), "mid2", "left_panel.native_bottom",
    )

    /**
     * `left_panel.native_mid`: the frame under the apps panel's suggested-apps grid. A native
     * banner by default — it sits between two sections. Absent = off.
     */
    fun leftPanelMidSlot(context: Context): Slot = optionalSlot(
        config(context).optJSONObject("left_panel")?.optJSONObject("native_mid"), "native_banner", "left_panel.native_mid",
    )

    /** `right_panel.native`: the native card inside the swipe-right app panel (Home). Absent = off. */
    fun rightPanelNativeSlot(context: Context): Slot = optionalSlot(
        config(context).optJSONObject("right_panel")?.optJSONObject("native"), "mid2", "right_panel.native",
    )

    private fun optionalSlot(block: JSONObject?, defaultNativeType: String, label: String): Slot =
        if (block == null) Slot(false, SlotAd.NONE, defaultNativeType, "adaptive", "")
        else slot(block, defaultNativeType = defaultNativeType, label = label)

    /**
     * The frame at the bottom of the swipe-up app drawer. Off unless Remote Config asks for
     * it — the drawer shipped without an ad, so a missing block keeps it that way.
     */
    fun appDrawerSlot(context: Context): Slot {
        val block = config(context).optJSONObject("app_drawer")?.optJSONObject("bottom_native")
            ?: return Slot(false, SlotAd.NONE, "mid2", "adaptive", "")

        return slot(block, defaultNativeType = "mid2", label = "app_drawer.bottom_native")
    }

    /** What the install / uninstall result screen may do, from `package_result` in Remote Config. */
    data class PackageResultSettings(
        val enabled: Boolean,
        val minGapMs: Long,
        val bodyNative: Boolean,
    )

    /**
     * `package_result: { "enabled": true, "min_gap_ms": 0, "body_native": true }`.
     *
     * `enabled` turns the whole screen off without a release; `min_gap_ms` throttles it so a burst
     * of package events cannot show it repeatedly; `body_native` carries its in-card native ad.
     */
    fun packageResultSettings(context: Context): PackageResultSettings {
        val block = config(context).optJSONObject("package_result")
        return PackageResultSettings(
            enabled = block?.optBoolean("enabled", true) ?: true,
            minGapMs = block?.optLong("min_gap_ms", 0L) ?: 0L,
            bodyNative = block?.optBoolean("body_native", true) ?: true,
        ).also { log("package_result → $it") }
    }

    /**
     * The `recent_ad` block — the full-screen page shown when the user reopens the app from the
     * **Recents / overview** list.
     *
     * [nativeType] is the body format (`big` / `mid` / `mid2`, or `off` for no body ad) and
     * [closeAd] is what fires when the page is closed (`inter` | `directlink` | `none`).
     */
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

    /**
     * `recent_ad: { "enabled": false, "native": "off", "close_ad": "none", "min_gap_sec": 0 }`.
     *
     * **Defaults off, and every gate fails closed** — an absent, blank or malformed block, or
     * `enabled` not explicitly true, shows nothing. This is deliberate and worth keeping: the page
     * appears on a resume the user did not ask for, which Google Play's Disruptive Ads policy treats
     * as an out-of-context ad, and the reference ships it off for exactly that reason. It also leans
     * on `CLOSE_SYSTEM_DIALOGS`, a broadcast the platform keeps narrowing, so it may simply stop
     * firing on a future release.
     */
    fun recentAdSettings(context: Context): RecentAdSettings =
        eventAdSettings(config(context).optJSONObject("recent_ad"), "recent_ad")

    /**
     * `system_ads.charge` / `system_ads.discharge` — the screen shown when the charger is plugged in
     * or pulled out (QRScanner's `system_ads`). Same shape and the same fail-closed defaults as
     * `recent_ad`, for the same Disruptive Ads reason: it appears on an event the user did not start
     * inside the app. [trigger] is `charge` or `discharge`.
     */
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

    /**
     * The app-wide direct link — the flat `DirectLink` pref, the same one the drawer's `directlink`
     * step falls back to. Used by surfaces that have no link of their own, such as the Recents
     * page's `close_ad: directlink`.
     */
    fun directLink(context: Context): String =
        AdsVault.getInstance(context).getString("DirectLink").orEmpty().trim()

    /** One configurable promo tile in the app drawer: an icon + title that opens [link] when tapped. */
    data class DrawerPromo(
        val position: Int,
        val title: String,
        val icon: String,
        val link: String,
    )

    /**
     * The drawer's promo tiles, from `app_drawer.promo` in Remote Config:
     * `{ "enabled": true, "items": [ { position, title, icon, link, enabled } ] }`. Only enabled
     * items with both an icon and a link survive; each is placed at its own `position` in the list.
     * `landing_url` is accepted as an alias for `link`. An absent or disabled block yields nothing.
     */
    fun drawerPromoItems(context: Context): List<DrawerPromo> {
        val block = config(context).optJSONObject("app_drawer")?.optJSONObject("promo")
            ?: return emptyList()
        if (!block.optBoolean("enabled", true)) return emptyList()
        val items = block.optJSONArray("items") ?: return emptyList()

        val out = ArrayList<DrawerPromo>(items.length())
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (!item.optBoolean("enabled", true)) continue
            // Accept the reference field names as aliases: logo→icon, label→title, landing_url→link.
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

    // ---------- App-drawer ad sequence (Remote-Config-driven fallback chain) ----------

    /**
     * The ad formats the app-drawer sequence can play. `idFallbackKey` is the legacy PromoVault key
     * an ad-unit-id (or URL, for [DIRECTLINK]) falls back to when the config leaves `ad_unit_id`
     * blank, so existing global ids keep working without being duplicated into the new block.
     */
    enum class DrawerAdType(val key: String, val idFallbackKey: String) {
        INTER("inter", "googleInter"),
        APPOPEN("appopen", "googleAppopen"),
        DIRECTLINK("directlink", "DirectLink"),
        REWARDED("rewarded", "googleRewarded"),
        FULLSCREEN_NATIVE("fullscreen_native", "googleNative"),

        /** This app's own house ad (`custom_ads`), full screen. Needs `IsCustomADS`; no unit id. */
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

    /**
     * One resolved ad in the sequence: its format and the ad-unit-id (or URL, for directlink).
     *
     * [urls] and [openType] are direct-link only. [urls] is the configured rotation — the next one
     * is picked when the link actually opens, not here, because this is parsed several times per
     * trigger (preload, then run) and rotating on parse would burn through the list. [openType]
     * overrides the global `DirectLinkType` for this one slot; blank means "use the global".
     */
    data class DrawerAdSpec(
        val type: DrawerAdType,
        val adUnitId: String,
        val urls: List<String> = emptyList(),
        val openType: String = "",
    )

    /**
     * The whole app-drawer ad flow: whether it runs, how often, the resolved chain, and how the
     * chain is walked.
     *
     * [showAll] and [startFromFirst] are the two switches that pick the variant:
     *
     * | `show_all_ads` | `always_start_first` | one app tap does…                                   |
     * |----------------|----------------------|-----------------------------------------------------|
     * | `true`         | (ignored)            | shows **every** ready ad back to back — inter closes, app-open closes, rewarded closes, fullscreen native closes — then opens the app |
     * | `false`        | `true`               | shows the **first** ready ad, always trying inter first and falling through the chain when one is not ready or fails |
     * | `false`        | `false`             | the same, but resuming where the last tap left off, so taps rotate through the formats |
     */
    data class DrawerAdFlow(
        val enabled: Boolean,
        val counter: Int,
        val sequence: List<DrawerAdSpec>,
        val showAll: Boolean = false,
        val startFromFirst: Boolean = false,
        /**
         * A format that is not loaded yet is loaded on the spot and shown, instead of skipped
         * (QRScanner's OnDemandInterstitial). For a placement whose own unit nothing preloads.
         */
        val onDemand: Boolean = false,
        /**
         * How long an [onDemand] load may hold the loader before that format is skipped and the
         * sequence moves on. `loader_timeout_ms` in the block; see [loaderTimeoutMs].
         */
        val loaderTimeoutMs: Long = DEFAULT_LOADER_TIMEOUT_MS,
    )

    /** `loader_timeout_ms` when the block leaves it out: the 8s that was hard-coded before. */
    const val DEFAULT_LOADER_TIMEOUT_MS = 8_000L

    /** Bounds for `loader_timeout_ms`: long enough for a fill on a slow network, never a stuck tap. */
    private const val MIN_LOADER_TIMEOUT_MS = 1_000L
    private const val MAX_LOADER_TIMEOUT_MS = 20_000L

    /** `loader_timeout_ms` (alias `on_demand_timeout_ms`) from [block], clamped to 1–20s. */
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

    /**
     * Parses one surface's flow. [block] carries the switches (`enabled`, `counter`, `sequence`,
     * `show_all`, `always_start_first`, `on_demand`); [idsFrom] is where per-format objects live.
     */
    private fun parseFlow(
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
            val id = resolveDrawerAdId(context, type, ad)
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

        // `on_demand`: the first format in line is loaded at tap time when it is not ready yet,
        // instead of being skipped. Off by default — it makes the tap wait on the network.
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

    /**
     * A direct link's "id" is a URL, so `url` / `link` / `landing_url` are read as well as
     * `ad_unit_id` — naming it after an ad unit reads wrong in Remote Config and was getting left
     * blank, which dropped `directlink` out of the chain entirely.
     */
    /**
     * The direct link's URL rotation: `urls` (or `links` / `landing_urls`), an array of strings or
     * of `{ url, enabled }` objects. Blank entries and disabled objects are dropped. Empty when the
     * slot is configured with a single `url` instead — the two forms can coexist, and the array
     * wins because a list of one is the same thing.
     */
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

    private fun resolveDrawerAdId(context: Context, type: DrawerAdType, ad: JSONObject?): String {
        val keys = if (type == DrawerAdType.DIRECTLINK) {
            listOf("ad_unit_id", "url", "link", "landing_url")
        } else {
            listOf("ad_unit_id")
        }
        keys.forEach { key ->
            val configured = ad?.optString(key, "").orEmpty().trim()
            if (configured.isNotBlank()) return configured
        }
        return AdsVault.getInstance(context).getString(type.idFallbackKey).orEmpty().trim()
    }

    /**
     * Renders [slot] into [container]. Hides the frame outright when the slot is off, so a
     * screen that follows the frame's visibility (the hairline dividers do) collapses with it.
     */
    fun showSlot(
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
                "inline" -> BannerScale.INLINE to false
                "normal" -> BannerScale.NORMAL to false
                "collapsible" -> BannerScale.ADAPTIVE to true
                else -> BannerScale.ADAPTIVE to false
            }
            log("slot: banner type=${slot.bannerType} collapsible=$collapsible")

            // disableInternalFallback=true → BannerPromo reports a single onAdFailed() so the
            // native-banner fallback owns the failure path, same as ScreenPromoConfig.showAd.
            BannerPromo().showBanner(
                activity = activity,
                container = container,
                type = BannerKind.AUTO,
                size = size,
                isCollapsable = collapsible,
                shimmer = shimmer,
                customAdUnitId = slot.adUnitId.takeIf { it.isNotBlank() },
                disableInternalFallback = true,
                observer = object : BannerAdWatcher {
                    override fun onAdFailed() {
                        log("slot: banner failed, falling back to native banner")
                        NativePromoBanner().showNativeBannerNative(activity, container, shimmer)
                    }
                }
            )
            return
        }

        log("slot: native type=${slot.nativeType}")
        val render = {
            when (slot.nativeType.lowercase()) {
                "big" -> NativePromo().showBigNative(activity, container, shimmer)
                "mid" -> NativePromo().showMidNative(activity, container, shimmer)
                "native_banner" -> NativePromoBanner().showNativeBannerNative(activity, container, shimmer)
                else -> NativePromo().showMidNative2(activity, container, shimmer)
            }
        }
        // The native pool is one shared ad, and every screen that renders it starts a refill. A
        // screen opened while that refill is in flight (onboarding steps a second or two apart)
        // found it empty, fell to the house-ad fallback and stayed blank. Hold the shimmer and
        // render when the ad lands — or fails, which runs the usual fallback.
        if (!slot.nativeType.equals("native_banner", ignoreCase = true) && !NativePromo.hasPreloadedNative()) {
            log("slot: native pool empty — waiting for the load")
            container.visibility = View.VISIBLE
            shimmer?.visibility = View.VISIBLE
            shimmer?.startShimmer()
            val renderIfAlive = { if (!activity.isFinishing && !activity.isDestroyed) render() }
            NativePromo().loadNativeADs(activity, object : NativePromo.NativeAdObserver {
                override fun onNativeAdLoaded() = renderIfAlive()
                override fun onNativeAdFailed() = renderIfAlive()
            })
            return
        }
        render()
    }

    /**
     * [showSlot] with a refresh policy, for the surfaces that are opened over and over — the
     * app drawer and the left panel.
     *
     * Re-showing unconditionally is what made those two look broken. The native pool is a
     * SINGLE static ad: rendering it consumes the ad and starts a refill, so a second open
     * before that refill lands finds nothing, drops through to the fallback branch, and
     * replaces a perfectly good ad with a custom one. The left panel hit it every single
     * time — it asks for two native frames back to back, and the second could never win.
     *
     * So: fill an empty frame exactly as before, replace a filled one only when a fresh
     * native is actually in hand, and otherwise leave what is on screen and warm the next.
     * Banner slots are untouched — they own their own refresh.
     */
    fun refreshSlot(
        activity: Activity,
        slot: Slot,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
    ) {
        // The shimmer placeholder is a child too; only a real ad view counts as "holding an ad".
        val holdsAnAd = (0 until container.childCount).any { container.getChildAt(it) !== shimmer }
        if (!slot.needsNativePreload || !holdsAnAd || NativePromo.hasPreloadedNative()) {
            showSlot(activity, slot, container, shimmer)
            return
        }

        log("slot: no fresh native — keeping the one on screen, warming the next")
        NativePromo().loadNativeADs(activity)
    }

    private fun slot(block: JSONObject?, defaultNativeType: String, label: String): Slot {
        if (block == null) {
            // No entry at all → the frame behaves exactly as it did before the block existed.
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
            // Negative values would push the ad off the front of the list — floor at the top.
            position = block.optInt("position", 0).coerceAtLeast(0),
        ).also { log("$label → $it") }
    }

    // ===================== home-screen coach mark =====================

    enum class HintDirection(val key: String) {
        RIGHT("right"), LEFT("left"), UP("up"), DOWN("down");

        companion object {
            fun from(raw: String?): HintDirection? =
                entries.firstOrNull { it.key == raw?.trim()?.lowercase() }
        }
    }

    /** How often the hint comes back. */
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
            // A present-but-empty list is an explicit "teach nothing", not a fallback.
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

    /**
     * Whether an `app_launches` hint is due on this launch. `once` and `always` are decided by
     * the caller (the launcher's own `wasSwipeHintShown` pref latches `once`, so an install
     * that has already seen the hint does not see it again after an update).
     */
    fun isHintDue(context: Context, hint: HomeHint): Boolean =
        isDue(context, HINT_COUNTER_KEY, hint.interval, "home_hint")

    private const val HINT_COUNTER_KEY = "__launcher_ads_home_hint_count"

    // ===================== onboarding =====================

    /**
     * The first-run screens, with the ad behaviour each one had before this block existed:
     * Welcome and the default-home ask carry a mid native and no interstitial, the intro
     * carousel a mid2 native, the language picker a big native, both with an interstitial on
     * the way out.
     */
    enum class OnboardScreen(
        val key: String,
        private val interByDefault: Boolean,
        private val nativeByDefault: String,
    ) {
        WELCOME("welcome", false, "mid"),
        SET_DEFAULT("set_default", false, "big"),
        INTRO("intro", true, "mid2"),
        LANGUAGE("language", true, "big"),

        /** The reminders step (ReminderIntroActivity): a bottom mid native, no exit interstitial. */
        REMINDERS("reminders", false, "mid");

        val counterKey: String get() = "__launcher_ads_onboarding_${key}_count"

        internal fun defaults(): Pair<Boolean, String> = interByDefault to nativeByDefault

        companion object {
            fun from(raw: String?): OnboardScreen? =
                entries.firstOrNull { it.key == raw?.trim()?.lowercase() }
        }
    }

    /** The ad frame on a first-run screen. */
    fun onboardingSlot(context: Context, screen: OnboardScreen): Slot = slot(
        block = onboardingBlock(context, screen)?.optJSONObject("slot"),
        defaultNativeType = screen.defaults().second,
        label = "onboarding.${screen.key}.slot",
    )

    /**
     * The parts of a first-run screen that are not ads.
     *
     * [skipEnabled] hides the Skip affordance when false, which turns the screen into a
     * required step — the CTA (or Back, where the screen offers it) is then the only way on.
     *
     * [backAdvances] applies to the intro carousel: `true` makes Back leave for the next
     * screen instead of walking forward through the remaining pages.
     */
    data class ScreenUi(val skipEnabled: Boolean, val backAdvances: Boolean)

    fun onboardingUi(context: Context, screen: OnboardScreen): ScreenUi {
        val block = onboardingBlock(context, screen)
        return ScreenUi(
            skipEnabled = block?.optBoolean("skip_enabled", true) ?: true,
            backAdvances = block?.optString("back_action")
                ?.trim()?.lowercase() == "next_screen",
        ).also { log("onboarding.${screen.key}.ui → $it") }
    }

    /**
     * Runs [screen]'s exit interstitial, then [proceed] — invoked exactly once on every path,
     * so a first-run screen never dead-ends on a missing ad.
     */
    fun runOnboardingInter(activity: Activity, screen: OnboardScreen, proceed: () -> Unit) {
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

        // `onboarding_<screen>_ads_on` / `onboarding_ads_on` false: no ad at all on this Next.
        if (!LauncherPlacementAds.placementEnabled(activity, "onboarding_${screen.key}")) {
            log("onboarding.${screen.key}: ads_on=false")
            return proceed()
        }
        // Link-first replaces the interstitial when configured: `onboarding_<screen>_DirectLink` +
        // `onboarding_<screen>_link_first_then`, each falling back to the shared `onboarding_…`
        // keys, so screens differ only where they are configured to.
        if (LauncherPlacementAds.showLinkFirst(activity, "onboarding_${screen.key}", proceed)) {
            log("onboarding.${screen.key}: link-first")
            return
        }
        log("onboarding.${screen.key}: showing interstitial")
        InterstitialNormal().showInterAds(activity) { proceed() }
    }

    /**
     * The first-run sequence. Unknown names are dropped; an empty or missing `order` falls
     * back to the historical flow. Repeats are kept — listing `set_default` twice asks again
     * at the end — and are resolved by the caller, which skips an entry with nothing to do.
     */
    fun onboardingOrder(context: Context): List<OnboardScreen> {
        val listed = config(context).optJSONObject("onboarding")?.optJSONArray("order")
            ?: return DEFAULT_ORDER

        val order = (0 until listed.length()).mapNotNull { OnboardScreen.from(listed.optString(it)) }
        return withReminders(order.ifEmpty { DEFAULT_ORDER })
            .also { log("onboarding.order → ${it.map(OnboardScreen::key)}") }
    }

    /**
     * An order published before the reminders step existed does not list `reminders`: it then
     * goes right after `welcome` (or at the end). Listing it places it explicitly; whether it
     * actually shows is up to `feature_config` (see LauncherFlow.isApplicable).
     */
    private fun withReminders(order: List<OnboardScreen>): List<OnboardScreen> {
        if (OnboardScreen.REMINDERS in order) return order
        val at = order.indexOf(OnboardScreen.WELCOME).let { if (it < 0) order.size else it + 1 }
        return order.toMutableList().apply { add(at, OnboardScreen.REMINDERS) }
    }

    /** The default-home ask comes first, so the user's first decision is the one that matters. */
    private val DEFAULT_ORDER = listOf(
        OnboardScreen.SET_DEFAULT,
        OnboardScreen.LANGUAGE,
        OnboardScreen.WELCOME,
        OnboardScreen.REMINDERS,
        OnboardScreen.INTRO,
    )

    /**
     * `onboarding.<screen>.auto_next`: whether a step the user has already satisfied (the Home
     * role granted) moves on by itself, and after how long. `auto_next` defaults true — a config
     * that fails to fetch must not strand the user on a finished step; `auto_next_delay_ms`
     * defaults 650, clamped to 0–5000.
     */
    fun onboardingAutoNext(context: Context, screen: OnboardScreen): Pair<Boolean, Long> {
        val block = onboardingBlock(context, screen)
        val auto = block?.optBoolean("auto_next", true) ?: true
        val delay = (block?.optLong("auto_next_delay_ms", 650L) ?: 650L).coerceIn(0L, 5_000L)
        return auto to delay
    }

    private fun onboardingBlock(context: Context, screen: OnboardScreen): JSONObject? =
        config(context).optJSONObject("onboarding")?.optJSONObject(screen.key)

    // ===================== the "set as default launcher" step =====================

    data class DefaultHomeStep(
        val enabled: Boolean,
        val skipIfDefault: Boolean,
        val skipRestOnGrant: Boolean,
    )

    /** Defaults reproduce the shipped flow: shown, skipped when already default, grant → home. */
    fun defaultHomeStep(context: Context): DefaultHomeStep {
        val block = config(context).optJSONObject("default_home_screen")
        return DefaultHomeStep(
            enabled = block?.optBoolean("enabled", true) ?: true,
            skipIfDefault = block?.optBoolean("skip_if_default", true) ?: true,
            skipRestOnGrant = block?.optBoolean("skip_rest_on_grant", true) ?: true,
        )
    }

    // ===================== plumbing =====================

    /**
     * The parsed block with the right variant merged in. Cached per (raw config, role) pair —
     * the merge runs on the UI thread from gesture handlers, and the raw string only changes
     * when a fresh config is ingested.
     */
    @Volatile
    private var cache: Pair<String, JSONObject>? = null

    private fun config(context: Context): JSONObject {
        val raw = AdsVault.getInstance(context).getString(CONFIG_KEY, "").orEmpty()
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

    /**
     * Copies [overlay] onto [base] in place: nested objects merge key by key, everything else
     * (scalars, arrays) is replaced whole. Arrays deliberately do not merge — a variant's
     * `swipeHints` is the whole list, not additions to the base list.
     */
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

    /**
     * Skip-then-show, counted in prefs so it survives the launcher process being killed —
     * which happens often, and an in-memory counter would reset the pacing every time.
     */
    private fun isDue(context: Context, counterKey: String, target: Int, label: String): Boolean {
        if (target <= 0) return true

        val pref = AdsVault.getInstance(context)
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
