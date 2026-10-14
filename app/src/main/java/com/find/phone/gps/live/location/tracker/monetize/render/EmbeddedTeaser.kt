package com.find.phone.gps.live.location.tracker.monetize.render

import android.R.attr.visibility
import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort.*
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotTallyBook.nativeCounter
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotIncomeGauge
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.DisplayTeaserProfile
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdLoadError
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.databinding.MetaMidNativeBinding
import com.find.phone.gps.live.location.tracker.databinding.MetaNativeBinding
import com.find.phone.gps.live.location.tracker.databinding.GmsBignativeBinding
import com.find.phone.gps.live.location.tracker.databinding.GmsBignativetopBinding
import com.find.phone.gps.live.location.tracker.databinding.GmsMidnative2Binding
import com.find.phone.gps.live.location.tracker.databinding.GmsMidnativeBinding
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
import kotlin.collections.plusAssign
import kotlin.text.compareTo

class EmbeddedTeaser() {
    interface NativeAdObserver {
        fun onNativeAdLoaded()
        fun onNativeAdFailed()
    }

    companion object {
        private var nativeAd: NativeAd? = null

        @Volatile
        private var loadingSince = 0L

        private val waiters = mutableListOf<NativeAdObserver>()

        private fun settleWaiters(loaded: Boolean) {
            val pending = waiters.toList()
            waiters.clear()
            pending.forEach {
                runCatching { if (loaded) it.onNativeAdLoaded() else it.onNativeAdFailed() }
            }
        }

        private const val LOAD_TIMEOUT_MS = 60_000L

        private val isLoading: Boolean
            get() = loadingSince != 0L &&
                    System.currentTimeMillis() - loadingSince < LOAD_TIMEOUT_MS

        fun hasPreloadedNative(): Boolean = nativeAd != null
    }

    private fun wipeToEmpty(layout: FrameLayout, shimmer: ShimmerFrameLayout?) {
        runCatching {
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            layout.removeAllViews()
        }
    }

    private fun Activity.isActivityDestroyedCompat(): Boolean {
        if (isFinishing) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed
    }

