package com.find.phone.gps.live.location.tracker.monetize.render

import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import android.content.Context
import android.content.Intent
import android.widget.TextView
import com.find.phone.gps.live.location.tracker.R
import com.livemap.homedeck.ext.isDefaultLauncher
import com.find.phone.gps.live.location.tracker.helpers.WardenRail

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ClueDrawerActivity : AppCompatActivity() {
    companion object {
        private const val AUTO_DISMISS_MS = 3_000L

        private const val EXTRA_MODE = "mode"

        const val MODE_OVERLAY = "overlay"

        const val MODE_HOME = "home"

        fun show(context: Context, mode: String) {
            if (MentorDrawerPane.show(context, mode)) return

            runCatching {
                context.startActivity(
                    Intent(context, ClueDrawerActivity::class.java).putExtra(EXTRA_MODE, mode)
                )
            }.onFailure { WardenRail.error("OverlayGuide", "guide failed to start ($mode)", it) }
        }

        fun satisfied(context: Context, mode: String): Boolean = when (mode) {
            MODE_HOME -> runCatching { context.isDefaultLauncher() }.getOrDefault(false)
            else -> Settings.canDrawOverlays(context)
        }

        fun enactMode(root: View, mode: String) {
            if (mode != MODE_HOME) return
            root.findViewById<TextView>(R.id.coachTitleTv)?.setText(R.string.home_guide_title)
            root.findViewById<TextView>(R.id.coachDescTv)?.setText(R.string.home_guide_desc)
            root.findViewById<TextView>(R.id.coachRowHintTv)?.setText(R.string.home_guide_row_hint)

            root.findViewById<TextView>(R.id.coachRowNameTv)?.let { name ->
                name.text = runCatching {
                    val ctx = name.context.applicationContext
                    ctx.applicationInfo.loadLabel(ctx.packageManager).toString().trim()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: name.context.getString(R.string.app_name)
            }

            root.findViewById<View>(R.id.lv_animation_view)?.visibility = View.GONE
            root.findViewById<CompoundButton>(R.id.coachRadioRb)?.let {
                it.visibility = View.VISIBLE
                pulseRadio(it)
            }
        }

        private fun pulseRadio(radio: CompoundButton) {
            val tick = object : Runnable {
                override fun run() {
                    if (!radio.isAttachedToWindow) return
                    radio.isChecked = !radio.isChecked
                    radio.postDelayed(this, if (radio.isChecked) 700L else 350L)
                }
            }
            radio.postDelayed(tick, 700L)
        }
    }

    private val mode: String
        get() = intent?.getStringExtra(EXTRA_MODE) ?: MODE_OVERLAY

    private fun isSatisfied(): Boolean = satisfied(this, mode)

    private var pollJob: Job? = null
    private var autoDismissJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.screen_overlay_guide)

        val root = findViewById<View>(R.id.stackMain)

        enactMode(root, mode)

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        ViewCompat.requestApplyInsets(root)

        root?.setOnClickListener {
            finish()
        }

        findViewById<View>(R.id.veilCard)?.apply {
            alpha = 0f
            post {
                translationY = height.toFloat()
                animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(280L)
                    .setInterpolator(DecelerateInterpolator(1.6f))
                    .start()
            }
        }

        autoDismissJob = lifecycleScope.launch {
            delay(AUTO_DISMISS_MS)
            if (!isFinishing && !isDestroyed) finish()
        }
    }

    override fun onResume() {
        super.onResume()

        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                if (isSatisfied()) {
                    finish()
                    return@launch
                }
                delay(500L)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
    }
}
