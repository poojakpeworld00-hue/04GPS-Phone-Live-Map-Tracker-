package com.find.my.phone.gps.location.tracker.ui.splash

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
import com.find.my.phone.gps.location.tracker.onboard.ReminderIntroActivity
import com.find.my.phone.gps.location.tracker.reminder.PromptGate
import io.lighthouse.push.LightHouse
import io.lighthouse.push.extended.LightHouseRichPush
import com.find.my.phone.gps.location.tracker.adcast.data.OnDataReady
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.adcast.presentation.showAppRedirectPopup
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.databinding.ViewSplashBinding
import com.find.my.phone.gps.location.tracker.onboard.LauncherFlow
import com.find.my.phone.gps.location.tracker.onboard.OnboardingHomeForward
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.intro.IntroRevealPolicy
import com.find.my.phone.gps.location.tracker.ui.language.LangChooserActivity
import com.find.my.phone.gps.location.tracker.ui.onboarding.PrimerActivity
import com.find.my.phone.gps.location.tracker.ui.terms.TermsGateActivity
import com.find.my.phone.gps.location.tracker.util.openActivity
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Entry point. Plays the "Map Light" splash (design 1c) while the ads module runs consent →
 * Remote Config → attribution → permission → splash ad, then routes to the first-run
 * sequence or the home screen. The animation is owned by [SplashMotion]; navigation waits
 * for BOTH the data chain and one full animation cycle.
 */
class StartupActivity : CanvasActivity<ViewSplashBinding>() {

    override val layoutId: Int = R.layout.view_splash
    private val handler = Handler(Looper.getMainLooper())
    private var motion: SplashMotion? = null

    private val prefs by lazy { VaultRegistry(this) }

    // Ensures we navigate exactly once, whether the win comes from getData's callback or a
    // late animation gate.
    private val proceeded = AtomicBoolean(false)

    // Navigation waits on BOTH gates: the getData chain must be ready AND the intro animation
    // must have played long enough.
    private val dataReady = AtomicBoolean(false)
    private val animMinElapsed = AtomicBoolean(false)

    private companion object {
        // Shared with AdBeaconActivity's tracing. Filter: `adb logcat -s SplashFlow`
        const val SPLASH_FLOW_TAG = "SplashFlow"

        // One full pass of the four-stage sequence (satellite → phone → pin → logo hold).
        const val ANIM_MIN_MS = SplashMotion.CYCLE_MS

        // With animations off the scene is a static final frame; nothing to wait for.
        const val ANIM_MIN_REDUCED_MS = 700L
    }

    override fun initView() {
        // Rich-push cold start: if this launch came from a push routed via the launcher, hand
        // off to the rich-push activity and skip the splash flow.
        if (LightHouseRichPush.handleFromSplash(this, binding.root)) return

        // One session = one cold start. Bump before nextScreen() so the intro `app_launches`
        // frequency counts this launch.
        prefs.appLaunchCount = prefs.appLaunchCount + 1
        // Same count for the reminders prompt's `prompt_frequency: app_launches`.
        PromptGate.onAppLaunch(this)

        // Light canvas → dark status-bar icons (and the reverse in dark mode); the grid and roads
        // run under the bars.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.splashRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        motion = SplashMotion(binding).also { it.start() }
        val animMin = if (DesignMotion.animationsDisabled(this)) ANIM_MIN_REDUCED_MS else ANIM_MIN_MS
        handler.postDelayed({
            Log.d(SPLASH_FLOW_TAG, "animation min elapsed (${animMin}ms) → maybeProceed")
            animMinElapsed.set(true)
            maybeProceed()
        }, animMin)

        printHashKey(this)
        Log.d(SPLASH_FLOW_TAG, "SplashActivity.initView → getData()")
        // Hand off to the ad module. It runs consent + SDK init + runtime permission prompts +
        // native/banner/interstitial preloads, then fires one of the two callbacks below.
        getData(this, true, object : OnDataReady {
            override fun onSuccess() {
                Log.d(SPLASH_FLOW_TAG, "getData onSuccess → dataReady")
                AppOpenAdRegistry.loadAd(this@StartupActivity)
                dataReady.set(true)
                maybeProceed()
            }

            override fun onError() {
                Log.d(SPLASH_FLOW_TAG, "getData onError → dataReady")
                dataReady.set(true)
                maybeProceed()
            }
        })
    }

    /** Navigate only once BOTH gates are met: data ready AND the intro has played. */
    private fun maybeProceed() {
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
        val redirectLink = AdsVault.getInstance(this).getString("In_App_Update_Link")
        if (!redirectLink.isNullOrEmpty()) {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → showAppRedirectPopup")
            showAppRedirectPopup { launchNext() }
        } else {
            Log.d(SPLASH_FLOW_TAG, "proceedNow → launchNext")
            launchNext()
        }
    }

    private fun launchNext() {
        if (isFinishing || isDestroyed) {
            Log.d(SPLASH_FLOW_TAG, "launchNext skipped (finishing/destroyed)")
            return
        }
        Log.d(SPLASH_FLOW_TAG, "launchNext → ensureDataDisclosure")
        // The LightHouse data-disclosure screen briefly sends the app background→foreground.
        // That foreground event would pop the returning-app App Open ad right after the
        // disclosure, in the middle of the first-launch flow. Suppress it for this one trip.
        AppOpenAdRegistry.skipNextAppOpenAd = true
        LightHouse.ensureDataDisclosure(this) {
            if (isFinishing || isDestroyed) return@ensureDataDisclosure
            LightHouse.subscribeAsync()
            // Read before nextScreen(): starting the launcher's first run can decide the whole
            // order has nothing to show and mark onboarding completed on the spot.
            val launcherOnboarding = !LauncherFlow.wasOnboardingCompleted(this)
            val next = nextScreen()
            Log.d(SPLASH_FLOW_TAG, "ensureDataDisclosure done → launching ${next.simpleName}")
            // The first-run marker tells Intro and Language they are steps in the launcher's
            // sequence rather than the app's own gated screens.
            val intent = when {
                launcherOnboarding && next != LauncherFlow.homeActivity() -> LauncherFlow.onboardingIntent(this, next)
                // Later launches: the reminders intro goes right before Home when it is due.
                next == LauncherFlow.homeActivity() -> ReminderIntroActivity.wrap(this, Intent(this, next))
                else -> Intent(this, next)
            }
            // Splash → onboarding/main: no interstitial on the very first launch.
            openActivity(intent, isShowAd = false)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        motion?.cancel()
        motion = null
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Picks the next screen in the launch flow. Language, Terms and Onboarding are all gated
     * by [IntroRevealPolicy] (the `intro_display` Remote Config block).
     */
    private fun nextScreen(): Class<*> = when {
        // The launcher's own onboarding runs once, ahead of everything else, and ends on the
        // home screen. Its sequence comes from `launcher_ads.onboarding.order`.
        // Left with Home mid-run: back at that screen, which then moves on (OnboardingHomeForward).
        !LauncherFlow.wasOnboardingCompleted(this) ->
            OnboardingHomeForward.resumeScreen(this) ?: LauncherFlow.firstScreen(this)
        IntroRevealPolicy.shouldShowLanguage(this) -> LangChooserActivity::class.java
        IntroRevealPolicy.shouldShowTerms(this) -> TermsGateActivity::class.java
        IntroRevealPolicy.shouldShowOnboarding(this) -> PrimerActivity::class.java
        else -> LauncherFlow.homeActivity()
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
