package com.find.phone.gps.live.location.tracker.screens.boot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.find.phone.gps.live.location.tracker.induction.MemoOpeningActivity
import com.find.phone.gps.live.location.tracker.memo.CuePortal
import io.lighthouse.push.LightHouse
import io.lighthouse.push.extended.LightHouseRichPush
import com.find.phone.gps.live.location.tracker.monetize.model.UponPayloadPrepared
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.monetize.render.presentAppRedirectPopup
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.databinding.ScreenSplashBinding
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import com.find.phone.gps.live.location.tracker.induction.InductionMainOnward
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.opening.OpeningUnveilCharter
import com.find.phone.gps.live.location.tracker.screens.tongue.TongueSelectorActivity
import com.find.phone.gps.live.location.tracker.screens.welcome.BriefingActivity
import com.find.phone.gps.live.location.tracker.screens.policy.PolicyPortalActivity
import com.find.phone.gps.live.location.tracker.helpers.enterActivity
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class BootActivity : StageActivity<ScreenSplashBinding>() {
    override val layoutId: Int = R.layout.screen_splash
    private val handler = Handler(Looper.getMainLooper())
    private var motion: IntroMovement? = null

    private val prefs by lazy { LockerBook(this) }

    private val proceeded = AtomicBoolean(false)

    private val dataReady = AtomicBoolean(false)
    private val animMinElapsed = AtomicBoolean(false)

    private companion object {
        const val SPLASH_FLOW_TAG = "SplashFlow"

        const val ANIM_MIN_MS = IntroMovement.CYCLE_MS

        const val ANIM_MIN_REDUCED_MS = 700L
    }

    override fun initView() {
        if (LightHouseRichPush.handleFromSplash(this, binding.root)) return

        prefs.appLaunchCount = prefs.appLaunchCount + 1

        CuePortal.onAppLaunch(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bootRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        motion = IntroMovement(binding).also { it.start() }
        val animMin = if (CraftMovement.animationsDisabled(this)) ANIM_MIN_REDUCED_MS else ANIM_MIN_MS
        handler.postDelayed({
            Log.d(SPLASH_FLOW_TAG, "animation min elapsed (${animMin}ms) → maybeProceed")
            animMinElapsed.set(true)
            perhapsProceed()
        }, animMin)

        printHashKey(this)
        Log.d(SPLASH_FLOW_TAG, "SplashActivity.initView → getData()")

        getData(this, true, object : UponPayloadPrepared {
            override fun onSuccess() {
                Log.d(SPLASH_FLOW_TAG, "getData onSuccess → dataReady")
                CoreOpenSpotBook.loadAd(this@BootActivity)
                dataReady.set(true)
                perhapsProceed()
            }

            override fun onError() {
                Log.d(SPLASH_FLOW_TAG, "getData onError → dataReady")
                dataReady.set(true)
                perhapsProceed()
            }
        })
    }

    private fun perhapsProceed() {
        if (dataReady.get() && animMinElapsed.get()) {
            Log.d(SPLASH_FLOW_TAG, "both gates met (data + animation) → proceedNow")
            proceedNow()
        }
    }

    private fun proceedNow() {
        if (!proceeded.compareAndSet(false, true)) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow ignored (already proceeded)")
            return
        }
        handler.removeCallbacksAndMessages(null)
        if (isFinishing || isDestroyed) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow skipped (finishing/destroyed)")
            return
        }
        val redirectLink = SpotsLocker.getInstance(this).getString("In_App_Update_Link")
        if (!redirectLink.isNullOrEmpty()) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → showAppRedirectPopup")
            presentAppRedirectPopup { fireNext() }
        } else {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → launchNext")
            fireNext()
        }
    }

    private fun fireNext() {
        if (isFinishing || isDestroyed) {
            Log.d(SPLASH_FLOW_TAG, "launchNext skipped (finishing/destroyed)")
            return
        }
        Log.d(SPLASH_FLOW_TAG, "launchNext → ensureDataDisclosure")

        CoreOpenSpotBook.skipNextAppOpenAd = true
        LightHouse.ensureDataDisclosure(this) {
            if (isFinishing || isDestroyed) return@ensureDataDisclosure
            LightHouse.subscribeAsync()

            val launcherOnboarding = !HomeStream.wasOnboardingCompleted(this)
            val next = nextScreen()
            Log.d(SPLASH_FLOW_TAG, "ensureDataDisclosure done → launching ${next.simpleName}")

            val intent = when {
                launcherOnboarding && next != HomeStream.homeActivity() -> HomeStream.onboardingIntent(this, next)

                next == HomeStream.homeActivity() -> MemoOpeningActivity.wrap(this, Intent(this, next))
                else -> Intent(this, next)
            }

            enterActivity(intent, isShowAd = false)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        motion?.cancel()
        motion = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun nextScreen(): Class<*> = when {
        !HomeStream.wasOnboardingCompleted(this) ->
            InductionMainOnward.resumeScreen(this) ?: HomeStream.firstScreen(this)
        OpeningUnveilCharter.mustShowLanguage(this) -> TongueSelectorActivity::class.java
        OpeningUnveilCharter.mustShowTerms(this) -> PolicyPortalActivity::class.java
        OpeningUnveilCharter.mustShowOnboarding(this) -> BriefingActivity::class.java
        else -> HomeStream.homeActivity()
    }

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun printHashKey(context: Context) {
        try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            } ?: return

            signatures.forEach { sig ->
                val md = MessageDigest.getInstance("SHA")
                md.update(sig.toByteArray())
                Log.d("KeyHash", Base64.encodeToString(md.digest(), Base64.DEFAULT))
            }
        } catch (_: Exception) {
        }
    }
}
