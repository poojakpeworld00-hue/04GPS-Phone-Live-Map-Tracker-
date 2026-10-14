package com.find.phone.gps.live.location.tracker.induction

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
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.dp
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.loop
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.pvh
import com.find.phone.gps.live.location.tracker.helpers.WardenRail

class PartClueActivity : AppCompatActivity() {
    private val autoFinish = Runnable { if (!isFinishing) finish() }
    private val animators = mutableListOf<Animator>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_home_hint)
        WardenRail.log(TAG, "hint shown over the home-app list")

        val root = findViewById<View>(R.id.stackMain)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        runCatching {
            findViewById<TextView>(R.id.coachRowNameTv)?.text =
                packageManager.getApplicationLabel(applicationInfo).toString().trim()
        }

        wireDismissOnTouch(root)
        enter(findViewById(R.id.veilCard))
        beginTapLoop()

        visible = this
        root.postDelayed(autoFinish, AUTO_FINISH_MS)
    }

    private fun enter(card: View) {
        if (CraftMovement.animationsDisabled(this)) return
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

    private fun beginTapLoop() {
        if (CraftMovement.animationsDisabled(this)) {
            findViewById<View>(R.id.clueRadioRingOn)?.alpha = 1f
            findViewById<View>(R.id.clueRadioDot)?.apply { scaleX = 1f; scaleY = 1f }
            return
        }
        val hand = findViewById<View>(R.id.clueHand)
        val ripple = findViewById<View>(R.id.clueRipple)
        val ringOn = findViewById<View>(R.id.clueRadioRingOn)
        val dot = findViewById<View>(R.id.clueRadioDot)
        val glow = findViewById<View>(R.id.clueRowGlow)

        val restX = dp(10f)
        val restY = dp(12f)
        animators += loop(
            hand, CYCLE_MS, 0L,
            pvh(View.TRANSLATION_X, CraftMovement.EASE_IN_OUT, 0f to restX, .38f to 0f, .62f to 0f, 1f to restX),
            pvh(View.TRANSLATION_Y, CraftMovement.EASE_IN_OUT, 0f to restY, .38f to 0f, .62f to 0f, 1f to restY),
            pvh(View.SCALE_X, CraftMovement.EASE_IN_OUT, 0f to 1f, .38f to 1f, .48f to .86f, .62f to 1f, 1f to 1f),
            pvh(View.SCALE_Y, CraftMovement.EASE_IN_OUT, 0f to 1f, .38f to 1f, .48f to .86f, .62f to 1f, 1f to 1f),
        )

        animators += loop(
            ripple, CYCLE_MS, 0L,
            pvh(View.SCALE_X, CraftMovement.EASE_OUT, 0f to .3f, .44f to .3f, .70f to 1.4f, 1f to 1.4f),
            pvh(View.SCALE_Y, CraftMovement.EASE_OUT, 0f to .3f, .44f to .3f, .70f to 1.4f, 1f to 1.4f),
            pvh(View.ALPHA, CraftMovement.EASE_OUT, 0f to 0f, .44f to 0f, .50f to .5f, .70f to 0f, 1f to 0f),
        )

        animators += loop(
            ringOn, CYCLE_MS, 0L,
            pvh(View.ALPHA, CraftMovement.EASE, 0f to 0f, .46f to 0f, .54f to 1f, .90f to 1f, 1f to 0f),
        )

        animators += loop(
            dot, CYCLE_MS, 0L,
            pvh(View.SCALE_X, CraftMovement.EASE, 0f to 0f, .46f to 0f, .54f to 1f, .90f to 1f, 1f to 0f),
            pvh(View.SCALE_Y, CraftMovement.EASE, 0f to 0f, .46f to 0f, .54f to 1f, .90f to 1f, 1f to 0f),
        )

        animators += loop(
            glow, CYCLE_MS, 0L,
            pvh(View.ALPHA, CraftMovement.EASE_IN_OUT, 0f to 0f, .5f to 1f, 1f to 0f),
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun wireDismissOnTouch(root: View) {
        root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !isFinishing) finish()
            true
        }
    }

    override fun onDestroy() {
        findViewById<View>(R.id.stackMain)?.removeCallbacks(autoFinish)
        CraftMovement.abortAll(animators)
        if (visible === this) visible = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DefaultHomeHint"

        private const val AUTO_FINISH_MS = 12_000L

        private const val CYCLE_MS = 2400L

        private val ENTER = PathInterpolator(0.2f, 0.9f, 0.3f, 1.1f)

        private var visible: PartClueActivity? = null

        fun intent(context: Context): Intent =
            Intent(context, PartClueActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun dismiss() {
            visible?.takeUnless { it.isFinishing }?.finish()
            visible = null
        }
    }
}
