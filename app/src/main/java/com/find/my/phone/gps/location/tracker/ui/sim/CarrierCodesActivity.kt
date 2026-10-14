package com.find.my.phone.gps.location.tracker.ui.sim

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.sim.MySimNumbers
import com.find.my.phone.gps.location.tracker.databinding.TileCarrierCodeBinding
import com.find.my.phone.gps.location.tracker.databinding.ViewCarrierCodesBinding
import com.find.my.phone.gps.location.tracker.util.isNightMode

/**
 * SIM Tools "Carrier codes" (design 1c). Shows USSD / short codes for the detected carrier, grouped
 * by section; tapping one opens the dialer (ACTION_DIAL) with the code filled in — never dials
 * automatically. A carrier tab per SIM when two are present.
 */
class CarrierCodesActivity : CanvasActivity<ViewCarrierCodesBinding>() {

    override val layoutId: Int = R.layout.view_carrier_codes

    private data class SimTab(val slot: Int, val carrier: String)

    private var tabs: List<SimTab> = emptyList()
    private var selected = 0

    override fun initView() {
        // Dark icons on the light canvas, light icons in dark mode.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.codesRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }

        tabs = MySimNumbers.read(this).map { SimTab(it.slot, it.carrier) }
        val wantSlot = intent.getIntExtra(EXTRA_SLOT, -1)
        selected = tabs.indexOfFirst { it.slot == wantSlot }.coerceAtLeast(0)

        buildTabs()
        render()
    }

    private fun buildTabs() {
        binding.carrierTabs.removeAllViews()
        if (tabs.size < 2) {
            binding.carrierTabs.visibility = View.GONE
            return
        }
        tabs.forEachIndexed { i, tab ->
            val view = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).also { if (i > 0) it.marginStart = dp(4) }
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 13f
                text = tab.carrier
                setOnClickListener { select(i) }
            }
            binding.carrierTabs.addView(view)
        }
        styleTabs()
    }

    private fun styleTabs() {
        for (i in 0 until binding.carrierTabs.childCount) {
            val tab = binding.carrierTabs.getChildAt(i) as TextView
            val on = i == selected
            tab.setTextColor(ContextCompat.getColor(this, if (on) R.color.ds_primary else R.color.ds_muted))
            tab.typeface = ResourcesCompat.getFont(this, if (on) R.font.figtree_bold else R.font.figtree_semibold)
            tab.background = if (on) ContextCompat.getDrawable(this, R.drawable.ds_seg_thumb_r10) else null
            tab.elevation = if (on) dp(2).toFloat() else 0f
        }
    }

    private fun select(i: Int) {
        if (i == selected) return
        selected = i
        styleTabs()
        render()
    }

    private fun render() {
        val tab = tabs.getOrNull(selected)
        binding.tvDetected.text = if (tab != null)
            getString(R.string.carrier_codes_detected_fmt, tab.carrier, tab.slot)
        else getString(R.string.carrier_codes_title)

        binding.sections.removeAllViews()
        val inflater = LayoutInflater.from(this)
        CarrierCodes.forCarrier(tab?.carrier).forEachIndexed { index, section ->
            binding.sections.addView(sectionLabel(section.title, index == 0))
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ds_card_border_r20)
            }
            section.codes.forEachIndexed { i, code ->
                if (i > 0) card.addView(divider())
                card.addView(codeRow(inflater, card, code))
            }
            binding.sections.addView(card)
        }
    }

    private fun sectionLabel(title: String, first: Boolean): TextView = TextView(this).apply {
        text = title
        isAllCaps = true
        letterSpacing = 0.12f
        typeface = ResourcesCompat.getFont(this@CarrierCodesActivity, R.font.nunito_extrabold)
        textSize = 11f
        setTextColor(ContextCompat.getColor(this@CarrierCodesActivity, R.color.ds_muted))
        setPadding(dp(6), dp(if (first) 4 else 16), dp(6), dp(8))
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
            it.marginStart = dp(14); it.marginEnd = dp(14)
        }
        setBackgroundColor(ContextCompat.getColor(this@CarrierCodesActivity, R.color.ds_divider))
    }

    private fun codeRow(inflater: LayoutInflater, parent: LinearLayout, code: CarrierCodes.Code): View =
        TileCarrierCodeBinding.inflate(inflater, parent, false).apply {
            tvTitle.text = code.title
            tvSubtitle.text = code.subtitle
            tvCode.text = code.code
            codeRow.setOnClickListener { dial(code.code) }
        }.root

    private fun dial(code: String) {
        logKeyEvent("CarrierCodes_Dial")
        AppOpenAdRegistry.skipNextAppOpenAd = true
        runCatching {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(code))))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_SLOT = "slot"

        fun intent(context: Context, slot: Int): Intent =
            Intent(context, CarrierCodesActivity::class.java).putExtra(EXTRA_SLOT, slot)
    }
}
