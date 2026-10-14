package com.find.my.phone.gps.location.tracker.adcast.presentation

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault

/**
 * The ad call-to-action drawn like the app's primary button (`Ds.Button.Primary`): 18dp corners,
 * a top-left → bottom-right gradient, Figtree Bold, and — for a full-width CTA — the same 56dp
 * height and primary-coloured shadow, so "Install" under an ad and "Get Started" under it read
 * as one family.
 *
 * Colours stay Remote Config driven. The native theme's `btnColor` is the gradient start; an
 * optional `btnColorEnd` is its end, and when that key is absent the end is a lighter tone of
 * the start (so a single RC colour still renders as a gradient). Without any RC colour the
 * design's own primary → cyan pair is used. `btnText` remains the label colour.
 */
object AdCtaStyle {

    private const val RADIUS_DP = 18f
    private const val FULL_HEIGHT_DP = 56f

    fun apply(cta: TextView) {
        val ctx = cta.context
        val pref = AdsVault.getInstance(ctx)
        val rcStart = parse(pref.getString("NativebtnColor"))
        val rcEnd = parse(pref.getString("NativebtnColorEnd"))
        val start = rcStart ?: ContextCompat.getColor(ctx, R.color.ds_primary)
        val end = rcEnd ?: if (rcStart == null) ContextCompat.getColor(ctx, R.color.ds_cyan) else lighter(start)

        val radius = dp(cta, RADIUS_DP)
        fun shape(a: Int, b: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b))
            .apply { cornerRadius = radius }

        // A pressed state a shade darker, like ds_btn_primary_selector_r18.
        val background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(darker(start), darker(end)))
            addState(intArrayOf(), shape(start, end))
        }
        cta.backgroundTintList = null // the ported layouts tint their flat shape; the gradient owns the fill now
        cta.background = background
        cta.typeface = ResourcesCompat.getFont(ctx, R.font.figtree_bold) ?: cta.typeface
        cta.setTextColor(parse(pref.getString("NativebtntxtColor")) ?: Color.WHITE)
        cta.isAllCaps = false

        val fullWidth = cta.layoutParams?.width == ViewGroup.LayoutParams.MATCH_PARENT
        if (fullWidth) {
            cta.layoutParams = cta.layoutParams.apply { height = dp(cta, FULL_HEIGHT_DP).toInt() }
            cta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            cta.elevation = dp(cta, 6f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cta.outlineSpotShadowColor = start
                cta.outlineAmbientShadowColor = start
            }
        }
    }

    private fun parse(raw: String?): Int? = raw?.trim()?.takeIf { it.isNotEmpty() }?.let {
        try { Color.parseColor(it) } catch (_: IllegalArgumentException) { null }
    }

    /** The gradient end derived from a single RC colour: the same hue, lifted in lightness. */
    private fun lighter(color: Int): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] + 0.05f).coerceAtMost(1f)
        hsl[2] = (hsl[2] + 0.16f).coerceAtMost(0.92f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun darker(color: Int): Int = ColorUtils.blendARGB(color, Color.BLACK, 0.18f)

    private fun dp(v: TextView, value: Float): Float = value * v.resources.displayMetrics.density
}
