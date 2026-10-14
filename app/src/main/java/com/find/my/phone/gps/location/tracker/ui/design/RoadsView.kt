package com.find.my.phone.gps.location.tracker.ui.design

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser
import com.find.my.phone.gps.location.tracker.R

/**
 * The decorative road networks the design draws as inline SVGs, scaled to the view:
 *
 *  - [Variant.SPLASH]: three pale roads over the 360×780 splash canvas.
 *  - [Variant.HOME_HERO]: two translucent white roads over the 360×300 home hero.
 *  - [Variant.MAP]: the onboarding "Track on Map" card (328×392) — white roads, two green
 *    blocks, and the blue route that draws itself (`draw 3.2s ease-in-out infinite`:
 *    stroke-dashoffset 320 → 0 over the first 65%, then holds).
 */
class RoadsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Variant { SPLASH, HOME_HERO, MAP }

    private class Road(val d: String, val width: Float, val color: Int)

    private val variant: Variant
    private val roads: List<Road>
    private val designW: Float
    private val designH: Float
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val scaled = Path()
    private val sources: List<Path>
    private val blocks = mutableListOf<RectF>()
    private var route: Path? = null
    private var routeLength = 0f
    private var routeProgress = 1f
    private var animator: Animator? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.RoadsView)
        variant = Variant.values()[a.getInt(R.styleable.RoadsView_roadsVariant, 0)]
        a.recycle()
        val white = 0xFFFFFFFF.toInt()
        when (variant) {
            Variant.SPLASH -> {
                designW = 360f; designH = 780f
                roads = listOf(
                    Road("M-20 520 C80 470 140 540 220 430 S340 300 400 320", 18f, ContextCompat.getColor(context, R.color.ds_road)),
                    Road("M120 -20 C130 160 200 260 190 380 S160 640 200 820", 14f, ContextCompat.getColor(context, R.color.ds_road)),
                    Road("M-20 200 L400 280", 10f, ContextCompat.getColor(context, R.color.ds_road2)),
                )
            }
            Variant.HOME_HERO -> {
                designW = 360f; designH = 300f
                roads = listOf(
                    Road("M-10 240 C80 200 140 250 220 170 S330 90 380 110", 12f, 0x24FFFFFF),
                    Road("M250 -10 C240 80 280 160 270 310", 9f, 0x1AFFFFFF),
                )
            }
            Variant.MAP -> {
                designW = 328f; designH = 392f
                roads = listOf(
                    Road("M-10 290 C60 250 120 300 180 220 S300 120 340 140", 16f, white),
                    Road("M90 -10 C100 90 160 160 150 250 S120 360 150 410", 12f, white),
                    Road("M-10 120 L340 170", 9f, white),
                    Road("M230 -10 L250 410", 7f, white),
                )
                blocks += RectF(180f, 40f, 220f, 92f)
                blocks += RectF(30f, 160f, 76f, 200f)
                route = PathParser.createPathFromPathData("M70 320 C110 300 130 260 150 240 S200 190 236 128")
                fill.color = ContextCompat.getColor(context, R.color.ds_map_block)
            }
        }
        sources = roads.map { PathParser.createPathFromPathData(it.d) }
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val sx = width / designW
        val sy = height / designH
        matrix.setScale(sx, sy)
        val s = minOf(sx, sy)

        roads.forEachIndexed { i, road ->
            sources[i].transform(matrix, scaled)
            paint.pathEffect = null
            paint.strokeWidth = road.width * s
            paint.color = road.color
            canvas.drawPath(scaled, paint)
        }
        blocks.forEach { b ->
            val r = 8f * s
            canvas.drawRoundRect(b.left * sx, b.top * sy, b.right * sx, b.bottom * sy, r, r, fill)
        }
        route?.let { src ->
            src.transform(matrix, scaled)
            val length = android.graphics.PathMeasure(scaled, false).length
            routeLength = length
            paint.strokeWidth = 4f * s
            paint.color = ContextCompat.getColor(context, R.color.ds_primary)
            // stroke-dasharray 320 / dashoffset 320→0, in the design's own units.
            val dash = 320f * s
            paint.pathEffect = DashPathEffect(floatArrayOf(dash, dash), dash * (1f - routeProgress))
            canvas.drawPath(scaled, paint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (route == null || DesignMotion.animationsDisabled(context)) return
        animator?.cancel()
        // `draw`: 0% offset 320 → 65% offset 0 → 100% hold, ease-in-out, 3.2s, infinite.
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = DesignMotion.LINEAR
            addUpdateListener {
                val t = it.animatedValue as Float
                val segment = (t / 0.65f).coerceAtMost(1f)
                routeProgress = DesignMotion.EASE_IN_OUT.getInterpolation(segment)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
