package com.find.phone.gps.live.location.tracker.consent.drawer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.dp

class UnveilVersusArrange @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var fraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            enactFraction()
            onFractionChanged?.invoke(field)
        }

    var onFractionChanged: ((Float) -> Unit)? = null

    private lateinit var cardTop: View
    private lateinit var handle: View
    private val clip = Rect()
    private var loop: ValueAnimator? = null
    private var dragging = false
    private var running = false
    private val grabSlop = context.dp(28f)
    private val resumeLoop = Runnable { if (running && !dragging) beginLoop() }

    override fun onFinishInflate() {
        super.onFinishInflate()
        cardTop = findViewById(R.id.plateTop)
        handle = findViewById(R.id.lv_handle)
        cardTop.clipToOutline = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        enactFraction()
    }

    private fun isRtl() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun enactFraction() {
        if (!::cardTop.isInitialized || width == 0) return
        val w = cardTop.width
        val revealed = (w * fraction).toInt()

        if (isRtl()) clip.set(w - revealed, 0, w, cardTop.height) else clip.set(0, 0, revealed, cardTop.height)
        cardTop.clipBounds = clip
        val x = if (isRtl()) width - width * fraction else width * fraction
        handle.translationX = x - handle.width / 2f
    }

    fun start() {
        running = true
        if (CraftMovement.animationsDisabled(context)) {
            fraction = 0.55f
            return
        }
        beginLoop()
    }

    fun stop() {
        running = false
        removeCallbacks(resumeLoop)
        loop?.cancel()
        loop = null
    }

    private fun beginLoop() {
        loop?.cancel()
        val e = CraftMovement.EASE_IN_OUT

        val frames = arrayOf(0f to 0f, 0.08f to 0f, 0.42f to 1f, 0.62f to 1f, 0.94f to 0f, 1f to 0f)
        loop = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOOP_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = CraftMovement.LINEAR
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val i = frames.indexOfLast { it.first <= t }.coerceAtMost(frames.size - 2)
                val (t0, v0) = frames[i]
                val (t1, v1) = frames[i + 1]
                val p = if (t1 > t0) e.getInterpolation(((t - t0) / (t1 - t0)).coerceIn(0f, 1f)) else 1f
                fraction = v0 + (v1 - v0) * p
            }

            start()
        }
    }

    private fun processX(): Float = handle.translationX + handle.width / 2f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && kotlin.math.abs(ev.x - processX()) <= grabSlop) {
            beginDrag()
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (kotlin.math.abs(event.x - processX()) > grabSlop) return false
                beginDrag()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) dragTo(event.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) endDrag()
        }
        return dragging
    }

    private fun beginDrag() {
        dragging = true

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
        if (running && !CraftMovement.animationsDisabled(context)) {
            val from = fraction
            loop = ValueAnimator.ofFloat(from, 0f).apply {
                startDelay = RESUME_DELAY_MS
                duration = 450L
                interpolator = CraftMovement.EASE_IN_OUT
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
