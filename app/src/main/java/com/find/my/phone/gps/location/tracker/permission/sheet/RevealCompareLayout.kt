package com.find.my.phone.gps.location.tracker.permission.sheet

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.dp

/**
 * The SIM page's before/after reveal (design "Permission Sheet" 2b): `@id/cardTop` is clipped
 * from the start edge to the `@id/handle` divider, over `@id/cardBase`.
 *
 * On its own it plays the design's 8s loop (`rev` + `hd`: hold hidden, wipe in, hold, wipe out).
 * Touching near the handle takes over: the user drags it to compare, and the loop picks up again
 * a moment after they let go. Touches away from the handle are left to the pager, so the page can
 * still be swiped.
 */
class RevealCompareLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 0 = only "without access" showing, 1 = fully revealed. */
    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            applyFraction()
            onFractionChanged?.invoke(field)
        }

    /** Lets the page fade its "Without / With access" labels with the wipe. */
    var onFractionChanged: ((Float) -> Unit)? = null

    private lateinit var cardTop: View
    private lateinit var handle: View
    private val clip = Rect()
    private var loop: ValueAnimator? = null
    private var dragging = false
    private var running = false
    private val grabSlop = context.dp(28f)
    private val resumeLoop = Runnable { if (running && !dragging) startLoop() }

    override fun onFinishInflate() {
        super.onFinishInflate()
        cardTop = findViewById(R.id.cardTop)
        handle = findViewById(R.id.handle)
        cardTop.clipToOutline = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyFraction()
    }

    private fun isRtl() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun applyFraction() {
        if (!::cardTop.isInitialized || width == 0) return
        val w = cardTop.width
        val revealed = (w * fraction).toInt()
        // The wipe runs from the reading-start edge, so mirror it in RTL.
        if (isRtl()) clip.set(w - revealed, 0, w, cardTop.height) else clip.set(0, 0, revealed, cardTop.height)
        cardTop.clipBounds = clip
        val x = if (isRtl()) width - width * fraction else width * fraction
        handle.translationX = x - handle.width / 2f
    }

    /** Starts the auto loop (no-op with system animations off: shows the half-way frame). */
    fun start() {
        running = true
        if (DesignMotion.animationsDisabled(context)) {
            fraction = 0.55f
            return
        }
        startLoop()
    }

    fun stop() {
        running = false
        removeCallbacks(resumeLoop)
        loop?.cancel()
        loop = null
    }

    private fun startLoop() {
        loop?.cancel()
        val e = DesignMotion.EASE_IN_OUT
        // rev / hd: 0–8% hidden, 42–62% revealed, 94–100% hidden again, ease-in-out.
        val frames = arrayOf(0f to 0f, 0.08f to 0f, 0.42f to 1f, 0.62f to 1f, 0.94f to 0f, 1f to 0f)
        loop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOOP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = DesignMotion.LINEAR
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val i = frames.indexOfLast { it.first <= t }.coerceAtMost(frames.size - 2)
                val (t0, v0) = frames[i]
                val (t1, v1) = frames[i + 1]
                val p = if (t1 > t0) e.getInterpolation(((t - t0) / (t1 - t0)).coerceIn(0f, 1f)) else 1f
                fraction = v0 + (v1 - v0) * p
            }
            // Always restarts at 0%, where the wipe is hidden; endDrag() glides back there first.
            start()
        }
    }

    private fun handleX(): Float = handle.translationX + handle.width / 2f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && kotlin.math.abs(ev.x - handleX()) <= grabSlop) {
            beginDrag()
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (kotlin.math.abs(event.x - handleX()) > grabSlop) return false
                beginDrag()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) dragTo(event.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) endDrag()
        }
        return dragging
    }

    private fun beginDrag() {
        dragging = true
        // Keep the pager from stealing the gesture while the handle is held.
        parent?.requestDisallowInterceptTouchEvent(true)
        removeCallbacks(resumeLoop)
        loop?.cancel()
        handle.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120L).start()
    }

    private fun dragTo(x: Float) {
        if (width == 0) return
        val f = x / width
        fraction = if (isRtl()) 1f - f else f
    }

    private fun endDrag() {
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        handle.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
        performClick()
        if (running && !DesignMotion.animationsDisabled(context)) {
            // Glide back to hidden, then let the loop take over again.
            val from = fraction
            loop = ValueAnimator.ofFloat(from, 0f).apply {
                startDelay = RESUME_DELAY_MS
                duration = 450L
                interpolator = DesignMotion.EASE_IN_OUT
                addUpdateListener { fraction = it.animatedValue as Float }
                start()
            }
            postDelayed(resumeLoop, RESUME_DELAY_MS + 470L)
        }
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LOOP_MS = 8000L
        const val RESUME_DELAY_MS = 1500L
    }
}
