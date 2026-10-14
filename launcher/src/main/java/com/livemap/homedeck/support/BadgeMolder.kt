package com.livemap.homedeck.support

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

object BadgeMolder {
    enum class Tray { WHITE, DOMINANT;
        companion object {
            fun parse(raw: String?): Tray? = entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
        }
    }

    private const val OPAQUE_INSET = 18f / 108f

    private const val ART_INSET = 25f / 108f

    private const val SAMPLE_CANVAS = 128

    private const val SAMPLES = 16

    private const val OPAQUE_ALPHA = 250

    fun shape(drawable: Drawable, tray: Tray): Drawable = runCatching {
        val shaped = if (drawable is AdaptiveIconDrawable) {
            drawable.withTrayIfClear(tray)
        } else {
            val bitmap = drawable.toSampledBitmap() ?: return drawable
            val opaqueSquare = bitmap.cornersArePainted()
            val background = ColorDrawable(if (opaqueSquare) bitmap.edgeColor() else trayColor(bitmap, tray))
            val foreground = InsetDrawable(drawable, if (opaqueSquare) OPAQUE_INSET else ART_INSET)
            AdaptiveIconDrawable(background, foreground)
        }

        if (shaped !== drawable && !shaped.rendersArt()) drawable else shaped
    }.getOrDefault(drawable)

    private const val LOGO_INSET = 16f / 108f

    private const val LOGO_EDGE_ALPHA = 128

    fun shapeLogo(resources: Resources, logo: Bitmap, tray: Tray): Drawable = runCatching {
        val last = logo.width - 1
        val midX = logo.width / 2
        val midY = logo.height / 2
        val lastY = logo.height - 1
        val square = listOf(midX to 0, 0 to midY, last to midY, midX to lastY)
            .all { (x, y) -> Color.alpha(logo.getPixel(x, y)) >= LOGO_EDGE_ALPHA }
        if (!square) return shape(BitmapDrawable(resources, logo), tray)
        val edge = logo.ringColor()
        val flat = Bitmap.createBitmap(logo.width, logo.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(edge); drawBitmap(logo, 0f, 0f, null) }
        }
        AdaptiveIconDrawable(ColorDrawable(edge), InsetDrawable(BitmapDrawable(resources, flat), LOGO_INSET))
    }.getOrElse { BitmapDrawable(resources, logo) }

    private fun Bitmap.ringColor(): Int {
        val inset = (width / 32).coerceAtLeast(1)
        val lo = inset
        val hi = width - 1 - inset
        val ring = ArrayList<Int>(SAMPLES * 4)
        for (i in 0 until SAMPLES) {
            val p = lo + i * (hi - lo) / (SAMPLES - 1)
            ring += getPixel(p, lo); ring += getPixel(p, hi); ring += getPixel(lo, p); ring += getPixel(hi, p)
        }
        return average(ring) ?: Color.WHITE
    }

    private fun Drawable.rendersArt(): Boolean {
        val bitmap = toSampledBitmap() ?: return false
        val last = bitmap.width - 1
        val step = (last / (SAMPLES - 1)).coerceAtLeast(1)
        var first: Int? = null
        for (x in 0..last step step) for (y in 0..last step step) {
            val c = bitmap.getPixel(x, y)
            if (Color.alpha(c) < OPAQUE_ALPHA) continue
            val rgb = c and 0xFFFFFF
            if (first == null) first = rgb else if (kotlin.math.abs(Color.red(rgb) - Color.red(first)) + kotlin.math.abs(Color.green(rgb) - Color.green(first)) + kotlin.math.abs(Color.blue(rgb) - Color.blue(first)) > 24) return true
        }
        return false
    }

    private fun AdaptiveIconDrawable.withTrayIfClear(tray: Tray): Drawable {
        val backgroundPainted = background?.toSampledBitmap()?.cornersArePainted() ?: false
        if (backgroundPainted) return this
        val art = foreground ?: return this
        val artBitmap = art.toSampledBitmap()
        val color = if (artBitmap != null) trayColor(artBitmap, tray) else Color.WHITE
        val ownArt = art.constantState?.newDrawable()?.mutate() ?: art
        return AdaptiveIconDrawable(ColorDrawable(color), ownArt)
    }

    private fun trayColor(bitmap: Bitmap, tray: Tray): Int = when (tray) {
        Tray.WHITE -> Color.WHITE

        Tray.DOMINANT -> ColorUtils.blendARGB(bitmap.bodyColor(), Color.WHITE, 0.55f)
    }

    private fun Drawable.toSampledBitmap(): Bitmap? {
        val w = intrinsicWidth
        val h = intrinsicHeight
        val size = if (w > 0 && h > 0) min(max(w, h), SAMPLE_CANVAS) else SAMPLE_CANVAS
        return runCatching {
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                val saved = bounds.let { android.graphics.Rect(it) }
                setBounds(0, 0, size, size)
                draw(canvas)
                bounds = saved
            }
        }.getOrNull()
    }

    private fun Bitmap.cornersArePainted(): Boolean {
        val last = width - 1
        val mid = width / 2
        val points = listOf(0 to 0, last to 0, 0 to last, last to last, mid to 0, 0 to mid, last to mid, mid to last)
        return points.all { (x, y) -> Color.alpha(getPixel(x, y)) >= OPAQUE_ALPHA }
    }

    private fun Bitmap.edgeColor(): Int {
        val last = width - 1
        val ring = ArrayList<Int>(SAMPLES * 4)
        for (i in 0 until SAMPLES) {
            val p = i * last / (SAMPLES - 1)
            ring += getPixel(p, 0); ring += getPixel(p, last); ring += getPixel(0, p); ring += getPixel(last, p)
        }
        return average(ring) ?: Color.WHITE
    }

    private fun Bitmap.bodyColor(): Int {
        val last = width - 1
        val body = ArrayList<Int>(SAMPLES * SAMPLES)
        for (i in 0 until SAMPLES) for (j in 0 until SAMPLES) {
            body += getPixel(i * last / (SAMPLES - 1), j * last / (SAMPLES - 1))
        }
        return average(body) ?: Color.WHITE
    }

    private fun average(pixels: List<Int>): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (c in pixels) {
            if (Color.alpha(c) < OPAQUE_ALPHA) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return null
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
