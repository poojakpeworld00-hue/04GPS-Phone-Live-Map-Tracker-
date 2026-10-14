package com.find.my.phone.gps.location.tracker.adcast.presentation

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.isVisible
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.domain.RewardedConfig
import com.find.my.phone.gps.location.tracker.adcast.domain.RewardedCooldown
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds.InterstitialNormal
import com.find.my.phone.gps.location.tracker.databinding.DialogRewardRetryBinding
import com.find.my.phone.gps.location.tracker.databinding.DialogRewardWaitBinding

/**
 * Preload-and-show pattern for Google AdMob Rewarded ads.
 *
 * Usage:
 *   RewardedPromo.preload(activity)                      // call early (screen open)
 *   RewardedPromo().show(activity) { result -> ... }     // on the user's "watch ad" tap
 *
 * [Result] says whether the user may have what the ad was for:
 * - ads OFF, or no internet (even during a no-fill cooldown): EARNED straight away — the
 *   content shows without an ad;
 * - no rewarded unit id, or the ad loaded but failed to show: EARNED, after the DirectLink
 *   fallback when custom ads are on;
 * - no fill (the network had no ad): a cooldown starts ([RewardedCooldown]) with no requests at
 *   all. `rewarded.nofill_mode` "free" unlocks every tap until it ends (EARNED); "wait" shows the
 *   countdown ring — at zero "Unlock now" asks for the ad again, "Maybe later" gives CANCELED;
 * - any other load error while online: the "Ad not ready" dialog — Retry loads and tries again,
 *   Cancel gives CANCELED; after `rewarded.max_retries` failed Retries it unlocks (EARNED);
 * - the ad played: EARNED only if the reward was earned before it was closed, else NOT_EARNED.
 */
class RewardedPromo {

    enum class Result { EARNED, NOT_EARNED, CANCELED }

    companion object {
        private const val TAG = "RewardedPromo"

        /** How long a tap waits for a load that is already in flight. */
        private const val WAIT_FOR_LOAD_MS = 6_000L

        /** Countdown ring refresh: smooth enough to drain, cheap enough to not matter. */
        private const val RING_TICK_MS = 100L

        /** How dark the screen behind the rewarded dialogs goes. */
        private const val DIM_AMOUNT = 0.6f

        /** AdMob's "no ad to serve" codes: NO_FILL and MEDIATION_NO_FILL. */
        private val NO_FILL_CODES = setOf(AdRequest.ERROR_CODE_NO_FILL, AdRequest.ERROR_CODE_MEDIATION_NO_FILL)

        private var loadedAd: RewardedAd? = null
        private var isLoading = false
        private val waiting = mutableListOf<() -> Unit>()

        fun isReady(): Boolean = loadedAd != null

        /** Call this early (e.g. when the screen opens) to warm up the ad. */
        fun preload(context: Context) {
            val pref = AdsVault.getInstance(context)
            if (!pref.getBoolean("IsAdsON")) return
            if (loadedAd != null || isLoading) return
            // Asking again inside a no-fill cooldown only piles up empty requests.
            if (RewardedCooldown.isActive(context)) return

            val unitId = pref.getString("googleRewarded")
            if (unitId.isNullOrEmpty()) return

            val app = context.applicationContext
            isLoading = true
            Log.d(TAG, "Preloading…")

            RewardedAd.load(
                app,
                unitId,
                AdRequest.Builder().build(),
                object : RewardedAdLoadCallback() {
                    override fun onAdLoaded(ad: RewardedAd) {
                        loadedAd = ad
                        isLoading = false
                        RewardedCooldown.reset(app)
                        Log.d(TAG, "Preloaded OK")
                        flushWaiting()
                    }

                    override fun onAdFailedToLoad(error: LoadAdError) {
                        loadedAd = null
                        isLoading = false
                        Log.e(TAG, "Preload failed (${error.code}): ${error.message}")
                        if (error.code in NO_FILL_CODES) {
                            app.logKeyEvent("reward_nofill")
                            RewardedCooldown.start(app)
                        } else {
                            app.logKeyEvent("reward_load_error_${error.code}")
                        }
                        flushWaiting()
                    }
                }
            )
        }

        private fun flushWaiting() {
            val pending = waiting.toList()
            waiting.clear()
            pending.forEach { it() }
        }

        /** Show DirectLink fallback, then invoke [onClosed] when it's done. */
        private fun showDirectLinkFallback(activity: Activity, onClosed: () -> Unit) {
            val pref = AdsVault.getInstance(activity)
            if (pref.getBoolean("IsCustomADS")) {
                Log.d(TAG, "Falling back to DirectLink")
                InterstitialNormal.openDirectLink(activity) { onClosed() }
            } else {
                onClosed()
            }
        }
    }

