package com.livemap.homedeck.teaser

import android.app.Activity
import com.livemap.homedeck.contract.HomeBook
import com.livemap.homedeck.setup.HomeBootstrap.SponsoredApp
import com.livemap.homedeck.entities.CoreHome

object BackedSquares {
    private const val PACKAGE_PREFIX = "com.livemap.homedeck.sponsored."

    private val logos = HashMap<String, String>()

    fun isSponsored(item: CoreHome) = item.packageName.startsWith(PACKAGE_PREFIX)

    fun logo(item: CoreHome): String = logos[item.packageName].orEmpty()

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
