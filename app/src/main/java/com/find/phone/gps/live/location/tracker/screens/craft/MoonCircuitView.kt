package com.find.phone.gps.live.location.tracker.screens.craft

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.R

class MoonCircuitView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val orbit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = ContextCompat.getColor(context, R.color.ds_dashed_ring)
        pathEffect = DashPathEffect(floatArrayOf(4f * d, 4f * d), 0f)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        color = ContextCompat.getColor(context, R.color.ds_primary)
    }
    private val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(14f * d, 0f, 6f * d, 0x40F4511E)
    }
    private val chipPrimary = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ds_primary) }
    private val chipCyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ds_cyan2) }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ds_primary15) }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.ds_primary) }

    private val rect = RectF()
    private val path = Path()
    private var rotation = 0f
    private var dashPhase = 0f
    private val animators = mutableListOf<Animator>()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = side / 2f

        val u = side / 260f

        canvas.drawCircle(cx, cy, r, orbit)

        canvas.save()
        canvas.rotate(rotation, cx, cy)

        line.pathEffect = DashPathEffect(floatArrayOf(4f * u, 6f * u), -dashPhase)
        path.reset()
        path.moveTo(cx, cy); path.lineTo(cx, cy - r)
        path.moveTo(cx, cy); path.lineTo(cx - 114f * u, cy + 70f * u)
        path.moveTo(cx, cy); path.lineTo(cx + 114f * u, cy + 70f * u)
        canvas.drawPath(path, line)

        sketchTile(canvas, cx, cy - r, 28f * u, 9f * u, 10f * u, chipPrimary)

        sketchTile(canvas, cx - 114f * u, cy + 70f * u, 24f * u, 8f * u, 8f * u, chipCyan)
        sketchTile(canvas, cx + 114f * u, cy + 70f * u, 24f * u, 8f * u, 8f * u, chipCyan)
        canvas.restore()

        canvas.drawCircle(cx, cy, 17f * u, halo)
        canvas.drawCircle(cx, cy, 9f * u, core)
    }

    private fun sketchTile(canvas: Canvas, x: Float, y: Float, size: Float, radius: Float, chip: Float, chipPaint: Paint) {
        rect.set(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f)
        canvas.drawRoundRect(rect, radius, radius, tile)
        rect.set(x - chip / 2f, y - chip / 2f, x + chip / 2f, y + chip / 2f)
        canvas.drawRoundRect(rect, 3f * d, 3f * d, chipPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (CraftMovement.animationsDisabled(context)) return
        CraftMovement.abortAll(animators)
        animators += ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 5000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = CraftMovement.LINEAR
            addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
            start()
        }
        animators += ValueAnimator.ofFloat(0f, 24f).apply {
            duration = 800L
            repeatCount = ValueAnimator.INFINITE
            interpolator = CraftMovement.LINEAR
            addUpdateListener { dashPhase = (it.animatedValue as Float) * (minOf(width, height) / 260f) }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        CraftMovement.abortAll(animators)
        super.onDetachedFromWindow()
    }
}
