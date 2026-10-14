package com.livemap.homedeck.contract

import android.content.Context
import com.livemap.homedeck.support.QnaRouteWarden
import com.livemap.homedeck.setup.HomeBootstrap

object HomeBook {
    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var installedBridge: HomeGateway = HomeGateway.Default

    @Volatile
    private var installedAds: HomeSpots = HomeSpots.NoOp

    @Volatile
    var launchingPackage: String? = null
        internal set

    val bridge: HomeGateway get() = installedBridge

    val ads: HomeSpots get() = installedAds

    @JvmStatic
    @JvmOverloads
    fun install(
        context: Context,
        bridge: HomeGateway = HomeGateway.Default,
        ads: HomeSpots = HomeSpots.NoOp,
    ) {
        appContext = context.applicationContext
        QnaRouteWarden.register(context)
        installedBridge = bridge
        installedAds = ads
    }

    fun ensure(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        QnaRouteWarden.register(context)
    }

    fun context(): Context = requireNotNull(appContext) {
        "HomeBook has no context: an entry point did not call ensure(context)"
    }

    fun setup(): HomeBootstrap = HomeBootstrap.parse(
        raw = installedBridge.configString(HomeTokens.LAUNCHER_CONFIG, ""),
        organic = installedBridge.isOrganicAudience(),
    )
}

object HomeTokens {
    const val LAUNCHER_CONFIG = "launcher_config"

    const val ADS_CONFIG = "ads_config"

    const val HOME_PROFILE_RULES = "home_profile_rules"

    const val HOME_PROFILE_PROBES = "home_profile_probes"
}