    /** Shows the rewarded ad; [onDone] gets whether the reward counts (see the class doc). */
    fun show(activity: Activity, onDone: (Result) -> Unit) = show(activity, onDone, attempt = 0)

    /** [attempt] counts the "Ad not ready" Retries taken so far in this tap. */
    private fun show(activity: Activity, onDone: (Result) -> Unit, attempt: Int) {
        val pref = AdsVault.getInstance(activity)

        if (!pref.getBoolean("IsAdsON")) {
            onDone(Result.EARNED)
            return
        }
        // Offline always unlocks — ahead of the no-fill cooldown, so "wait" mode never holds an
        // offline user. A preloaded ad stays held for the next tap online.
        if (!isNetworkConnected(activity)) {
            Log.d(TAG, "No internet — content shown without an ad")
            onDone(Result.EARNED)
            return
        }
        if (RewardedCooldown.isActive(activity)) return onCooldown(activity, onDone)

        // Nothing loaded and nothing loading (the preload failed earlier, or a cooldown just
        // ended): ask now, then wait for it like for one already in flight.
        if (loadedAd == null) preload(activity)

        // A load is running: give it a moment instead of skipping the ad outright.
        if (loadedAd == null && isLoading) {
            val handler = Handler(Looper.getMainLooper())
            var settled = false
            val resume = {
                if (!settled) {
                    settled = true
                    handler.removeCallbacksAndMessages(null)
                    if (!activity.isFinishing && !activity.isDestroyed) showLoaded(activity, onDone, attempt)
                }
            }
            waiting += resume
            handler.postDelayed({ waiting.remove(resume); resume() }, WAIT_FOR_LOAD_MS)
            return
        }
        showLoaded(activity, onDone, attempt)
    }

