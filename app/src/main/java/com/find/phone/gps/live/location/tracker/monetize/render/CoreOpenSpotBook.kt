package com.find.phone.gps.live.location.tracker.monetize.render

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback
import io.lighthouse.push.extended.LightHouseRichPush
import com.find.phone.gps.live.location.tracker.monetize.model.SpotSort
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotIncomeGauge
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.logKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverRear
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.BuildConfig

object CoreOpenSpotBook {
    private const val LOG_TAG = "CoreOpenSpotBook"
    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    var isShowingAd: Boolean = false
    var callbackshow:Boolean = false
    var isOpenAppDismiss: Boolean = false

    // When true, the next background→foreground transition skips the App Open
    // ad exactly once. Set before app-initiated trips to system settings (e.g.
    // the overlay-permission flow) so that programmatic return isn't monetised.
    var skipNextAppOpenAd: Boolean = false

    /**
     * Set when the launcher opens another app, so the *return* from that app is the moment an ad may
     * show — the reference's `other_app_return`. Consumed once, on the first foreground after it is
     * set, so it never leaks into an unrelated return. This is what lets the app-tap ad fire on the
     * way back rather than alongside the app (e.g. the Play Store) it just launched.
     */
    private var expectReturnAd: Boolean = false

    fun expectReturnAd() {
        expectReturnAd = true
        // A skip armed earlier but never consumed (the splash arms one for its disclosure trip,
        // which often never leaves the app) would otherwise eat this return and its ad. The user
        // leaving for another app from the launcher is the newer, deliberate trip, so it wins.
        skipNextAppOpenAd = false
    }

    fun consumeExpectReturnAd(): Boolean {
        val v = expectReturnAd
        expectReturnAd = false
        return v
    }

    val isAdAvailable: Boolean
        get() = appOpenAd != null

    fun loadAd(context: Context?) {
        if (isLoadingAd || isAdAvailable) {
            return
        }
        if (context == null) {
            return
        }
        val adsPreference = SpotsLocker.getInstance(context)
        // Firebase "AppopenAds" master switch — disable app-open loading entirely
        if (!adsPreference.getBoolean("AppopenAds")) {
            Log.e(LOG_TAG, "AppopenAds disabled by Firebase flag")
            return
        }
        if (SpotSort.fromString(adsPreference.getString("IsAdType")) == SpotSort.GOOGLE) {
            isLoadingAd = true
            val request = AdRequest.Builder().build()


            if (adsPreference.getString("IsAdType").equals("Google", true)) {
                SpotsLocker.getInstance(context).getString("googleAppopen")?.let { adUnitId ->
                    AppOpenAd.load(
                        context, adUnitId, request, object : AppOpenAdLoadCallback() {
                            override fun onAdLoaded(ad: AppOpenAd) {

                                try {
                                    context.logKeyEvent("appopen_ad_loaded")
                                } catch (e: Exception) {
                                }

                                Log.d(LOG_TAG, "Ad was loaded.")
                                appOpenAd = ad
                                isLoadingAd = false
                            }

                            override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                                Log.e(
                                    LOG_TAG, "Ad failed to load: ${loadAdError.message}"
                                )

                                try {
                                    context.logKeyEvent("appopen_ad_fail")
                                } catch (e: Exception) {
                                }
                                isLoadingAd = false
                            }
                        })
                }
            }

        } else {
            return
        }
    }

    fun showAdIfAvailable(
        activity: Activity, onShowAdCompleteListener: OnShowAdCompleteListener
    ) {
        if (!SpotsLocker.getInstance(activity).getBoolean("IsAdsON")) {
            return
        }
        // Firebase "AppopenAds" master switch — skip showing app-open ads entirely
        if (!SpotsLocker.getInstance(activity).getBoolean("AppopenAds")) {
            Log.e(LOG_TAG, "AppopenAds disabled by Firebase flag")
            onShowAdCompleteListener.onShowAdComplete()
            return
        }
        if (callbackshow) {
            Log.e(
                LOG_TAG, "callbackshow is Show"
            )
            return
        }
        // Never cover a LightHouse rich-push overlay with an App Open ad.
        if (LightHouseRichPush.shouldDeferOverlay(activity)) {
            Log.e(LOG_TAG, "Deferring App Open — rich-push overlay active")
            return
        }
        if (TakeoverStandard.Companion.isInterShow) {
            Log.e(
                LOG_TAG, "Inter is Show"
            )
            return
        }
        if (TakeoverRear.Companion.isInterBAckShow) {
            Log.e(
                LOG_TAG, "Inter Back is Show"
            )
            return
        }
        if (isShowingAd) {
            Log.e(
                LOG_TAG, "The app open ad is already showing."
            )
            return
        }
        if (!isAdAvailable) {
            Log.e(
                LOG_TAG, "The app open ad is not ready yet."
            )
            onShowAdCompleteListener.onShowAdComplete()
            loadAd(activity)
            return
        }

        appOpenAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.e(
                    LOG_TAG, "Ad dismissed fullscreen content."
                )

                try {
                    activity.logKeyEvent("appopen_ad_dismissed")
                } catch (_: Exception) {
                }

                appOpenAd = null
                isShowingAd = false
                isOpenAppDismiss = true
                onShowAdCompleteListener.onShowAdComplete()
                loadAd(
                    activity
                )
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                Log.e(
                    LOG_TAG, adError.message
                )
                try {
                    activity.logKeyEvent("appopen_ad_fail")
                } catch (_: Exception) {
                }
                appOpenAd = null
                isShowingAd = false
                onShowAdCompleteListener.onShowAdComplete()
                loadAd(
                    activity
                )
            }

            override fun onAdShowedFullScreenContent() {
                Log.e(
                    LOG_TAG, "Ad showed fullscreen content."
                )
            }
        }

        // Log load
        activity.logKeyEvent("appopen_ad_shown")

        if (BuildConfig.DEBUG) SpotIncomeGauge.simulateDebugRevenue(activity)

        appOpenAd!!.setOnPaidEventListener {
            SpotIncomeGauge.logPaidEvent(activity, it)
        }

        isShowingAd = true

        if (SpotsLocker.getInstance(activity).getString("IsAdType").equals("Google", true)) {
            appOpenAd?.show(activity)
        }
    }

    interface OnShowAdCompleteListener {
        fun onShowAdComplete()
    }

}
