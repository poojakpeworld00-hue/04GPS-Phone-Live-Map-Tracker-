package com.find.phone.gps.live.location.tracker.screens.finder

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.render.BonusTeaser
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.inquiry.NumInquiry
import com.find.phone.gps.live.location.tracker.store.inquiry.NumDigest
import com.find.phone.gps.live.location.tracker.store.inquiry.ExposedTitles
import com.find.phone.gps.live.location.tracker.store.plus.PlusCheckout
import com.find.phone.gps.live.location.tracker.databinding.CellDetailActionBinding
import com.find.phone.gps.live.location.tracker.databinding.CellDetailCellBinding
import com.find.phone.gps.live.location.tracker.databinding.CellDetailNameBinding
import com.find.phone.gps.live.location.tracker.databinding.ScreenNumberDetailBinding
import com.find.phone.gps.live.location.tracker.screens.people.BuddyAdapter
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

class NumProfile2Activity : StageActivity<ScreenNumberDetailBinding>() {
    override val layoutId: Int = R.layout.screen_number_detail

    private lateinit var digest: NumDigest
    private var areaPulse: SectorThrobVeil? = null
    private var statusInset = 0
    private var adBusy = false
    private var expandNames = false

    override fun onCreate(savedInstanceState: Bundle?) {
        StreetCharts.init(this)
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        digest = IntentCompat.getSerializableExtra(intent, EXTRA_REPORT, NumDigest::class.java) ?: run {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        trackInsets()

        with(binding) {
            actBack.setOnClickListener { goBack() }
            actShare.setOnClickListener { shareReport() }
            actRetry.setOnClickListener { relookup() }
            chartTap.setOnClickListener { openBigMap() }
            actFullMap.setOnClickListener { openBigMap() }
            CraftMovement.pressScale(actFullMap)
            lvIdentityCard.clipToOutline = false
        }

        setupTile(binding.lvActCall, R.drawable.lv_ic_call, R.string.ds_act_call, primary = true) { siteCall(digest.e164) }
        setupTile(binding.lvActSms, R.drawable.lv_ic_message, R.string.ds_act_sms) { textNumber() }
        setupTile(binding.lvActMap, R.drawable.lv_ic_navigation, R.string.ds_act_map) { openBigMap() }
        setupTile(binding.lvActCopy, R.drawable.lv_ic_copy, R.string.ds_act_copy) { copyNumber() }

        StreetCharts.style(binding.chartView, interactive = false)
        renderAll()
        dropPin()
    }

    private fun trackInsets() {
        val baseTopPad = binding.chartTopBar.paddingTop
        val baseMapHeight = binding.chartBox.layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(binding.infoRoot) { root, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.chartTopBar.updatePadding(top = baseTopPad + sb.top)
            binding.chartBox.updateLayoutParams { height = baseMapHeight + sb.top }
            if (statusInset != sb.top) {
                statusInset = sb.top
                if (::digest.isInitialized) offsetPin()
            }
            root.updatePadding(left = sb.left, right = sb.right, bottom = sb.bottom)
            insets
        }
    }

    private fun cityRegion(d: NumDigest): String = listOfNotNull(d.city, d.state).distinct().joinToString(", ")

    private fun renderAll() {
        val d = digest
        binding.txtIso.text = d.iso2
        binding.txtNumber.text = d.international
        binding.txtPlace.text = cityRegion(d).ifEmpty { d.countryName }
        binding.txtMapPlace.text = binding.txtPlace.text

        if (d.spam) {
            verdictPill(R.string.ds_verdict_spam, R.drawable.lv_pill_danger, R.color.ds_danger)
        } else if (d.valid) {
            verdictPill(R.string.ds_result_valid_short, R.drawable.lv_pill_success_soft, R.color.ds_success)
        } else {
            verdictPill(R.string.ds_verdict_unverified, R.drawable.lv_pill_warn, R.color.ds_warn)
        }
        val lineLabel = d.lineType.label(this)
        binding.pillLine.text = lineLabel
        binding.pillLine.isVisible = lineLabel != null
        binding.lvOfflineCard.isVisible = !d.serverReached

        fillFact(binding.boxCountry, R.string.ds_label_country, d.countryName)
        fillFact(binding.boxPlace, R.string.ds_detail_city_region, cityRegion(d))
        fillFact(binding.boxCarrier, R.string.ds_fact_carrier, d.carrier)
        fillFact(binding.boxLine, R.string.ds_fact_line, lineLabel)

        renderNames()
    }

    private fun verdictPill(@StringRes caption: Int, @DrawableRes bg: Int, tint: Int) = with(binding.pillVerdict) {
        setText(caption)
        setBackgroundResource(bg)
        setTextColor(ContextCompat.getColor(this@NumProfile2Activity, tint))
    }

    private fun fillFact(box: CellDetailCellBinding, @StringRes caption: Int, value: String?) {
        box.txtLabel.setText(caption)
        val missing = value.isNullOrBlank()
        box.txtValue.text = if (missing) getString(R.string.ds_not_available) else value
        box.txtValue.setTextColor(ContextCompat.getColor(this, if (missing) R.color.ds_muted else R.color.ds_ink))
    }

    private fun setupTile(
        tile: CellDetailActionBinding,
        @DrawableRes glyph: Int,
        @StringRes caption: Int,
        primary: Boolean = false,
        onTap: () -> Unit,
    ) {
        tile.imgIcon.setImageResource(glyph)
        tile.txtLabel.setText(caption)
        if (primary) {
            tile.badgeBox.setBackgroundResource(R.drawable.lv_tile_primary_r16)
            tile.imgIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))
        }
        CraftMovement.pressScale(tile.lvActionRoot)
        tile.lvActionRoot.setOnClickListener { onTap() }
    }

    private val knownNames: List<String>
        get() = buildList {
            digest.name?.let { add(it) }
            addAll(digest.otherNames)
        }

    private fun nameVisible(position: Int, name: String): Boolean {
        if (position == 0 && digest.name != null) return true
        if (PlusCheckout.isPremium(this)) return true
        return ExposedTitles.isRevealed(this, digest.e164, name)
    }

    private fun renderNames() {
        val everyName = knownNames
        binding.lvNamesBlock.isVisible = everyName.isNotEmpty()
        binding.lvNamesList.removeAllViews()

        val listed = when {
            expandNames -> everyName
            else -> everyName.take(NAMES_PREVIEW)
        }
        with(binding.actShowAllNames) {
            isVisible = listed.size < everyName.size
            text = getString(R.string.ds_names_show_all, everyName.size)
            setOnClickListener {
                expandNames = true
                renderNames()
            }
        }
        val inflater = LayoutInflater.from(this)
        for ((pos, name) in listed.withIndex()) {
            val row = CellDetailNameBinding.inflate(inflater, binding.lvNamesList, true)
            row.txtName.text = name
            row.lvNameDivider.isVisible = pos < listed.lastIndex
            styleName(row, name, nameVisible(pos, name))
            row.actReveal.setOnClickListener { watchForName(row, name) }
        }
        refreshNameTally()
        val anyHidden = everyName.indices.any { !nameVisible(it, everyName[it]) }
        if (anyHidden) BonusTeaser.preload(this)
    }

    private fun styleName(row: CellDetailNameBinding, name: String, revealed: Boolean) {
        row.txtName.setBlurred(!revealed)
        row.txtRevealed.isVisible = revealed
        row.actReveal.isVisible = !revealed
        if (!revealed) {
            row.txtAvatar.text = ""
            row.txtAvatar.setBackgroundResource(R.drawable.lv_circle_track)
            return
        }
        val palette = BuddyAdapter.AVATARS
        row.txtAvatar.text = monogram(name)
        row.txtAvatar.setBackgroundResource(palette[abs(name.hashCode()) % palette.size])
    }

    private fun monogram(name: String): String {
        val sb = StringBuilder()
        name.split(' ').filter { it.isNotBlank() }.take(2).forEach { word ->
            word.firstOrNull { it.isLetterOrDigit() }?.let { sb.append(it.uppercaseChar()) }
        }
        return sb.toString()
    }

    private fun refreshNameTally() {
        val everyName = knownNames
        val shownCount = everyName.indices.count { nameVisible(it, everyName[it]) }
        binding.txtRevealedCount.text = getString(R.string.ds_names_count, shownCount, everyName.size)
        binding.txtNamesNote.isVisible = shownCount < everyName.size
    }

    private fun setRevealBusy(row: CellDetailNameBinding, busy: Boolean) {
        adBusy = busy
        row.txtReveal.isInvisible = busy
        row.spinReveal.isVisible = busy
    }

    private fun watchForName(row: CellDetailNameBinding, name: String) {
        if (adBusy) return
        setRevealBusy(row, true)
        BonusTeaser().show(this) { outcome ->
            setRevealBusy(row, false)
            when (outcome) {
                BonusTeaser.Result.EARNED -> {
                    ExposedTitles.reveal(this, digest.e164, name)
                    styleName(row, name, revealed = true)
                    refreshNameTally()
                }
                BonusTeaser.Result.NOT_EARNED ->
                    Toast.makeText(this, R.string.ds_reward_not_earned, Toast.LENGTH_SHORT).show()
                else -> Unit
            }
        }
    }

    private fun dropPin() {
        areaPulse?.also { old ->
            old.stop()
            binding.chartView.overlays.remove(old)
        }
        areaPulse = StreetCharts.place(binding.chartView, digest, coarse = false)
        offsetPin()
    }

    private fun offsetPin() {
        val density = resources.displayMetrics.density
        val topSpace = statusInset + (46 * density).roundToInt()
        val bottomSpace = (34 * density).roundToInt()
        with(binding.chartView) {
            setMapCenterOffset(0, (topSpace - bottomSpace) / 2)
            invalidate()
        }
    }

    private fun relookup() {
        binding.actRetry.isVisible = false
        binding.spinRetry.isVisible = true
        lifecycleScope.launch {
            val again = NumInquiry.run(this@NumProfile2Activity, digest.e164, digest.iso2, "")
            binding.actRetry.isVisible = true
            binding.spinRetry.isVisible = false
            if (again.serverReached) {
                val pinChanged = again.pin != digest.pin || again.precision != digest.precision
                digest = again
                renderAll()
                if (pinChanged) dropPin()
            } else {
                Toast.makeText(this@NumProfile2Activity, R.string.ds_offline_note, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun textNumber() = safeStart(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${digest.e164}")))

    private fun copyNumber() {
        (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.let { cm ->
            cm.setPrimaryClip(ClipData.newPlainText(digest.e164, digest.international))
            Toast.makeText(this, R.string.ds_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openBigMap() = startActivity(ChartWholeActivity.intent(this, digest))

    private fun mapsLink(d: NumDigest): String = d.pin?.let { "https://www.google.com/maps/search/?api=1&query=${it.lat},${it.lng}" }
        ?: "https://www.google.com/maps/search/?api=1&query=${Uri.encode(d.placeLabel)}"

    private fun shareReport() {
        val d = digest
        val link = mapsLink(d)
        val lines = mutableListOf<String>()
        d.name?.let { lines += getString(R.string.ds_share_name, it) }
        lines += getString(R.string.ds_share_number, d.international)
        d.placeLabel.takeIf { it.isNotEmpty() }?.let { lines += getString(R.string.ds_share_place, it) }
        d.carrier?.let { lines += getString(R.string.ds_share_carrier, it) }
        lines += link
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
        safeStart(Intent.createChooser(send, getString(R.string.ds_share_title)))
    }

    private fun safeStart(target: Intent) {
        try {
            startActivity(target)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ds_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::digest.isInitialized) binding.chartView.onResume()
    }

    override fun onPause() {
        if (::digest.isInitialized) binding.chartView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        areaPulse?.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REPORT = "report"
        private const val NAMES_PREVIEW = 10

        fun intent(context: Context, report: NumDigest): Intent =
            Intent(context, NumProfile2Activity::class.java).putExtra(EXTRA_REPORT, report)
    }
}
