package com.find.phone.gps.live.location.tracker.monetize.render

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotTallyBook.nativeBannerCounter
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotIncomeGauge
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.DisplayTeaserProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.TAG_EVENT
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdLoadError
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.databinding.MetaNativeBannerBinding
import com.find.phone.gps.live.location.tracker.databinding.GmsNativebannerBinding
import com.facebook.ads.Ad
import com.facebook.ads.AdError
import com.facebook.ads.AdOptionsView
import com.facebook.ads.NativeAdListener
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdOptions

class EmbeddedTeaserRibbon {
    companion object {
        private var nativeAdBanner: NativeAd? = null

        fun hasPreloadedNativeBanner(): Boolean = nativeAdBanner != null
    }

    fun fetchNativeBannerAds(activity: Activity) {
        val adsPref = SpotsLocker.getInstance(activity)
        if (!adsPref.getBoolean("IsAdsON")) return

        if (!adsPref.getBoolean("NativeBanner")) return

        when (SpotSort.fromString(adsPref.getString("IsAdType"))) {
            SpotSort.GOOGLE -> {
                val adUnitId = adsPref.getString("googleNative") ?: return

                val adLoader = AdLoader.Builder(activity, adUnitId).forNativeAd { ad ->
                    nativeAdBanner?.destroy()
                    nativeAdBanner = ad
                    try {
                        activity.recordKeyEvent("NativeBanner_Load")
                        activity.recordAdEvent("native_banner", "loaded")
                    } catch (e: Exception) {
                    }

                    Log.d("EmbeddedTeaserRibbon", "Ad loaded successfully")
                }.withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(error: LoadAdError) {
                        Log.e("EmbeddedTeaserRibbon", "Ad failed to load: ${error.message}")
                        nativeAdBanner = null

                        try {
                            activity.recordKeyEvent("NativeBanner_fail")
                            activity.recordAdLoadError("native_banner", error.code)
                        } catch (e: Exception) {
                        }
                    }

                    override fun onAdImpression() {
                        runCatching { activity.recordAdEvent("native_banner", "impression") }
                    }
                }).withNativeAdOptions(NativeAdOptions.Builder().build()).build()

                activity.recordAdEvent("native_banner", "req")
                adLoader.loadAd(AdRequest.Builder().build())
            }

            SpotSort.FACEBOOK -> {
                Log.e(TAG_EVENT, "AdType FaceBook NOt Pre load Google Native")
                return
            }

