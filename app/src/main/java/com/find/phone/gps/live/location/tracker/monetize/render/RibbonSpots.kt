package com.find.phone.gps.live.location.tracker.monetize.render
import android.app.Activity
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotIncomeGauge
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdLoadError
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.facebook.ads.AdView as FbAdView

enum class RibbonSpan { ADAPTIVE, INLINE, NORMAL }
enum class RibbonSort { AUTO, GOOGLE, FACEBOOK, CUSTOM }

interface RibbonSpotSentinel {
    fun onAdLoaded() {}
    fun onAdFailed() {}
}

class RibbonTeaser {
    private var googleBanner: AdView? = null
    private var facebookBanner: FbAdView? = null

    companion object {
        var bannerCounter = 0
    }

    fun presentBanner(
        activity: Activity,
        container: FrameLayout,
        type: RibbonSort = RibbonSort.AUTO,
        size: RibbonSpan = RibbonSpan.ADAPTIVE,
        isCollapsable: Boolean = false,
        shimmer: ShimmerFrameLayout? = null,
        observer: RibbonSpotSentinel? = null,
        customAdUnitId: String? = null,
        disableInternalFallback: Boolean = false
    ) {
        val pref = SpotsLocker.getInstance(activity)

        if (!isNetworkConnected(activity)|| !pref.getBoolean("IsAdsON") || !pref.getBoolean("BannerAds")) {
            hide(container)
            observer?.onAdFailed()
            return
        }

        if (bannerCounter < pref.getInt("BannerCounter")) {
            bannerCounter++
            hide(container)
            observer?.onAdFailed()
            return
        }
        bannerCounter = 0

        when (type) {
            RibbonSort.AUTO -> {
                when (SpotSort.fromString(pref.getString("IsAdType"))) {
                    SpotSort.GOOGLE -> fetchGoogleBanner(
                        activity,
                        container,
                        size,
                        isCollapsable,
                        shimmer,
                        observer,
                        customAdUnitId,
                        disableInternalFallback
                    )

                    SpotSort.FACEBOOK -> fetchFacebookBanner(
                        activity, container, shimmer, observer, disableInternalFallback
                    )

                    SpotSort.CUSTOM, SpotSort.UNKNOWN -> {
                        if (disableInternalFallback) {
                            observer?.onAdFailed()
                        } else {
                            BespokeSpotsBook().fetchCustomAd(
                                activity,
                                container,
                                BespokeSpotsBook.CustomAdType.BANNER
                            )
                        }
                    }
                }
            }

            RibbonSort.GOOGLE -> fetchGoogleBanner(
                activity,
                container,
                size,
                isCollapsable,
                shimmer,
                observer,
                customAdUnitId,
                disableInternalFallback
            )

            RibbonSort.FACEBOOK -> fetchFacebookBanner(
                activity, container, shimmer, observer, disableInternalFallback
            )

            RibbonSort.CUSTOM -> {
                if (disableInternalFallback) {
                    observer?.onAdFailed()
                } else {
                    BespokeSpotsBook().fetchCustomAd(
                        activity,
                        container,
                        BespokeSpotsBook.CustomAdType.BANNER
                    )
                }
            }
        }
    }

    private fun fetchGoogleBanner(
        activity: Activity,
        container: FrameLayout,
        size: RibbonSpan,
        isCollapsable: Boolean,
        shimmer: ShimmerFrameLayout? = null,
        observer: RibbonSpotSentinel?,
        customAdUnitId: String? = null,
        disableInternalFallback: Boolean = false
    ) {
        val pref = SpotsLocker.getInstance(activity)
        val adUnitId = customAdUnitId ?: pref.getString("googleBanner")

        if (adUnitId.isNullOrEmpty()) {
            if (disableInternalFallback) observer?.onAdFailed()
            else fallbackToFBOrCustom(activity, container, shimmer, observer)
            return
        }

        hide(container)

        shimmer?.startShimmer()
        shimmer?.visibility = View.VISIBLE
        container.removeAllViews()
        shimmer?.let { container.addView(it) }
        container.visibility = View.VISIBLE

        if (googleBanner == null) googleBanner = AdView(activity)
        googleBanner?.adUnitId = adUnitId

        if (isCollapsable) {
            googleBanner?.setAdSize(getAdSize(activity, container))
        } else {
            googleBanner?.setAdSize(getGoogleSize(activity, container, size, isCollapsable))

        }

        googleBanner?.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Log.i(
                    "BannerAds",
                    "Ad loaded. adView.isCollapsible() is ${googleBanner?.isCollapsible}.",
                )

                activity.recordKeyEvent("Banner_Load")
                activity.recordAdEvent("banner", "loaded")


                googleBanner!!.setOnPaidEventListener {
                    SpotIncomeGauge.recordPaidEvent(activity, it, "banner", googleBanner?.adUnitId, googleBanner?.responseInfo)
                }

                shimmer?.stopShimmer()
                shimmer?.visibility = View.GONE
                container.removeAllViews()

                container.addView(googleBanner)
                container.visibility = View.VISIBLE
                activity.recordAdEvent("banner", "shown")
                observer?.onAdLoaded()

            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                shimmer?.stopShimmer()
                shimmer?.visibility = View.GONE
                try {
                    activity.recordKeyEvent("Banner_fail_Load")
                    activity.recordAdLoadError("banner", error.code)
                } catch (_: Exception) {
                }
                Log.e("RibbonTeaser", "Google Banner Failed: ${error.message}")
                observer?.onAdFailed()
                if (!disableInternalFallback) {
                    fallbackToFBOrCustom(activity, container, shimmer, observer)
                }
            }