    fun fetchNativeADs(context: Activity, observer: NativeAdObserver? = null) {
        val adsPreference = SpotsLocker.getInstance(context)

        if (!adsPreference.getBoolean("IsAdsON") || SpotSort.fromString(adsPreference.getString("IsAdType")) != SpotSort.GOOGLE) {
            observer?.onNativeAdFailed()
            return
        }

        if (!adsPreference.getBoolean("NativeAd")) {
            observer?.onNativeAdFailed()
            return
        }

        val adUnit = DisplayTeaserProfile.nativeAdUnitId(context)
        if (adUnit.isEmpty()) {
            observer?.onNativeAdFailed()
            return
        }

        if (isLoading) {
            Log.d("NativeAds", "load already in flight — waiting on it")
            observer?.let { waiters += it }
            return
        }
        loadingSince = System.currentTimeMillis()
        val adLoader =
            AdLoader.Builder(context, adUnit)
                .forNativeAd { nativeAds ->

                    nativeAd?.destroy()
                    nativeAd = nativeAds
                    loadingSince = 0L

                    settleWaiters(loaded = true)

                    if (context.isActivityDestroyedCompat()) {
                        return@forNativeAd
                    }

                    observer?.onNativeAdLoaded()
                    try {
                        context.recordKeyEvent("NativeAds_load")
                        context.recordAdEvent("native", "loaded")
                    } catch (e: Exception) {
                    }

                    Log.e("NativeAds", "Google Load: nativeAd")
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        super.onAdFailedToLoad(loadAdError)
                        loadingSince = 0L
                        settleWaiters(loaded = false)
                        if (context.isActivityDestroyedCompat()) return
                        observer?.onNativeAdFailed()
                        Log.e(
                            "NativeAds",
                            "Google onAdFailedToLoad:nativeAd ${loadAdError.message}"
                        )
                        try {
                            context.recordKeyEvent("NativeAds_Fail")
                            context.recordAdLoadError("native", loadAdError.code)
                        } catch (e: Exception) {
                        }
                        nativeAd = null
                    }

                    override fun onAdImpression() {
                        super.onAdImpression()
                        runCatching { context.recordAdEvent("native", "impression") }
                    }
                })
                .withNativeAdOptions(NativeAdOptions.Builder().build())
                .build()

        context.recordAdEvent("native", "req")
        adLoader.loadAd(AdRequest.Builder().build())
    }

    fun presentBigNative(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        isButtonTop: Boolean? = false,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {
        val adsPreference = SpotsLocker.getInstance(context)

        if (context.isFinishing || context.isDestroyed) return

        if (!isNetworkConnected(context)
            || !adsPreference.getBoolean("IsAdsON")
            || !adsPreference.getBoolean("NativeAd")

            || !DisplayTeaserProfile.resolve(context, context.javaClass.simpleName).show
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }

        if (nativeCounter < adsPreference.getInt("NativeCounter")) {
            nativeCounter++
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }
        nativeCounter = 0

        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true
        imageView?.gone()
        ln?.gone()

        when (SpotSort.fromString(adsPreference.getString("IsAdType"))) {
            SpotSort.GOOGLE -> {
                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post

                        if (nativeAd != null) {
                            val rootView: View = if (isButtonTop == true) {
                                val binding =
                                    GmsBignativetopBinding.inflate(context.layoutInflater)

                                bigNativeTemplateTop(nativeAd!!, binding, context)
                                binding.root
                            } else {
                                val binding =
                                    GmsBignativeBinding.inflate(context.layoutInflater)

                                bigNativeTemplate(nativeAd!!, binding, context)
                                binding.root
                            }

                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(rootView)

                            context.recordKeyEvent("NativeAds_showBigNative_Google")

                            if (BuildConfig.DEBUG) {
                                SpotIncomeGauge.simulateDebugRevenue(context)
                            }

                            nativeAd?.setOnPaidEventListener {
                                SpotIncomeGauge.recordPaidEvent(context, it)
                            }

                            nativeAd = null
                            fetchNativeADs(context)

                            return@post
                        }

                        if (adsPreference.getBoolean("IsFail_FB")) {
                            presentFBNativeFallback(context, layout, imageView, shimmer)
                        } else {
                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false

                            BespokeSpotsBook().fetchCustomAd(
                                context,
                                layout,
                                BespokeSpotsBook.CustomAdType.BIG_NATIVE,
                                imageView
                            )
                        }

                    } catch (e: Exception) {
                        Log.e("NativeAds", "Google BigNative crash", e)
                        wipeToEmpty(layout, shimmer)
                    }
                }
            }

            SpotSort.FACEBOOK -> {
                presentFBNativeFallback(context, layout, imageView)
            }

            SpotSort.UNKNOWN, SpotSort.CUSTOM -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                BespokeSpotsBook().fetchCustomAd(
                    context,
                    layout,
                    BespokeSpotsBook.CustomAdType.BIG_NATIVE,
                    imageView
                )
            }
        }
    }

    private fun bigNativeTemplate(
        nativeAd: NativeAd,
        binding: GmsBignativeBinding,
        context: Activity
    ) {
        binding.apply {
            binding.mainNativeadView.mediaView = adMedia
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline
            binding.mainNativeadView.mediaView?.mediaContent = nativeAd.mediaContent

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }

            EmbeddedSpotGuise.bind(binding.mainNativeadView, nativeAd)
            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }

    private fun bigNativeTemplateTop(
        nativeAd: NativeAd,
        binding: GmsBignativetopBinding,
        context: Activity
    ) {
        binding.apply {
            binding.mainNativeadView.mediaView = adMedia
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline
            binding.mainNativeadView.mediaView?.mediaContent = nativeAd.mediaContent

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }

            EmbeddedSpotGuise.bind(binding.mainNativeadView, nativeAd)
            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }

    private fun presentFBNativeFallback(
        context: Activity,
        layout: FrameLayout,
        imageView: ImageView? = null,
        shimmer: ShimmerFrameLayout? = null
    ) {
        val adsPref = SpotsLocker.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeAds")

        if (fbId.isNullOrEmpty()) {
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            BespokeSpotsBook().fetchCustomAd(
                context,
                layout,
                BespokeSpotsBook.CustomAdType.BIG_NATIVE,
                imageView
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)

        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        layout.findFocus()?.clearFocus()
                        layout.removeAllViews()
                        expandFbNativeAd(fbNative, layout, context)
                    }
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        layout.findFocus()?.clearFocus()
                        BespokeSpotsBook().fetchCustomAd(
                            context,
                            layout,
                            BespokeSpotsBook.CustomAdType.BIG_NATIVE,
                            imageView
                        )
                    }
                }

                override fun onAdLoaded(ad: Ad?) {
                    if (fbNative !== ad) return
                    context.recordKeyEvent("NativeAds_showBigNative_FB_Load")
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {
                    runCatching { context.recordAdEvent("native", "impression", network = "facebook") }
                }
            }).build()
        )
    }

    fun expandFbNativeAd(
        nativeAd: com.facebook.ads.NativeAd,
        viewGroup: ViewGroup,
        activity: Activity,
        adSize: String? = null
    ) {
        viewGroup.isVisible = true

        nativeAd.unregisterView()

        val binding = MetaNativeBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        val adOptionsView = AdOptionsView(activity, nativeAd, binding.nativview)
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(adOptionsView, 0)
        val bgColor = SpotsLocker.getInstance(activity).getString("NativeBgColor")
        val btnColor = SpotsLocker.getInstance(activity).getString("NativebtnColor")
        val txtColor =
            SpotsLocker.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            SpotsLocker.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdBody.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(safeParseColor(bgColor, "#FFFFFF"))

        SpotButtonTheme.apply(binding.nativeAdCallToAction as TextView)
        (binding.nativeAdCallToAction as TextView).apply {
            setTextColor(Color.parseColor(btntxtColor))
        }

        binding.nativeAdTitle.text = nativeAd.advertiserName
        binding.nativeAdBody.text = nativeAd.adBodyText
        binding.nativeAdSocialContext.text = nativeAd.adSocialContext
        binding.nativeAdSponsoredLabel.text = nativeAd.sponsoredTranslation

        if (nativeAd.hasCallToAction()) {
            binding.nativeAdCallToAction.text = nativeAd.adCallToAction
            binding.nativeAdCallToAction.isVisible = true
        } else {
            binding.nativeAdCallToAction.isVisible = false
        }

        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeAdMedia, binding.nativeAdIcon, clickableViews
        )
    }

    fun presentMidNative(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {
        val adsPref = SpotsLocker.getInstance(context)

        if (context.isFinishing || context.isDestroyed) return

        if (!isNetworkConnected(context)
            || !adsPref.getBoolean("IsAdsON")
            || !adsPref.getBoolean("NativeAd")
            || !DisplayTeaserProfile.resolve(context, context.javaClass.simpleName).show
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }

        if (nativeCounter < adsPref.getInt("MidNativeCounter")) {
            nativeCounter += 1
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }
        nativeCounter = 0

        shimmer?.startShimmer()
        shimmer?.isVisible = true
        imageView?.gone()
        ln?.gone()
        layout.visible()

        when (SpotSort.fromString(adsPref.getString("IsAdType"))) {
            SpotSort.GOOGLE -> {
                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post
                        if (nativeAd != null) {
                            val binding = GmsMidnativeBinding.inflate(context.layoutInflater)
                            MidNativeTemplate(
                                nativeAd!!,
                                binding,
                                context
                            )

                            layout.removeAllViews()

                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(binding.root)

                            context.recordKeyEvent("NativeAds_showMidNative_Google")

                            if (BuildConfig.DEBUG) SpotIncomeGauge.simulateDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                SpotIncomeGauge.recordPaidEvent(context, it)
                            }

                            nativeAd = null
                            fetchNativeADs(context)
                            return@post
                        }

                        if (adsPref.getBoolean("IsFail_FB")) {
                            presentMidFBNativeFallback(context, layout, shimmer, imageView)
                        } else {
                            layout.removeAllViews()
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            BespokeSpotsBook().fetchCustomAd(
                                context,
                                layout,
                                BespokeSpotsBook.CustomAdType.MID_NATIVE
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("MidNativeAds", "Google MidNative failed: ${e.message}")
                        wipeToEmpty(layout, shimmer)
                    }
                }

            }

            SpotSort.FACEBOOK -> {
                presentMidFBNativeFallback(context, layout, shimmer, imageView)
            }

            SpotSort.CUSTOM, SpotSort.UNKNOWN -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                BespokeSpotsBook().fetchCustomAd(
                    context,
                    layout,
                    BespokeSpotsBook.CustomAdType.MID_NATIVE
                )
            }
        }
    }

    private fun presentMidFBNativeFallback(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        imageView: ImageView? = null
    ) {
        val adsPref = SpotsLocker.getInstance(context)
        val fbId = adsPref.getString("faceB_NativeAds")

        if (fbId.isNullOrEmpty()) {
            layout.removeAllViews()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            BespokeSpotsBook().fetchCustomAd(
                context,
                layout,
                BespokeSpotsBook.CustomAdType.MID_NATIVE
            )
            return
        }

        val fbNative = com.facebook.ads.NativeAd(context, fbId)
        fbNative.loadAd(
            fbNative.buildLoadAdConfig().withAdListener(object : NativeAdListener {
                override fun onMediaDownloaded(ad: Ad?) {
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        layout.findFocus()?.clearFocus()
                        layout.removeAllViews()
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        expandFbMidNativeAd(fbNative, layout, context)
                        context.recordKeyEvent("NativeAds_showMid_FB")
                    }
                }

                override fun onError(ad: Ad?, adError: AdError?) {
                    if (context.isActivityDestroyedCompat()) return
                    layout.post {
                        if (context.isActivityDestroyedCompat()) return@post
                        Log.e("NativeAds", "FB MidNative failed: ${adError?.errorMessage}")
                        layout.findFocus()?.clearFocus()
                        layout.removeAllViews()
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        BespokeSpotsBook().fetchCustomAd(
                            context,
                            layout,
                            BespokeSpotsBook.CustomAdType.MID_NATIVE
                        )
                    }
                }

                override fun onAdLoaded(ad: Ad?) {
                    if (fbNative !== ad) return
                    fbNative.downloadMedia()
                }

                override fun onAdClicked(ad: Ad?) {}
                override fun onLoggingImpression(ad: Ad?) {
                    runCatching { context.recordAdEvent("native", "impression", network = "facebook") }
                }
            }).build()
        )
    }

    fun expandFbMidNativeAd(
        nativeAd: com.facebook.ads.NativeAd,
        viewGroup: ViewGroup,
        activity: Activity,
        adSize: String? = null
    ) {
        viewGroup.isVisible = true

        nativeAd.unregisterView()

        val binding = MetaMidNativeBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        val adOptionsView = AdOptionsView(activity, nativeAd, binding.nativview)
        binding.adChoicesContainer.removeAllViews()
        binding.adChoicesContainer.addView(adOptionsView, 0)

        val bgColor = SpotsLocker.getInstance(activity).getString("NativeBgColor")
        val btnColor = SpotsLocker.getInstance(activity).getString("NativebtnColor")
        val txtColor =
            SpotsLocker.getInstance(activity).getString("NativetxtColor") ?: "#000000"
        val btntxtColor =
            SpotsLocker.getInstance(activity).getString("NativebtntxtColor") ?: "#000000"

        binding.nativeAdTitle.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSocialContext.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdSponsoredLabel.setTextColor(Color.parseColor(txtColor))
        binding.nativeAdBody.setTextColor(Color.parseColor(txtColor))

        binding.nativview.backgroundTintList =
            ColorStateList.valueOf(safeParseColor(bgColor, "#FFFFFF"))

        SpotButtonTheme.apply(binding.nativeAdCallToAction as TextView)
        (binding.nativeAdCallToAction as TextView).apply {
            setTextColor(Color.parseColor(btntxtColor))
        }

        binding.nativeAdTitle.text = nativeAd.advertiserName
        binding.nativeAdBody.text = nativeAd.adBodyText
        binding.nativeAdSocialContext.text = nativeAd.adSocialContext
        binding.nativeAdSponsoredLabel.text = nativeAd.sponsoredTranslation

        if (nativeAd.hasCallToAction()) {
            binding.nativeAdCallToAction.text = nativeAd.adCallToAction
            binding.nativeAdCallToAction.isVisible = true
        } else {
            binding.nativeAdCallToAction.isVisible = false
        }

        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeAdIcon, clickableViews
        )
    }

    private fun MidNativeTemplate(
        nativeAd: NativeAd,
        binding: GmsMidnativeBinding,
        context: Activity
    ) {
        binding.apply {
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }

            EmbeddedSpotGuise.bind(binding.mainNativeadView, nativeAd)
            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }

    fun View.visible() {
        this.visibility = View.VISIBLE
    }

    fun View.invisible() {
        this.visibility = View.GONE
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

    fun presentMidNative2(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {
        val adsPref = SpotsLocker.getInstance(context)

        if (context.isFinishing || context.isDestroyed) return

        if (!isNetworkConnected(context) || !adsPref.getBoolean("IsAdsON") || !adsPref.getBoolean("NativeAd")
            || !DisplayTeaserProfile.resolve(context, context.javaClass.simpleName).show
        ) {
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }

        if (nativeCounter < adsPref.getInt("MidNativeCounter")) {
            nativeCounter += 1
            layout.removeAllViews()
            layout.invisible()
            shimmer?.stopShimmer()
            shimmer?.isVisible = false
            imageView?.visible()
            ln?.visible()
            return
        }
        nativeCounter = 0

        shimmer?.startShimmer()
        shimmer?.isVisible = true
        imageView?.gone()
        ln?.gone()
        layout.visible()
        when (SpotSort.fromString(adsPref.getString("IsAdType"))) {
            GOOGLE -> {
                layout.post {
                    try {
                        if (context.isFinishing || context.isDestroyed) return@post
                        if (Companion.nativeAd != null) {
                            val binding = GmsMidnative2Binding.inflate(context.layoutInflater)
                            MidNativeTemplate2(
                                Companion.nativeAd!!,
                                binding,
                                context
                            )

                            layout.removeAllViews()

                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(binding.root)

                            context.recordKeyEvent("NativeAds_showMidNative2_Google")

                            if (BuildConfig.DEBUG) SpotIncomeGauge.simulateDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                SpotIncomeGauge.recordPaidEvent(context, it)
                            }

                            nativeAd = null
                            fetchNativeADs(context)
                            return@post
                        }

                        layout.removeAllViews()
                        shimmer?.stopShimmer()
                        shimmer?.isVisible = false
                        BespokeSpotsBook().fetchCustomAd(
                            context,
                            layout,
                            BespokeSpotsBook.CustomAdType.MID_NATIVE
                        )
                    } catch (e: Exception) {
                        Log.e("MidNativeAds", "Google MidNative failed: ${e.message}")
                        wipeToEmpty(layout, shimmer)
                    }
                }

            }

            CUSTOM, UNKNOWN -> {
                layout.removeAllViews()
                shimmer?.stopShimmer()
                shimmer?.isVisible = false
                BespokeSpotsBook().fetchCustomAd(
                    context,
                    layout,
                    BespokeSpotsBook.CustomAdType.MID_NATIVE
                )
            }

            SpotSort.FACEBOOK -> {
                presentMidFBNativeFallback(context, layout, shimmer, imageView)
            }
        }
    }

    private fun MidNativeTemplate2(
        nativeAd: NativeAd,
        binding: GmsMidnative2Binding,
        context: Activity
    ) {
        binding.apply {
            binding.mainNativeadView.mediaView = adMedia
            binding.mainNativeadView.headlineView = adHeadline
            binding.mainNativeadView.bodyView = adBody
            binding.mainNativeadView.callToActionView = adCallToAction
            binding.mainNativeadView.iconView = adAppIcon

            (binding.mainNativeadView.headlineView as TextView).text = nativeAd.headline
            binding.mainNativeadView.mediaView?.mediaContent = nativeAd.mediaContent

            binding.mainNativeadView.bodyView?.apply {
                visibility = if (nativeAd.body == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.bodyView as TextView).text = nativeAd.body
            }

            binding.mainNativeadView.iconView?.apply {
                visibility = if (nativeAd.icon == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.iconView as ImageView).setImageDrawable(nativeAd.icon?.drawable)
            }

            binding.mainNativeadView.callToActionView?.apply {
                visibility = if (nativeAd.callToAction == null) View.GONE else View.VISIBLE
                (binding.mainNativeadView.callToActionView as TextView).text = nativeAd.callToAction
            }

            EmbeddedSpotGuise.bind(binding.mainNativeadView, nativeAd)
            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }

}
