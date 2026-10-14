package com.livemap.homedeck.screens

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.Telephony
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import com.livemap.homedeck.R
import com.livemap.homedeck.contract.HomeBook
import timber.log.Timber

class PreferredMainClueDeck : AppCompatActivity() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var mockAnimation: Animator? = null
    private lateinit var kind: Kind

    enum class Kind(
        val title: Int,
        val description: Int,

        val position: Position,

        val control: Control,
        val dismissAfterMs: Long,
    ) {
        OVERLAY(
            R.string.launcher_hint_overlay_title,
            R.string.launcher_hint_overlay_description,
            Position.BOTTOM,
            Control.SWITCH,
            3_000L,
        ),
        DEFAULT_HOME(
            R.string.launcher_hint_default_home_title,
            R.string.launcher_hint_default_home_description,
            Position.BOTTOM,
            Control.RADIO,
            3_000L,
        ),
        DEFAULT_SMS(
            R.string.launcher_hint_default_sms_title,
            R.string.launcher_hint_default_sms_description,
            Position.TOP,
            Control.NONE,
            2_500L,
        ),
        ;

        fun isSatisfied(context: Context): Boolean = when (this) {
            OVERLAY -> Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

            DEFAULT_HOME -> runCatching {
                val probe = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val resolvedPackage = context.packageManager.resolveActivity(probe, 0)?.activityInfo?.packageName
                resolvedPackage == context.packageName
            }.getOrDefault(false)

            DEFAULT_SMS -> runCatching {
                context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
            }.getOrDefault(false)
        }
    }

    enum class Position { TOP, BOTTOM }

    enum class Control { SWITCH, RADIO, NONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requested = intent?.getStringExtra(EXTRA_KIND).orEmpty()
        val resolved = Kind.entries.firstOrNull { it.name == requested }
        if (resolved == null) {
            finish()
            return
        }
        kind = resolved

        setContentView(R.layout.hd_default_home_hint_panel)
        applyKind()

        findViewById<View>(R.id.stackMainUi)?.setOnClickListener { finish() }
        if (kind.control == Control.SWITCH) animateToggle()
        else if (kind.control == Control.RADIO) animateRadio()

        mainHandler.postDelayed({ if (!isFinishing) finish() }, kind.dismissAfterMs)
        mainHandler.post(doneWatcher)
    }

    private fun shownIf(condition: Boolean) = if (condition) View.VISIBLE else View.GONE

    private fun applyKind() {
        val appName = runCatching { applicationInfo.loadLabel(packageManager).toString() }
            .getOrElse { getString(R.string.launcher_app_launcher_name) }
        findViewById<android.widget.TextView>(R.id.coachTitleUi)?.text = getString(kind.title, appName)
        findViewById<android.widget.TextView>(R.id.coachDescriptionUi)?.setText(kind.description)
        for (labelId in intArrayOf(R.id.coachMockAppLabelUi, R.id.coachMockOverlayLabelUi)) {
            findViewById<android.widget.TextView>(labelId)?.text = appName
        }

        val control = kind.control
        findViewById<View>(R.id.coachHomeMockUi)?.visibility = shownIf(control == Control.RADIO)
        findViewById<View>(R.id.coachOverlayMockUi)?.visibility = shownIf(control == Control.SWITCH)
        findViewById<View>(R.id.coachRowUi)?.visibility = shownIf(control == Control.NONE)

        if (kind.position != Position.TOP) return
        findViewById<View>(R.id.coachSpacerUi)?.visibility = View.GONE
        with(window) {
            setGravity(Gravity.TOP)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)

            addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
    }

    private val doneWatcher = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            when {
                kind.isSatisfied(this@PreferredMainClueDeck) -> finish()
                else -> mainHandler.postDelayed(this, CHECK_MS)
            }
        }
    }

    private fun <T : ValueAnimator> T.pulsing(): T = apply {
        duration = FLIP_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
    }

    private fun startTogether(parts: List<Animator>) {
        mockAnimation = AnimatorSet().apply {
            startDelay = HOLD_MS
            playTogether(parts)
            start()
        }
    }

    private fun animateToggle() {
        val track = findViewById<FrameLayout>(R.id.coachSwitchTrackUi) ?: return
        val thumb = findViewById<View>(R.id.coachSwitchThumbUi) ?: return
        val row = findViewById<View>(R.id.coachMockOverlayRowUi)

        val density = resources.displayMetrics.density
        val distance = (TRACK_WIDTH_DP - THUMB_SIZE_DP - THUMB_INSET_DP * 2) * density

        val slide = ObjectAnimator.ofFloat(thumb, View.TRANSLATION_X, 0f, distance).pulsing()
        val fade = ValueAnimator.ofObject(ArgbEvaluator(), getColor(R.color.hint_switch_off), activeColor()).pulsing()
        fade.addUpdateListener { a ->
            track.backgroundTintList = ColorStateList.valueOf(a.animatedValue as Int)
        }

        startTogether(listOf(slide, fade) + rowLift(row))
    }

    private fun rowLift(row: View?): List<Animator> {
        if (row == null) return emptyList()
        val rise = -ROW_LIFT_DP * resources.displayMetrics.density
        return listOf(
            ObjectAnimator.ofFloat(row, View.SCALE_X, 1f, ROW_LIFT_SCALE).pulsing(),
            ObjectAnimator.ofFloat(row, View.SCALE_Y, 1f, ROW_LIFT_SCALE).pulsing(),
            ObjectAnimator.ofFloat(row, View.TRANSLATION_Y, 0f, rise).pulsing(),
        )
    }

    private fun animateRadio() {
        val ring = findViewById<View>(R.id.coachRadioRingUi) ?: return
        val dot = findViewById<View>(R.id.coachRadioDotUi) ?: return
        val row = findViewById<View>(R.id.coachMockAppRowUi)

        dot.apply {
            scaleX = 0f
            scaleY = 0f
            alpha = 0f
        }

        val appear = listOf(View.SCALE_X, View.SCALE_Y, View.ALPHA).map { prop ->
            ObjectAnimator.ofFloat(dot, prop, 0f, 1f).pulsing()
        }

        val fade = ValueAnimator.ofObject(ArgbEvaluator(), restingColor(), activeColor()).pulsing()
        fade.addUpdateListener { a ->
            val tint = ColorStateList.valueOf(a.animatedValue as Int)
            ring.backgroundTintList = tint
            dot.backgroundTintList = tint
        }

        startTogether(appear + fade + rowLift(row))
    }

    private fun restingColor(): Int = getColor(R.color.hint_mock_row_ring)
    private fun activeColor(): Int = getColor(R.color.hint_accent)

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        mockAnimation?.cancel()
        mockAnimation = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "OverlayFlow"
        private const val EXTRA_KIND = "hint_kind"

        fun presentForOverlay(activity: Activity) = show(activity, Kind.OVERLAY)

        fun presentForDefaultHome(activity: Activity) = show(activity, Kind.DEFAULT_HOME)

        fun show(activity: Activity) = show(activity, Kind.DEFAULT_HOME)

        fun presentForDefaultSms(activity: Activity) = show(activity, Kind.DEFAULT_SMS)

        fun show(activity: Activity, kind: Kind) {
            if (kind.isSatisfied(activity)) return

            val poller = Handler(Looper.getMainLooper())
            val beganAt = SystemClock.elapsedRealtime()
            poller.postDelayed(object : Runnable {
                override fun run() {
                    if (activity.isFinishing || activity.isDestroyed || kind.isSatisfied(activity)) return
                    val stillFocused = activity.hasWindowFocus()
                    val elapsed = SystemClock.elapsedRealtime() - beganAt
                    when {
                        !stillFocused -> poller.postDelayed({ deliver(activity, kind) }, SETTLE_MS)
                        elapsed < GIVE_UP_MS -> poller.postDelayed(this, POLL_MS)
                        else -> Timber.tag(TAG).w("%s screen never came up — no hint", kind)
                    }
                }
            }, FIRST_CHECK_MS)
        }

        private fun deliver(activity: Activity, kind: Kind) {
            if (activity.isFinishing || activity.isDestroyed || kind.isSatisfied(activity)) return
            runCatching {
                val hostCard = kind.takeIf { it == Kind.DEFAULT_HOME }
                    ?.let { HomeBook.bridge.defaultHomeHintIntent(activity) }
                val target = hostCard
                    ?: Intent(activity, PreferredMainClueDeck::class.java).putExtra(EXTRA_KIND, kind.name)
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                activity.startActivity(target)
                Timber.tag(TAG).d("hint shown: %s", kind)
            }.onFailure { Timber.tag(TAG).w(it, "could not show the %s hint", kind) }
        }

        private const val FIRST_CHECK_MS = 250L
        private const val POLL_MS = 150L

        private const val SETTLE_MS = 350L

        private const val GIVE_UP_MS = 6_000L

        private const val CHECK_MS = 300L

        private const val FLIP_MS = 700L
        private const val HOLD_MS = 250L

        private const val TRACK_WIDTH_DP = 52f
        private const val THUMB_SIZE_DP = 26f
        private const val THUMB_INSET_DP = 3f

        private const val ROW_LIFT_SCALE = 1.04f
        private const val ROW_LIFT_DP = 3f
    }
}