    private fun showLoaded(activity: Activity, onDone: (Result) -> Unit, attempt: Int) {
        val ad = loadedAd
        if (ad == null) {
            val maxRetries = RewardedConfig.read(activity).maxRetries
            when {
                !isNetworkConnected(activity) -> {
                    Log.d(TAG, "No ad, no internet — content shown without an ad")
                    onDone(Result.EARNED)
                }
                AdsVault.getInstance(activity).getString("googleRewarded").isNullOrEmpty() -> {
                    Log.d(TAG, "No rewarded unit — showing DirectLink fallback")
                    showDirectLinkFallback(activity) { onDone(Result.EARNED) }
                }
                // The load just came back empty and started a cooldown.
                RewardedCooldown.isActive(activity) -> onCooldown(activity, onDone)
                maxRetries >= 0 && attempt >= maxRetries -> {
                    Log.d(TAG, "Still no ad after $attempt Retries — content shown without an ad")
                    activity.logKeyEvent("reward_retry_exhausted")
                    onDone(Result.EARNED)
                }
                else -> {
                    Log.d(TAG, "Online but the ad didn't load — asking to try again")
                    preload(activity)
                    askRetry(activity, onDone, attempt)
                }
            }
            return
        }

        // Consume the held reference so we don't show it twice
        loadedAd = null
        var earned = false

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                Log.d(TAG, "Dismissed, earned=$earned")
                preload(activity)
                onDone(if (earned) Result.EARNED else Result.NOT_EARNED)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.e(TAG, "Show failed: ${error.message} — showing DirectLink fallback")
                preload(activity)
                showDirectLinkFallback(activity) { onDone(Result.EARNED) }
            }
        }

        ad.show(activity) {
            earned = true
            Log.d(TAG, "Reward earned: ${it.type} x${it.amount}")
        }
    }

    /** Dims the screen behind [dialog], whatever the dialog theme says. */
    private fun dimBehind(dialog: Dialog) {
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIM_AMOUNT)
        }
    }

    /** Inside a no-fill cooldown: unlock ("free") or show the countdown ("wait"). */
    private fun onCooldown(activity: Activity, onDone: (Result) -> Unit) {
        if (RewardedConfig.read(activity).mode == RewardedConfig.Mode.FREE) {
            Log.d(TAG, "No-fill cooldown (${RewardedCooldown.remainingMs(activity) / 1000}s left) — content shown without an ad")
            activity.logKeyEvent("reward_cooldown_free")
            onDone(Result.EARNED)
        } else {
            activity.logKeyEvent("reward_cooldown_wait")
            askWait(activity, onDone)
        }
    }

    private fun dialogFor(activity: Activity): Pair<Dialog, DialogRewardRetryBinding> {
        val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
        val view = DialogRewardRetryBinding.inflate(LayoutInflater.from(themed))
        val dialog = Dialog(themed)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dimBehind(dialog)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCanceledOnTouchOutside(false)
        return dialog to view
    }

    /**
     * "Ad not ready — try again": Retry starts a fresh load (or joins the one in flight) and goes
     * through [show] again, which waits for it; Cancel or Back ends with [Result.CANCELED].
     */
    private fun askRetry(activity: Activity, onDone: (Result) -> Unit, attempt: Int) {
        if (activity.isFinishing || activity.isDestroyed) return onDone(Result.CANCELED)
        val (dialog, view) = dialogFor(activity)
        var retrying = false
        dialog.setOnDismissListener { if (!retrying) onDone(Result.CANCELED) }
        view.btnRetryCancel.setOnClickListener { dialog.dismiss() }
        view.btnRetry.setOnClickListener {
            retrying = true
            dialog.dismiss()
            show(activity, onDone, attempt + 1)
        }
        dialog.show()
    }

    /**
     * "Almost ready to unlock" (`nofill_mode` "wait"): a ring that drains with the countdown in
     * its centre. The copy never mentions ads — it keeps the user here (or brings them back) for
     * the unlock. At zero the ring turns into a check and "Unlock now" asks for the ad again;
     * "Maybe later" / Back → CANCELED.
     */
    private fun askWait(activity: Activity, onDone: (Result) -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return onDone(Result.CANCELED)
        val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
        val view = DialogRewardWaitBinding.inflate(LayoutInflater.from(themed))
        val dialog = Dialog(themed)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dimBehind(dialog)
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.setCanceledOnTouchOutside(false)

        val handler = Handler(Looper.getMainLooper())
        val total = RewardedCooldown.totalMs(activity).toFloat()
        var unlock = false

        fun setUnlockEnabled(on: Boolean) {
            view.btnWaitUnlock.isEnabled = on
            view.btnWaitUnlock.alpha = if (on) 1f else 0.45f
        }
        setUnlockEnabled(false)

        val tick = object : Runnable {
            override fun run() {
                val left = RewardedCooldown.remainingMs(activity)
                if (left > 0) {
                    view.ringWait.progress = (left / total * view.ringWait.max).toInt()
                    view.tvWaitTime.text = DateUtils.formatElapsedTime((left + 999) / 1000)
                    handler.postDelayed(this, RING_TICK_MS)
                } else {
                    view.ringWait.progress = 0
                    view.tvWaitTime.isVisible = false
                    view.ivWaitDone.isVisible = true
                    view.tvWaitTitle.setText(R.string.ds_reward_wait_ready_title)
                    view.tvWaitBody.setText(R.string.ds_reward_wait_ready)
                    setUnlockEnabled(true)
                }
            }
        }
        tick.run()

        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            if (unlock) show(activity, onDone) else onDone(Result.CANCELED)
        }
        view.btnWaitLater.setOnClickListener { dialog.dismiss() }
        view.btnWaitUnlock.setOnClickListener {
            unlock = true
            dialog.dismiss()
        }
        dialog.show()
    }
}
