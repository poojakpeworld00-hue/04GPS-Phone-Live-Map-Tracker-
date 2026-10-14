package com.find.phone.gps.live.location.tracker.monetize.render

import android.animation.ValueAnimator
import android.view.View
import android.widget.TextView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.find.phone.gps.live.location.tracker.R
import java.util.Locale
import kotlin.math.floor

object EmbeddedSpotGuise {
    fun bind(adView: NativeAdView, nativeAd: NativeAd) {
        enactCardSpacing(adView)
        enactRemoteColors(adView)
        wireStars(adView, nativeAd)
        adView.findViewById<TextView?>(R.id.ad_call_to_action)?.let { cta ->
            if (cta.tag == "glow") glow(cta)
        }
    }

    private fun enactCardSpacing(adView: NativeAdView) {
        val res = adView.resources
        val h = res.getDimensionPixelSize(R.dimen.ad_card_margin_h)
        val top = res.getDimensionPixelSize(R.dimen.ad_card_margin_top)
        val bottom = res.getDimensionPixelSize(R.dimen.ad_card_margin_bottom)
        val params = (adView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
            ?: android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        params.setMargins(h, top, h, bottom)
        adView.layoutParams = params

        adView.elevation = res.getDimension(R.dimen.ad_card_elevation)

        adView.post { fitSideMargins(adView, h) }
    }

    private fun fitSideMargins(adView: NativeAdView, target: Int) {
        val parent = adView.parent as? View ?: return
        val root = adView.rootView
        val windowWidth = root.width
        if (parent.width <= 0 || windowWidth <= 0) return

        var x = 0
        var v: View? = parent
        while (v != null && v !== root) {
            x += v.left
            v = v.parent as? View
        }
        val insetLeft = x.coerceAtLeast(0)
        val insetRight = (windowWidth - x - parent.width).coerceAtLeast(0)
        val params = adView.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        val left = (target - insetLeft).coerceAtLeast(0)
        val right = (target - insetRight).coerceAtLeast(0)
        if (params.leftMargin == left && params.rightMargin == right) return
        params.leftMargin = left
        params.rightMargin = right
        adView.layoutParams = params
    }

    private fun enactRemoteColors(adView: NativeAdView) {
        val pref = com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker.getInstance(adView.context)
        fun rc(key: String): Int? = pref.getString(key)?.trim()?.takeIf { it.isNotEmpty() }?.let {
            try { android.graphics.Color.parseColor(it) } catch (_: IllegalArgumentException) { null }
        }
        rc("NativeBgColor")?.let { fill(adView, it) }
        rc("NativetxtColor")?.let { txt ->
            adView.findViewById<TextView?>(R.id.ad_headline)?.setTextColor(txt)
            adView.findViewById<TextView?>(R.id.ad_body)?.setTextColor(txt)
        }
        adView.findViewById<TextView?>(R.id.ad_call_to_action)?.let { cta ->
            SpotButtonTheme.apply(cta)
            rc("NativebtntxtColor")?.let { btnTxt ->
                cta.setTextColor(btnTxt)
                cta.compoundDrawablesRelative.forEach { d -> d?.mutate()?.setTint(btnTxt) }
            }
        }
    }

    private fun fill(v: View, color: Int) {
        when (val bg = v.background?.mutate()) {
            is android.graphics.drawable.GradientDrawable -> bg.setColor(color)
            null -> v.setBackgroundColor(color)
            else -> v.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        }
    }

    private fun wireStars(adView: NativeAdView, nativeAd: NativeAd) {
        val stars = adView.findViewById<TextView?>(R.id.ad_stars) ?: return
        val rating = nativeAd.starRating
        if (rating == null || rating <= 0.0) {
            stars.visibility = View.GONE
            return
        }
        val value = String.format(Locale.getDefault(), "%.1f", rating)
        stars.text = if (adView.findViewById<View?>(R.id.ad_media) == null) {
            val full = floor(rating + 0.5).toInt().coerceIn(0, 5)
            android.text.SpannableStringBuilder().apply {
                append("★".repeat(5))
                setSpan(android.text.style.ForegroundColorSpan(color(stars, R.color.ad_star)), 0, full, 0)
                if (full < 5) setSpan(
                    android.text.style.ForegroundColorSpan(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(color(stars, R.color.ad_star), 0x99)
                    ), full, 5, 0
                )
                append("  ")
                val start = length
                append(value)
                setSpan(android.text.style.ForegroundColorSpan(color(stars, R.color.lk_ink)), start, length, 0)
            }
        } else {
            android.text.SpannableStringBuilder().apply {
                append("★ ")
                setSpan(android.text.style.ForegroundColorSpan(color(stars, R.color.ad_star)), 0, 1, 0)
                append(listOfNotNull(value, nativeAd.price?.takeIf { it.isNotBlank() }).joinToString(" · "))
            }
        }
        stars.visibility = View.VISIBLE
        adView.starRatingView = stars
    }

    private fun glow(cta: TextView) {
        val dp = cta.resources.displayMetrics.density
        (cta.getTag(R.id.ad_call_to_action) as? ValueAnimator)?.cancel()
        val anim = ValueAnimator.ofFloat(4 * dp, 9 * dp).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { cta.elevation = it.animatedValue as Float }
        }
        cta.setTag(R.id.ad_call_to_action, anim)
        cta.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = anim.start()
            override fun onViewDetachedFromWindow(v: View) = anim.cancel()
        })
        if (cta.isAttachedToWindow) anim.start()
    }

    private fun color(v: View, res: Int) = androidx.core.content.ContextCompat.getColor(v.context, res)
}
