package com.find.phone.gps.live.location.tracker.screens.craft

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
import com.find.phone.gps.live.location.tracker.R

class WorldOrbView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val bitmap = BitmapFactory.decodeResource(resources, R.drawable.lv_earth_texture)
    private val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.CLAMP)
    private val matrix = Matrix()
    private val globe = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = this@WorldOrbView.shader }

    private val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22124B8A }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x5579C3FF
    }

    private var phase = 0f
    private val animators = mutableListOf<Animator>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val d = minOf(w, h).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = d / 2f

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
        if (CraftMovement.animationsDisabled(context)) return
        CraftMovement.abortAll(animators)
        animators += ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 12_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = CraftMovement.LINEAR
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        CraftMovement.abortAll(animators)
        super.onDetachedFromWindow()
    }
}
