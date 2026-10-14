package com.find.my.phone.gps.location.tracker.ui.design

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.find.my.phone.gps.location.tracker.R

/**
 * The redesign's rotating Earth. A fast, 60fps look-alike of the design's `earth-globe.js`
 * orthographic sphere: the equirectangular texture ([R.drawable.ds_earth_texture]) is scrolled
 * horizontally behind a circular clip (the design's `earthroll` → `translateX(-50%)`), then
 * shaded with a radial terminator and a top-left highlight so the flat strip reads as a lit
 * ball, with a soft atmosphere ring at the rim.
 *
 * One full scroll takes [R.integer]-free 12 s (the design's `speed="12"`). Honours
 * "remove animations" via [DesignMotion.animationsDisabled] — it then holds a still frame.
 */
class EarthGlobeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bitmap = BitmapFactory.decodeResource(resources, R.drawable.ds_earth_texture)
    private val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val globe = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@EarthGlobeView.shader }

    // A faint blue cast so the daylight texture matches the design's deep-blue ocean look.
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22124B8A }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x5579C3FF
    }

    private var phase = 0f // 0..1 across one texture width
    private val animators = mutableListOf<Animator>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = minOf(w, h).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = d / 2f
        // Terminator: bright toward the top-left light, darkening to the lower-right rim.
        shade.shader = RadialGradient(
            cx - r * 0.28f, cy - r * 0.3f, r * 1.25f,
            intArrayOf(0x00000000, 0x11000A1E, 0x66001024, 0xB2000818.toInt()),
            floatArrayOf(0f, 0.55f, 0.82f, 1f), Shader.TileMode.CLAMP,
        )
        highlight.shader = RadialGradient(
            cx - r * 0.34f, cy - r * 0.36f, r * 0.7f,
            intArrayOf(0x4DFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        rim.strokeWidth = r * 0.03f
    }

    override fun onDraw(canvas: Canvas) {
        val d = minOf(width, height).toFloat()
        if (d <= 0f || bitmap.width == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = d / 2f

        // Scale the texture so its full height maps to the circle (uniform, no distortion),
        // then scroll X; REPEAT tiles it seamlessly around the "equator".
        val scale = d / bitmap.height
        val scaledWidth = bitmap.width * scale
        matrix.reset()
        matrix.setScale(scale, scale)
        matrix.postTranslate(-phase * scaledWidth, cy - bitmap.height * scale / 2f)
        shader.setLocalMatrix(matrix)

        canvas.drawCircle(cx, cy, r, globe)
        canvas.drawCircle(cx, cy, r, tint)
        canvas.drawCircle(cx, cy, r, highlight)
        canvas.drawCircle(cx, cy, r, shade)
        canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2f, rim)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (DesignMotion.animationsDisabled(context)) return
        DesignMotion.cancelAll(animators)
        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 12_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = DesignMotion.LINEAR
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        DesignMotion.cancelAll(animators)
        super.onDetachedFromWindow()
    }
}
