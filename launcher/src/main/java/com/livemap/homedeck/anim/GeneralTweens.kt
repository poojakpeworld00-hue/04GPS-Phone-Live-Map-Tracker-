package com.livemap.homedeck.anim

import android.view.View
import androidx.core.view.isVisible
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import com.livemap.homedeck.R

object GeneralTweens {
    private const val PRESS_SCALE = 0.94f
    private const val PRESS_DOWN_MS = 90L
    private const val PRESS_UP_MS = 220L

    fun buttonPressAnimation(
        view: View,
        onClick: () -> Unit
    ) {
        if (view.getTag(R_TAG_PRESSING) == true) return
        view.setTag(R_TAG_PRESSING, true)

        val restZ = view.translationZ
        view.animate()
            .scaleX(PRESS_SCALE)
            .scaleY(PRESS_SCALE)
            .translationZ(0f)
            .setDuration(PRESS_DOWN_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationZ(restZ)
                    .setDuration(PRESS_UP_MS)
                    .setInterpolator(OvershootInterpolator(2f))
                    .withEndAction {
                        view.setTag(R_TAG_PRESSING, false)
                        onClick()
                    }
                    .start()
            }
            .start()
    }

    private const val REVEAL_MS = 380L
    private const val CONCEAL_MS = 260L
    private const val REVEAL_FROM_SCALE = 0.82f
    private const val REVEAL_FROM_Y_DP = 32f

    fun unveilCard(view: View) {
        if (view.getTag(R_TAG_SHOWN) == true) return
        view.setTag(R_TAG_SHOWN, true)

        view.animate().cancel()
        if (!view.isVisible) {
            view.alpha = 0f
            view.scaleX = REVEAL_FROM_SCALE
            view.scaleY = REVEAL_FROM_SCALE
            view.translationY = REVEAL_FROM_Y_DP * view.resources.displayMetrics.density
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(REVEAL_MS)
            .setInterpolator(OvershootInterpolator(1.2f))
            .withLayer()
            .withEndAction(null)
            .start()
    }

    fun concealCard(view: View) {
        if (view.getTag(R_TAG_SHOWN) != true) return
        view.setTag(R_TAG_SHOWN, false)

        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .scaleX(REVEAL_FROM_SCALE)
            .scaleY(REVEAL_FROM_SCALE)
            .translationY(REVEAL_FROM_Y_DP * 0.6f * view.resources.displayMetrics.density)
            .setDuration(CONCEAL_MS)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withLayer()
            .withEndAction {
                if (view.getTag(R_TAG_SHOWN) != true) view.visibility = View.INVISIBLE
            }
            .start()
    }

    private const val SHEET_MS = 260L
    private const val FADE_MS = 200L
    private const val POP_MS = 220L

    fun unveilSheet(view: View) {
        if (view.getTag(R_TAG_SHOWN) == true) return
        view.setTag(R_TAG_SHOWN, true)
        view.animate().cancel()
        if (!view.isVisible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.post {
                if (view.getTag(R_TAG_SHOWN) != true) return@post
                view.translationY = view.height * 0.25f
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(SHEET_MS)
                    .setInterpolator(DecelerateInterpolator(2f))
                    .withLayer()
                    .start()
            }
            return
        }
        view.animate().alpha(1f).translationY(0f).setDuration(SHEET_MS)
            .setInterpolator(DecelerateInterpolator(2f)).withLayer().start()
    }

    fun concealSheet(view: View) {
        if (view.getTag(R_TAG_SHOWN) != true) return
        view.setTag(R_TAG_SHOWN, false)
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(view.height * 0.25f)
            .setDuration(SHEET_MS - 60L)
            .setInterpolator(AccelerateInterpolator(1.4f))
            .withLayer()
            .withEndAction {
                if (view.getTag(R_TAG_SHOWN) != true) {
                    view.visibility = View.GONE
                    view.translationY = 0f
                }
            }
            .start()
    }

    fun fade(view: View, shown: Boolean, hidden: Int = View.GONE, alpha: Float = 1f) {
        if (view.getTag(R_TAG_SHOWN) == shown) return
        view.setTag(R_TAG_SHOWN, shown)
        view.animate().cancel()
        if (shown && !view.isVisible) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
        }
        view.animate()
            .alpha(if (shown) alpha else 0f)
            .setDuration(FADE_MS)
            .withEndAction { if (!shown && view.getTag(R_TAG_SHOWN) != true) view.visibility = hidden }
            .start()
    }

    fun popIn(view: View) {
        view.animate().cancel()
        view.scaleX = 0.5f
        view.scaleY = 0.5f
        view.alpha = 0f
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(POP_MS)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }

    fun quickPressAnimation(view: View, onClick: () -> Unit) {
        view.animate().cancel()
        view.scaleX = PRESS_SCALE
        view.scaleY = PRESS_SCALE
        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(160L)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
        onClick()
    }

    fun View.setOnQuickPressClickListener(onClick: () -> Unit) {
        setOnClickListener { quickPressAnimation(this, onClick) }
    }

    fun View.setOnPressAnimatedClickListener(onClick: () -> Unit) {
        setOnClickListener { buttonPressAnimation(this, onClick) }
    }

    private val R_TAG_PRESSING = R.id.tag_press_animating
    private val R_TAG_SHOWN = R.id.tag_card_shown
}
