package com.find.my.phone.gps.location.tracker.ui.lookup

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/**
 * The number's registered area on the map: a tinted circle of [radiusMeters] (skipped when 0,
 * i.e. only the country is known), a ring that keeps pulsing out from the centre, and the
 * centre dot with a white rim.
 */
class AreaPulseOverlay(
    private val map: MapView,
    private val center: GeoPoint,
    private val radiusMeters: Double,
    color: Int,
    private val density: Float,
) : Overlay() {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ColorUtils.setAlphaComponent(color, 38) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        this.color = ColorUtils.setAlphaComponent(color, 150)
    }
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        this.color = color
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE }
    private val px = Point()

    private var phase = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            map.postInvalidate()
        }
    }

    fun start() = animator.start()

    fun stop() = animator.cancel()

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val projection = mapView.projection
        projection.toPixels(center, px)
        val cx = px.x.toFloat()
        val cy = px.y.toFloat()

        val areaPx = if (radiusMeters > 0) projection.metersToPixels(radiusMeters.toFloat()) else 0f
        if (areaPx > 0f) {
            canvas.drawCircle(cx, cy, areaPx, fill)
            canvas.drawCircle(cx, cy, areaPx, edge)
        }

        // The pulse grows to the area edge, or to a fixed 48dp when the area is tiny / unknown.
        val pulseMax = areaPx.coerceAtLeast(48 * density)
        pulse.alpha = ((1f - phase) * 200).toInt()
        canvas.drawCircle(cx, cy, 8 * density + (pulseMax - 8 * density) * phase, pulse)

        canvas.drawCircle(cx, cy, 9 * density, rim)
        canvas.drawCircle(cx, cy, 6.5f * density, dot)
    }

    override fun onDetach(mapView: MapView?) {
        animator.cancel()
        super.onDetach(mapView)
    }
}
