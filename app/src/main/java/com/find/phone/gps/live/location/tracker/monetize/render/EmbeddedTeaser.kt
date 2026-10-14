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

        /**
         * A load is in flight. The pool is one static slot and every `show*` kicks a refill,
         * so without this a surface that opens twice in a row (the app drawer, the left panel)
         * stacks concurrent AdLoader requests that each overwrite — and destroy — the last
         * one's result.
         */
        @Volatile
        private var loadingSince = 0L

        /**
         * Observers that asked while a load was already in flight. Without them a screen that
         * opens mid-refill (onboarding steps shown seconds apart all share this one slot) was
         * turned away and never told when the ad landed, so its frame stayed empty.
         */
        private val waiters = mutableListOf<NativeAdObserver>()

        private fun settleWaiters(loaded: Boolean) {
            val pending = waiters.toList()
            waiters.clear()
            pending.forEach {
                runCatching { if (loaded) it.onNativeAdLoaded() else it.onNativeAdFailed() }
            }
        }

        /**
         * How long a request may be considered in flight. AdLoader always answers one of its
         * two callbacks, but a latched flag here would kill native ads process-wide, so the
         * guard expires rather than trusting that.
         */
        private const val LOAD_TIMEOUT_MS = 60_000L

        private val isLoading: Boolean
            get() = loadingSince != 0L &&
                    System.currentTimeMillis() - loadingSince < LOAD_TIMEOUT_MS

        /**
         * Whether a native is in hand right now.
         *
         * Callers that re-render an already-filled frame need this: rendering consumes the
         * pooled ad, so asking again before the refill lands would drop through to the
         * fallback path and replace a good ad with a custom one. See
         * [com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile.renewSlot].
         */
        fun hasPreloadedNative(): Boolean = nativeAd != null
    }

    /**
     * Takes the shimmer down and clears whatever half-built view is in the frame.
     *
     * Every render path below builds the ad view FIRST and stops the shimmer only once that
     * has succeeded — so anything thrown while binding a template (a null asset, a recycled
     * NativeAd, an inflate failure) used to land in a catch that only logged, leaving the
     * shimmer running over an empty frame for as long as the screen lived. The launcher's
     * side panels are where that shows up worst: they are never recreated, so the placeholder
     * stays until the process dies.
     */
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
        // Ads toggle and type check
        if (!adsPreference.getBoolean("IsAdsON") || SpotSort.fromString(adsPreference.getString("IsAdType")) != SpotSort.GOOGLE) {
            observer?.onNativeAdFailed()
            return
        }
        // Firebase "NativeBanner" master switch — disable native ad loading
        if (!adsPreference.getBoolean("NativeAd")) {
            observer?.onNativeAdFailed()
            return
        }
        // Screen-wise aware: resolves to ScreenAds.default native id, or the
        // global googleNative when screen_wise_ad is off.
        val adUnit = DisplayTeaserProfile.nativeAdUnitId(context)
        if (adUnit.isEmpty()) {
            observer?.onNativeAdFailed()
            return
        }
        // One request at a time — see [isLoading]. A caller waiting on the result is told when
        // the in-flight one settles instead of being dropped.
        if (isLoading) {
            Log.d("NativeAds", "load already in flight — waiting on it")
            observer?.let { waiters += it }
            return
        }
        loadingSince = System.currentTimeMillis()
        val adLoader =
            AdLoader.Builder(context, adUnit)
                .forNativeAd { nativeAds ->
                    // Always cache the ad — even when the activity that
                    // triggered this load is destroyed by the time the ad
                    // lands. The cache is a static field; it survives
                    // activity transitions and can be shown by the next
                    // caller. Previously this branch destroyed the ad,
                    // which is why "1 load → then nothing" was the symptom:
                    // the splash refill landed after splash was gone, got
                    // destroyed, and every subsequent show found null.
                    nativeAd?.destroy()
                    nativeAd = nativeAds
                    loadingSince = 0L
                    // Screens that asked mid-load check their own lifecycle in their callback.
                    settleWaiters(loaded = true)

                    if (context.isActivityDestroyedCompat()) {
                        // Originating activity is gone — cache only, skip
                        // observer/log calls that need a live context.
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

    //================================================================================BigNAtive
    fun presentBigNative(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        isButtonTop: Boolean? = false,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {

        val adsPreference = SpotsLocker.getInstance(context)

        // 🔥 1. Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return

        // 🔥 2. Network + Ads ON + EmbeddedTeaserRibbon master switch
        if (!isNetworkConnected(context)
            || !adsPreference.getBoolean("IsAdsON")
            || !adsPreference.getBoolean("NativeAd")
            // Per-screen visibility: a screen whose ScreenAds entry is off gets no native.
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

        // 🔥 3. Counter logic
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


        // 🔥 4. UI loading state
        layout.visible()
        shimmer?.startShimmer()
        shimmer?.isVisible = true
        imageView?.gone()
        ln?.gone()

        when (SpotSort.fromString(adsPreference.getString("IsAdType"))) {
            SpotSort.GOOGLE -> {
                layout.post {  // 🔥 UI thread safe
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

                        // 🔥 GOOGLE FAIL → fallback
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


            // Remote Config ad colours (with the layout's own theme as the fallback), the rating line
            // and the CTA glow — the reference app's EmbeddedSpotGuise.
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


            // Remote Config ad colours (with the layout's own theme as the fallback), the rating line
            // and the CTA glow — the reference app's EmbeddedSpotGuise.
            EmbeddedSpotGuise.bind(binding.mainNativeadView, nativeAd)
            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }


    // Helper function for FB fallback
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
        // ✅ Make sure container is visible
        viewGroup.isVisible = true

        // Unregister any old ad view
        nativeAd.unregisterView()

        // ✅ Inflate layout with ViewBinding
        val binding = MetaNativeBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        // Clear old views and add new ad view
        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        // ✅ Add AdChoicesView
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
        // ✅ Bind ad data to views
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

        // ✅ Register clickable views
        val clickableViews = listOf(binding.nativeAdTitle, binding.nativeAdCallToAction)

        nativeAd.registerViewForInteraction(
            binding.root, binding.nativeAdMedia, binding.nativeAdIcon, clickableViews
        )
    }

    //================================================================================BigNAtive
//================================================================================MidNAtive
    fun presentMidNative(
        context: Activity,
        layout: FrameLayout,
        shimmer: ShimmerFrameLayout? = null,
        imageView: ImageView? = null,
        ln: LinearLayout? = null
    ) {
        val adsPref = SpotsLocker.getInstance(context)
        // 🔥 1. Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return

        // Check network & ad toggle + EmbeddedTeaserRibbon master switch
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

        // MidNative counter logic
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
                            // Stop shimmer before adding real ad
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(binding.root)

                            // Log load
                            context.recordKeyEvent("NativeAds_showMidNative_Google")

                            if (BuildConfig.DEBUG) SpotIncomeGauge.simulateDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                SpotIncomeGauge.recordPaidEvent(context, it)
                            }

                            nativeAd = null
                            fetchNativeADs(context) // preload next Google ad
                            return@post
                        }
                        // Google failed → fallback
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
        // ✅ Make sure container is visible
        viewGroup.isVisible = true

        // Unregister any old ad view
        nativeAd.unregisterView()

        // ✅ Inflate layout with ViewBinding
        val binding = MetaMidNativeBinding.inflate(LayoutInflater.from(activity), viewGroup, false)

        // Clear old views and add new ad view
        viewGroup.removeAllViews()
        viewGroup.addView(binding.root)

        // ✅ Add AdChoicesView
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

        // ✅ Bind ad data to views
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

        // ✅ Register clickable views
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

            // Remote Config ad colours (with the layout's own theme as the fallback), the rating line
            // and the CTA glow — the reference app's EmbeddedSpotGuise.
            EmbeddedSpotGuise.bind(binding.mainNativeadView, nativeAd)
            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }

    // Extension helpers
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
        // 🔥 1. Activity lifecycle safety
        if (context.isFinishing || context.isDestroyed) return

        // Check network & ad toggle
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

        // MidNative counter logic
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
                            // Stop shimmer before adding real ad
                            shimmer?.stopShimmer()
                            shimmer?.isVisible = false
                            layout.addView(binding.root)

                            // Log load
                            context.recordKeyEvent("NativeAds_showMidNative2_Google")

                            if (BuildConfig.DEBUG) SpotIncomeGauge.simulateDebugRevenue(context)

                            nativeAd!!.setOnPaidEventListener {
                                SpotIncomeGauge.recordPaidEvent(context, it)
                            }

                            nativeAd = null
                            fetchNativeADs(context) // preload next Google ad
                            return@post
                        }
                        // Google failed → fallback
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


            // Remote Config ad colours (with the layout's own theme as the fallback), the rating line
            // and the CTA glow — the reference app's EmbeddedSpotGuise.
            EmbeddedSpotGuise.bind(binding.mainNativeadView, nativeAd)
            binding.mainNativeadView.setNativeAd(nativeAd)
        }
    }


}
