package com.find.my.phone.gps.location.tracker.onboard

import android.animation.Animator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.dp
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.loop
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.pvh
import com.find.my.phone.gps.location.tracker.util.GuardRail

/**
 * Hint dialog 5a: the "pick this app" card drawn over the system *Default home app* list. The
 * card mirrors this app's row and runs the design's 2.4 s tap loop — the hand moves in and
 * presses, the ripple spreads, the radio ring turns primary and its dot fills, the row glows —
 * then resets. Enters with `fadein .45s cubic-bezier(.2,.9,.3,1.1)`.
 *
 * A translucent activity in its own task (see the manifest), so it sits over Settings rather than
 * joining this app's task behind it. It goes away on the first touch, after [AUTO_FINISH_MS], or when the caller comes back to front.
 */
class RoleHintActivity : AppCompatActivity() {

    private val autoFinish = Runnable { if (!isFinishing) finish() }
    private val animators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.view_home_hint)
        GuardRail.log(TAG, "hint shown over the home-app list")

        // Focusable on purpose: a FLAG_NOT_FOCUSABLE window here left the app with no focused
        // window while it was the top task, and the input dispatcher ANR-killed the process on
        // the system list. The first touch dismisses the card, so the list is never blocked.

        val root = findViewById<View>(R.id.llMain)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // The row mirrors the entry the user is looking for: this app's label.
        runCatching {
            findViewById<TextView>(R.id.guideRowNameTv)?.text =
                packageManager.getApplicationLabel(applicationInfo).toString().trim()
        }

        bindDismissOnTouch(root)
        enter(findViewById(R.id.overlayCardVw))
        startTapLoop()

        visible = this
        root.postDelayed(autoFinish, AUTO_FINISH_MS)
    }

    /** `fadein`: opacity 0 → 1, translateY 12 → 0, scale .97 → 1, 450 ms with a slight overshoot. */
    private fun enter(card: View) {
        if (DesignMotion.animationsDisabled(this)) return
        val rise = dp(12f)
        card.alpha = 0f
        card.translationY = rise
        card.scaleX = 0.97f
        card.scaleY = 0.97f
        animators += ObjectAnimator.ofPropertyValuesHolder(
            card,
            PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
            PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, rise, 0f),
            PropertyValuesHolder.ofFloat(View.SCALE_X, 0.97f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.97f, 1f),
        ).apply {
            duration = 450L
            interpolator = ENTER
            start()
        }
    }

    /** The 2.4 s loop: `tap`, `ripple`, `rring`, `rdot` and `glowrow`, all in phase. */
    private fun startTapLoop() {
        if (DesignMotion.animationsDisabled(this)) {
            // Final frame: the hand resting on a selected radio.
            findViewById<View>(R.id.hintRadioRingOn)?.alpha = 1f
            findViewById<View>(R.id.hintRadioDot)?.apply { scaleX = 1f; scaleY = 1f }
            return
        }
        val hand = findViewById<View>(R.id.hintHand)
        val ripple = findViewById<View>(R.id.hintRipple)
        val ringOn = findViewById<View>(R.id.hintRadioRingOn)
        val dot = findViewById<View>(R.id.hintRadioDot)
        val glow = findViewById<View>(R.id.hintRowGlow)

        // tap: rests at (10,12); in at 38%, pressed (scale .86) at 48%, released by 62%, back out.
        val restX = dp(10f)
        val restY = dp(12f)
        animators += loop(
            hand, CYCLE_MS, 0L,
            pvh(View.TRANSLATION_X, DesignMotion.EASE_IN_OUT, 0f to restX, .38f to 0f, .62f to 0f, 1f to restX),
            pvh(View.TRANSLATION_Y, DesignMotion.EASE_IN_OUT, 0f to restY, .38f to 0f, .62f to 0f, 1f to restY),
            pvh(View.SCALE_X, DesignMotion.EASE_IN_OUT, 0f to 1f, .38f to 1f, .48f to .86f, .62f to 1f, 1f to 1f),
            pvh(View.SCALE_Y, DesignMotion.EASE_IN_OUT, 0f to 1f, .38f to 1f, .48f to .86f, .62f to 1f, 1f to 1f),
        )
        // ripple: scale .3 → 1.4, opacity 0 → .5 (50%) → 0, from 44% to 70%.
        animators += loop(
            ripple, CYCLE_MS, 0L,
            pvh(View.SCALE_X, DesignMotion.EASE_OUT, 0f to .3f, .44f to .3f, .70f to 1.4f, 1f to 1.4f),
            pvh(View.SCALE_Y, DesignMotion.EASE_OUT, 0f to .3f, .44f to .3f, .70f to 1.4f, 1f to 1.4f),
            pvh(View.ALPHA, DesignMotion.EASE_OUT, 0f to 0f, .44f to 0f, .50f to .5f, .70f to 0f, 1f to 0f),
        )
        // rring: the ring turns primary between 46% and 54%, back after 90%.
        animators += loop(
            ringOn, CYCLE_MS, 0L,
            pvh(View.ALPHA, DesignMotion.EASE, 0f to 0f, .46f to 0f, .54f to 1f, .90f to 1f, 1f to 0f),
        )
        // rdot: the dot fills over the same window.
        animators += loop(
            dot, CYCLE_MS, 0L,
            pvh(View.SCALE_X, DesignMotion.EASE, 0f to 0f, .46f to 0f, .54f to 1f, .90f to 1f, 1f to 0f),
            pvh(View.SCALE_Y, DesignMotion.EASE, 0f to 0f, .46f to 0f, .54f to 1f, .90f to 1f, 1f to 0f),
        )
        // glowrow: a 6dp cyan halo breathes around the row, ease-in-out.
        animators += loop(
            glow, CYCLE_MS, 0L,
            pvh(View.ALPHA, DesignMotion.EASE_IN_OUT, 0f to 0f, .5f to 1f, 1f to 0f),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindDismissOnTouch(root: View) {
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !isFinishing) finish()
            true
        }
    }

    override fun onDestroy() {
        findViewById<View>(R.id.llMain)?.removeCallbacks(autoFinish)
        DesignMotion.cancelAll(animators)
        if (visible === this) visible = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DefaultHomeHint"

        /** Long enough to find one row in a list of launchers. */
        private const val AUTO_FINISH_MS = 12_000L

        /** One pass of the tap loop. */
        private const val CYCLE_MS = 2400L

        /** The card entrance curve, `cubic-bezier(.2,.9,.3,1.1)`. */
        private val ENTER = PathInterpolator(0.2f, 0.9f, 0.3f, 1.1f)

        private var visible: RoleHintActivity? = null

        fun intent(context: Context): Intent =
            Intent(context, RoleHintActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun dismiss() {
            visible?.takeUnless { it.isFinishing }?.finish()
            visible = null
        }
    }
}