            SpotSort.UNKNOWN, SpotSort.CUSTOM -> {
                Log.e(TAG_EVENT, "AdType Custom NOt Pre load Google Native")
                return
            }
        }

    }

    fun presentNativeBannerNative(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        Log.e("NativeAds", "Google Show: nativeAd")
        val adsPref = SpotsLocker.getInstance(context)

        if (context.isFinishing || context.isDestroyed) return

        if (!isNetworkConnected(context)
            || !adsPref.getBoolean("IsAdsON")
            || !adsPref.getBoolean("NativeBanner")

            || !DisplayTeaserProfile.resolve(context, context.javaClass.simpleName).show
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            return
        }

        if (nativeBannerCounter < adsPref.getInt("MidNativeCounter")) {
            nativeBannerCounter += 1
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            return
        }

        nativeBannerCounter = 0

        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true

        when (SpotSort.fromString(adsPref.getString("IsAdType"))) {
            SpotSort.GOOGLE -> {
                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post
                        if (nativeAdBanner != null) {
                            val binding = GmsNativebannerBinding.inflate(context.layoutInflater)
                            wireGoogleNativeAd(nativeAdBanner!!, binding, context)

                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false

                            layout.addView(binding.root)
                            nativeAdBanner = null
                            fetchNativeBannerAds(context)
                            return@post
                        } else {
                            if (adsPref.getBoolean("IsFail_FB")) {
                                presentFBNativeBannerFallback(context, layout, shimmer)
                            } else {
                                layout.removeAllViews()
                                shimmer?.stopShimmer()
                                shimmer?.isVisible = false
                                BespokeSpotsBook().fetchCustomAd(
                                    context, layout, BespokeSpotsBook.CustomAdType.BANNER
                                )
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("EmbeddedTeaserRibbon", "Google NativeBanner failed: ${e.message}")

                        runCatching {
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.removeAllViews()
                        }
                    }
                }
            }

            SpotSort.FACEBOOK -> {
                presentFBNativeBannerFallback(context, layout, shimmer)
            }

            SpotSort.UNKNOWN, SpotSort.CUSTOM -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                BespokeSpotsBook().fetchCustomAd(
                    context, layout, BespokeSpotsBook.CustomAdType.BANNER
                )
            }
        }
    }

    private fun wireGoogleNativeAd(
        nativeAd: NativeAd, binding: GmsNativebannerBinding, context: Activity
    ) {
        context.recordKeyEvent("NativeBanner_Show_Google")

        if (BuildConfig.DEBUG) SpotIncomeGauge.simulateDebugRevenue(context)

        nativeAd.setOnPaidEventListener {
            SpotIncomeGauge.recordPaidEvent(context, it)
        }

        binding.apply {
            mainNativeadView.headlineView = adHeadline
            mainNativeadView.bodyView = adBody
            mainNativeadView.callToActionView = adCallToAction
            mainNativeadView.iconView = adAppIcon

            (adHeadline as TextView).text = nativeAd.headline

            if (nativeAd.body != null) {
                adBody.visibility = View.VISIBLE
                (adBody as TextView).text = nativeAd.body
            } else {
                adBody.visibility = View.GONE
            }

            if (nativeAd.icon != null) {
                adAppIcon.visibility = View.VISIBLE
                (adAppIcon as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            } else {
                adAppIcon.visibility = View.GONE
            }

            if (nativeAd.callToAction != null) {
                adCallToAction.visibility = View.VISIBLE
                (adCallToAction as TextView).text = nativeAd.callToAction

            } else {
                adCallToAction.visibility = View.GONE
            }

            EmbeddedSpotGuise.bind(mainNativeadView, nativeAd)
            mainNativeadView.setNativeAd(nativeAd)
        }
    }

    fun safeParseColor(colorString: String?, defaultColor: String): Int {
        return try {
            if (!colorString.isNullOrBlank()) {
                Color.parseColor(colorString)
            } else {
                Color.parseColor(defaultColor)
            }
        } catch (e: IllegalArgumentException) {
            Color.parseColor(defaultColor)
        }
    }

    private fun presentFBNativeBannerFallback(
        context: Activity, layout: FrameLayout, shimmer: ShimmerFrameLayout? = null
    ) {
        val adsPref = SpotsLocker.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeBannerAds")

        if (fbId.isNullOrEmpty()) {
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            BespokeSpotsBook().fetchCustomAd(
                context, layout, BespokeSpotsBook.CustomAdType.BANNER
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)
        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    layout.removeAllViews()
                    expandFbNativeBAnnerAd(fbNative, layout, context)
                    context.recordKeyEvent("NativeBAnner_FB")
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    shimmer?.stopShimmer()
                    shimmer?.isVisible = false
                    Log.e("NativeAds", "FB MidNative failed: ${adError?.errorMessage}")
                    BespokeSpotsBook().fetchCustomAd(
                        context, layout, BespokeSpotsBook.CustomAdType.BANNER
                    )
                }

                override fun onAdLoaded(ad: Ad?) {
                    if (fbNative !== ad) return
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {
                    runCatching { context.recordAdEvent("native_banner", "impression", network = "facebook") }
                }
            }).build()
        )
    }

    fun expandFbNativeBAnnerAd(
        nativeAd: com.facebook.ads.NativeAd,
        viewGroup: ViewGroup,
        activity: Activity,
        adSize: String? = null
    ) {
        viewGroup.isVisible = true

        nativeAd.unregisterView()

        val binding =
            MetaNativeBannerBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        val adOptionsView = AdOptionsView(activity, nativeAd, binding.nativview)
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(adOptionsView, 0)

        binding.nativeAdTitle.text = nativeAd.advertiserName
        binding.nativeAdSocialContext.text = nativeAd.adSocialContext
        binding.nativeAdSponsoredLabel.text = nativeAd.sponsoredTranslation

        val bgColor = SpotsLocker.getInstance(activity).getString("NativeBgColor")
        val btnColor = SpotsLocker.getInstance(activity).getString("NativebtnColor")
        val txtColor = SpotsLocker.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            SpotsLocker.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(safeParseColor(bgColor, "#FFFFFF"))

        SpotButtonTheme.apply(binding.nativeAdCallToAction as TextView)
        (binding.nativeAdCallToAction as TextView).apply {
            setTextColor(Color.parseColor(btntxtColor))
        }

        if (nativeAd.hasCallToAction()) {
            binding.nativeAdCallToAction.text = nativeAd.adCallToAction
            binding.nativeAdCallToAction.isVisible = true
        } else {
            binding.nativeAdCallToAction.isVisible = false
        }

        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeIconView, clickableViews
        )
    }

}
