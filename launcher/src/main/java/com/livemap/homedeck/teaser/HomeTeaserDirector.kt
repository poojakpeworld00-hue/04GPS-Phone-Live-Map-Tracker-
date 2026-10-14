package com.livemap.homedeck.teaser

import com.livemap.homedeck.contract.HomeBook
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log

object HomeTeaserDirector {
    private const val TAG = "LAUNCHER_PROMO"

    private val counters = mutableMapOf<String, Int>()

    fun run(activity: Activity?, gesture: String, action: () -> Unit) {
        var done = false
        val once = {
            if (!done) {
                done = true
                action()
            }
        }

        if (activity == null || !HomeSpotsProfile.interEnabled(gesture)) {
            once()
            return
        }

        if (!HomeBook.ads.isReady()) {
            Log.d(TAG, "$gesture: ads not initialised — running the action")
            once()
            return
        }

        val target = HomeSpotsProfile.interCounter(gesture).coerceAtLeast(0)
        val count = counters[gesture] ?: 0
        if (count < target) {
            counters[gesture] = count + 1
            Log.d(TAG, "$gesture: counter ${count + 1}/$target — skipping")
            once()
            return
        }
        counters[gesture] = 0

        val url = if (HomeSpotsProfile.isAppLaunch(gesture)) "" else HomeSpotsProfile.promoUrl(gesture)
        if (url.isNotEmpty()) {
            Log.d(TAG, "$gesture: opening promo link")
            enterLink(activity, url)
            once()
            return
        }

        Log.d(TAG, "$gesture: showing interstitial")
        runCatching {
            val show = if (HomeSpotsProfile.isAppLaunch(gesture)) HomeBook.ads::showFullNative
            else HomeBook.ads::showInterstitial
            show(activity, gesture) {
                Log.d(TAG, "$gesture: interstitial closed")
                once()
            }
        }.onFailure {
            Log.w(TAG, "$gesture: interstitial failed - running the action", it)
            once()
        }
    }

    private fun enterLink(activity: Activity, url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            if (it is ActivityNotFoundException) {
                Log.w(TAG, "no handler for the promo link")
            } else {
                Log.w(TAG, "could not open the promo link", it)
            }
        }
    }

    fun reset() = counters.clear()
}
