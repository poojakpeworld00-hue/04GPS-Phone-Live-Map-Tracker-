package com.find.phone.gps.live.location.tracker.monetize.render.fullscreen

import android.app.Activity
import android.content.Context
import android.util.Log
import com.facebook.ads.Ad
import com.facebook.ads.InterstitialAdListener
import com.google.android.gms.ads.*
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotTallyBook.interBackCounter
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSeatSpots
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordAdLoadError
import com.find.phone.gps.live.location.tracker.monetize.render.isNetworkConnected

class TakeoverRear {
    companion object {
        private var _isInterBAckShow: Boolean = false
        var isInterBAckShow: Boolean
            get() = _isInterBAckShow
            set(value) {
                _isInterBAckShow = value
            }
        private var googleInterBack: InterstitialAd? = null
    }

    fun fetchBackInterAds(activity: Activity) {
        val pref = SpotsLocker.getInstance(activity)

        if (!pref.getBoolean("IsAdsON")) {
            activity.safeLog("BackLoad:AdsOFF")
            return
        }

        if (!pref.getBoolean("InterAds")) {
            activity.safeLog("BackLoad:InterAdsDisabled")
            return
        }

        if (!pref.getBoolean("IsBack")) {
            activity.safeLog("BackLoad:BackAdsOFF")
            return
        }

        val id = pref.getString("googleBackInter") ?: return
        val req = AdRequest.Builder().build()

        if (SpotSort.fromString(pref.getString("IsAdType")) == SpotSort.GOOGLE) {
            activity.recordAdEvent("back_interstitial", "req")
            InterstitialAd.load(
                activity, id, req,
                object : InterstitialAdLoadCallback() {
                    override fun onAdLoaded(ad: InterstitialAd) {
                        googleInterBack = ad
                        Log.d("TakeoverRear", "Back Inter Loaded")
                        activity.safeLog("Back_Inter_Loaded")
                        activity.recordAdEvent("back_interstitial", "loaded")
                    }

                    override fun onAdFailedToLoad(err: LoadAdError) {
                        googleInterBack = null
                        Log.e("TakeoverRear", "Back Inter Load Fail: ${err.message}")
                        activity.safeLog("Back_Inter_Load_FAILED_${err.code}")
                        activity.recordAdLoadError("back_interstitial", err.code)
                    }
                })
        }
    }

    fun presentBackAds(activity: Activity?, adsClose: () -> Unit) {
        presentBackInternal(activity, adsClose)
    }

    private fun presentBackInternal(activity: Activity?, adsClose: () -> Unit) {
        val act = activity ?: return adsClose()
        val pref = SpotsLocker.getInstance(act)
        var closedOnce = false

        fun safeClose(reason: String) {
            if (closedOnce) return
            closedOnce = true
            act.safeLog("Closed_$reason")
            Log.e("TakeoverRear", "Closed: $reason")
            try {
                adsClose()
            } catch (_: Exception) {
            }
        }

        if (!isNetworkConnected(act)) return safeClose("no_network")
        if (!pref.getBoolean("IsAdsON")) return safeClose("ads_off")

        if (!pref.getBoolean("InterAds")) return safeClose("inter_ads_disabled")
        if (!pref.getBoolean("IsBack")) return safeClose("back_ads_disabled")

        val target = pref.getInt("InterBackCounter")

        if (interBackCounter != target) {
            interBackCounter++
            return safeClose("counter_skip")
        }
        interBackCounter = 0

        if (HomeSeatSpots.hasOwnFlow(act, "back")) {
            if (!HomeSeatSpots.placementEnabled(act, "back")) return safeClose("back_ads_on_false")
            HomeSeatSpots.showInterstitial(act, "back") { safeClose("back_flow") }
            return
        }

        when (SpotSort.fromString(pref.getString("IsAdType"))) {
            SpotSort.GOOGLE -> {
                if (googleInterBack != null) {
                    WholeDisplayWheel.beforeShow(act) { presentGoogleBackInter(act, pref, ::safeClose) }
                } else {
                    presentGoogleBackInter(act, pref, ::safeClose)
                }
            }

            SpotSort.FACEBOOK -> {
                presentFacebookBackInter(
                    act,
                    onDismiss = { safeClose("fb_back_dismiss") },
                    onFail = {
                        presentCustomAfterFBFail(act, pref) {
                            safeClose("fb_back_fail")
                        }
                    }
                )
            }

            SpotSort.CUSTOM, SpotSort.UNKNOWN -> {
                if (pref.getBoolean("IsCustomADS"))
                    TakeoverStandard.enterDirectLink(act) { safeClose("custom_open") }
                else safeClose("custom_disabled")
            }

            else -> safeClose("invalid_type")
        }
    }

