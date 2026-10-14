package com.find.my.phone.gps.location.tracker.ui.design

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.find.my.phone.gps.location.tracker.R

/**
 * The design's teardrop map pin: a square with `border-radius: 50% 50% 50% 0` rotated -45°
 * (point down), filled with the 135° signal gradient (or a solid colour) and a white dot in
 * the middle. The view's bounds are the pin's bounding box; the shadow is drawn by the paint.
 */
class PinShapeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val rect = RectF()
    private val startColor: Int
    private val endColor: Int
    private val dotSize: Float
    private val shadowColor: Int
    private val borderColor: Int
    private val borderWidth: Float

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PinShapeView)
        startColor = a.getColor(R.styleable.PinShapeView_pinStartColor, 0xFF5C7DFF.toInt())
        endColor = a.getColor(R.styleable.PinShapeView_pinEndColor, 0xFF002EFE.toInt())
        dot.color = a.getColor(R.styleable.PinShapeView_pinDotColor, 0xFFFFFFFF.toInt())
        dotSize = a.getDimension(R.styleable.PinShapeView_pinDotSize, 0f)
        shadowColor = a.getColor(R.styleable.PinShapeView_pinShadowColor, 0)
        borderColor = a.getColor(R.styleable.PinShapeView_pinBorderColor, 0)
        borderWidth = a.getDimension(R.styleable.PinShapeView_pinBorderWidth, 0f)
        a.recycle()
        border.color = borderColor
        border.strokeWidth = borderWidth
        if (shadowColor != 0) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val side = minOf(w, h).toFloat()
        // Inset so the -45° rotation (diagonal ≈ 1.21 × side for the pointed corner) and the
        // soft shadow both stay inside the view — a software layer clips to its bounds.
        val inset = side * (if (shadowColor != 0) 0.2f else 0.1f)
        rect.set(inset, inset, side - inset, side - inset)
        val r = rect.width() / 2f
        path.reset()
        // Rounded on three corners, square on the bottom-left: that corner becomes the tip.
        path.addRoundRect(rect, floatArrayOf(r, r, r, r, r, r, 0f, 0f), Path.Direction.CW)
        body.shader = if (startColor != endColor) {
            LinearGradient(rect.left, rect.top, rect.right, rect.bottom, startColor, endColor, Shader.TileMode.CLAMP)
        } else {
            null
        }
        if (startColor == endColor) body.color = startColor
        if (shadowColor != 0) body.setShadowLayer(side * 0.12f, 0f, side * 0.08f, shadowColor)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = rect.centerX()
        val cy = rect.centerY()
        canvas.save()
        canvas.rotate(-45f, cx, cy)
        canvas.drawPath(path, body)
        if (borderColor != 0 && borderWidth > 0f) canvas.drawPath(path, border)
        canvas.restore()
        if (dotSize > 0f) canvas.drawCircle(cx, cy, dotSize / 2f, dot)
    }
}
