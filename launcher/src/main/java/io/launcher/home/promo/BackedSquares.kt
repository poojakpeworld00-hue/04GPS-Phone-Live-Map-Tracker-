package io.launcher.home.promo

import android.app.Activity
import io.launcher.home.api.HomeBook
import io.launcher.home.config.HomeBootstrap.SponsoredApp
import io.launcher.home.models.CoreHome

/**
 * Sponsored tiles in the app drawer (`drawerAppAdEnabled` / `drawerAppAdEvery` / `drawerAppAds` in
 * `launcher_config`, QRScanner's keys).
 *
 * A tile rides in the drawer's list as a marker [CoreHome], the same trick as
 * `HomesFeed.AD_SLOT`, so the scrolling grid, the pages and the diffing need no new row type.
 * Each marker gets its own package name because [CoreHome.equals] compares only that; the landing
 * URL travels in `activityName` and the logo is looked up here by package.
 */
object BackedSquares {

    /** Not a package any launcher can resolve, so a tile can never collide with a real app. */
    private const val PACKAGE_PREFIX = "io.launcher.home.sponsored."

    private val logos = HashMap<String, String>()

    fun isSponsored(item: CoreHome) = item.packageName.startsWith(PACKAGE_PREFIX)

    fun logo(item: CoreHome): String = logos[item.packageName].orEmpty()

    /**
     * [apps] with a tile after every `drawerAppAdEvery` of them. Each feed entry is used once, in
     * order, so the drawer never repeats a tile. The caller skips this while searching: the list is a
     * different, shorter thing every keystroke, and a promo drifting through results reads as one.
     */

    fun interleave(apps: List<CoreHome>): List<CoreHome> {
        val setup = HomeBook.setup()
        val feed = setup.drawerAppAds
        if (!setup.drawerAppAdEnabled || feed.isEmpty() || apps.isEmpty()) return apps
        val every = setup.drawerAppAdEvery.coerceAtLeast(1)
        val out = ArrayList<CoreHome>(apps.size + feed.size)
        var fed = 0
        apps.forEachIndexed { i, app ->
            out.add(app)
            if ((i + 1) % every == 0 && fed < feed.size) out.add(marker(feed[fed], fed++))
        }
        return out
    }
    private fun marker(ad: SponsoredApp, index: Int): CoreHome {
        // Keyed by the tile's own package, not its landing URL: feed entries may share one landing
        // page (one store listing) and still carry different logos, and a URL key made the last
        // entry's logo win for all of them.
        val pkg = "$PACKAGE_PREFIX$index"
        logos[pkg] = ad.logo
        return CoreHome(
            id = null,
            title = ad.label,
            packageName = pkg,
            activityName = ad.landingUrl,
            order = 0,
            thumbnailColor = 0,
            drawable = null,
        )
    }
    fun open(activity: Activity, item: CoreHome) =
        HomeBook.ads.openSponsored(activity, item.activityName)
}