            override fun onAdClicked() {
                activity.recordKeyEvent("google_banner")
            }

            override fun onAdImpression() {
                activity.recordAdEvent("banner", "impression")
            }
        }

        val request = if (isCollapsable) {
            val extras = Bundle()
            extras.putString("collapsible", "bottom")
            AdRequest.Builder().addNetworkExtrasBundle(AdMobAdapter::class.java, extras).build()
        } else AdRequest.Builder().build()

        activity.recordAdEvent("banner", "req")
        googleBanner?.loadAd(request)
    }

    private fun getAdSize(
        activity: Activity,
        adContainer: FrameLayout
    ): com.google.android.gms.ads.AdSize {
        val display = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(display)

        val density = display.density
        val widthPixels =
            if (adContainer.width == 0) display.widthPixels.toFloat()
            else adContainer.width.toFloat()

        val adWidth = (widthPixels / density).toInt()
        return AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity, adWidth)
    }

    private fun getGoogleSize(
        activity: Activity,
        container: FrameLayout,
        size: RibbonSpan,
        isCollapsable: Boolean
    ): AdSize {
        return when (size) {
            RibbonSpan.NORMAL -> AdSize.BANNER
            RibbonSpan.INLINE -> AdSize.getCurrentOrientationInlineAdaptiveBannerAdSize(
                activity,
                getWidthDp(activity)
            )

            RibbonSpan.ADAPTIVE -> AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(
                activity,
                getWidthDp(activity)
            )
        }
    }

    private fun getWidthDp(activity: Activity): Int {
        val display = DisplayMetrics()
        activity.windowManager.defaultDisplay.getMetrics(display)
        return (display.widthPixels / display.density).toInt()
    }

    private fun fetchFacebookBanner(
        activity: Activity,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        observer: RibbonSpotSentinel?,
        disableInternalFallback: Boolean = false
    ) {
        val pref = SpotsLocker.getInstance(activity)
        val fbId = pref.getString("faceB_BannerAds")

        if (fbId.isNullOrEmpty()) {
            if (disableInternalFallback) {
                observer?.onAdFailed()
            } else {
                BespokeSpotsBook().fetchCustomAd(
                    activity,
                    container,
                    BespokeSpotsBook.CustomAdType.BANNER
                )
            }
            return
        }

        hide(container)

        shimmer?.startShimmer()
        shimmer?.visibility = View.VISIBLE
        container.removeAllViews()
        shimmer?.let { container.addView(it) }

        if (facebookBanner == null) facebookBanner =
            FbAdView(activity, fbId, com.facebook.ads.AdSize.BANNER_HEIGHT_50)

        activity.recordAdEvent("banner", "req", network = "facebook")
        facebookBanner?.loadAd(
            facebookBanner!!.buildLoadAdConfig()
                .withAdListener(object : com.facebook.ads.AdListener {
                    override fun onAdLoaded(ad: Ad?) {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        container.removeAllViews()

                        container.addView(facebookBanner)
                        container.visibility = View.VISIBLE
                        observer?.onAdLoaded()
                        activity.recordKeyEvent("facebook_banner_load")
                        activity.recordAdEvent("banner", "loaded", network = "facebook")
                    }

                    override fun onError(
                        ad: Ad?,
                        error: AdError?
                    ) {
                        shimmer?.stopShimmer()
                        shimmer?.visibility = View.GONE
                        observer?.onAdFailed()
                        Log.e("RibbonTeaser", "FB Banner Failed: ${error?.errorMessage}")
                        activity.recordAdLoadError("banner", error?.errorCode ?: -1, network = "facebook")
                        if (!disableInternalFallback) {
                            BespokeSpotsBook().fetchCustomAd(
                                activity,
                                container,
                                BespokeSpotsBook.CustomAdType.BANNER
                            )
                        }
                    }

                    override fun onAdClicked(ad: Ad?) {
                    }

                    override fun onLoggingImpression(ad: Ad?) {
                        activity.recordAdEvent("banner", "impression", network = "facebook")
                    }
                })
                .build()
        )
    }

    private fun fallbackToFBOrCustom(
        activity: Activity,
        container: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        observer: RibbonSpotSentinel?
    ) {
        val pref = SpotsLocker.getInstance(activity)
        if (pref.getBoolean("IsFail_FB")) {
            fetchFacebookBanner(activity, container, shimmer, observer)
        } else {
            shimmer?.startShimmer()
            shimmer?.visibility = View.VISIBLE
            BespokeSpotsBook().fetchCustomAd(
                activity,
                container,
                BespokeSpotsBook.CustomAdType.BANNER
            )
        }
    }

    private fun hide(container: FrameLayout) {
        container.removeAllViews()
        container.visibility = View.GONE
    }

}