    private fun presentGoogleBackInter(
        activity: Activity,
        pref: SpotsLocker,
        safeClose: (String) -> Unit
    ) {
        val ad = googleInterBack
        if (ad == null) {
            return processGoogleFail(activity, pref, safeClose)
        }
        activity.safeLog("google_back_inter_show_attempt")

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                super.onAdShowedFullScreenContent()
                isInterBAckShow = true
                runCatching { activity.recordAdEvent("back_interstitial", "show") }
            }

            override fun onAdDismissedFullScreenContent() {
                googleInterBack = null
                isInterBAckShow = false
                runCatching { activity.recordAdEvent("back_interstitial", "close") }
                safeClose("Google_Dismiss")
                fetchBackInterAds(activity)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                googleInterBack = null
                processGoogleFail(activity, pref, safeClose)
                fetchBackInterAds(activity)
            }
        }

        try {
            ad.show(activity)
        } catch (e: Exception) {
            googleInterBack = null
            processGoogleFail(activity, pref, safeClose)
            fetchBackInterAds(activity)
        }
    }

    private fun processGoogleFail(
        activity: Activity,
        pref: SpotsLocker,
        safeClose: (String) -> Unit
    ) {
        if (pref.getBoolean("IsFail_FB")) {
            presentFacebookBackInter(
                activity,
                onDismiss = { safeClose("fb_dismiss") },
                onFail = { presentCustomAfterFBFail(activity, pref, safeClose) }
            )

        } else {
            if (pref.getBoolean("IsCustomADS")) {
                TakeoverStandard.enterDirectLink(activity) { safeClose("google_fail_custom") }
            } else safeClose("google_fail_no_fb_no_custom")
        }
    }

    private fun presentFacebookBackInter(
        context: Context,
        onDismiss: () -> Unit,
        onFail: () -> Unit
    ) {
        val pref = SpotsLocker.getInstance(context)
        val isLoader = WholeDisplayWheel.isEnabled(context)
        val fbId = pref.getString("faceB_InterAds") ?: return onFail()

        val fb = com.facebook.ads.InterstitialAd(context, fbId)

        if (context is Activity) WholeDisplayWheel.show(context, isLoader)

        fb.loadAd(
            fb.buildLoadAdConfig()
                .withAdListener(object : InterstitialAdListener {
                    override fun onAdLoaded(ad: Ad?) {
                        if (context is Activity) WholeDisplayWheel.hide()
                        try {
                            fb.show()
                        } catch (e: Exception) {
                            onFail()
                        }
                    }

                    override fun onError(ad: Ad?, err: com.facebook.ads.AdError?) {
                        if (context is Activity) WholeDisplayWheel.hide()
                        onFail()
                    }

                    override fun onInterstitialDismissed(ad: Ad?) {
                        if (context is Activity) WholeDisplayWheel.hide()
                        onDismiss()
                    }

                    override fun onLoggingImpression(ad: Ad?) {}
                    override fun onInterstitialDisplayed(ad: Ad?) {
                        if (context is Activity) WholeDisplayWheel.hide()
                    }

                    override fun onAdClicked(ad: Ad?) {}

                }).build()
        )
    }

    private fun presentCustomAfterFBFail(
        context: Activity,
        pref: SpotsLocker,
        safeClose: (String) -> Unit
    ) {
        if (pref.getBoolean("IsCustomADS"))
            TakeoverStandard.enterDirectLink(context) { safeClose("fb_fail_custom") }
        else safeClose("fb_fail_no_custom")
    }

    private fun Context.safeLog(event: String) {
        try {
            this.recordKeyEvent(event)
        } catch (_: Exception) {
        }
    }

}
