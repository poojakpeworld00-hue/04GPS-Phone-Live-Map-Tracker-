package com.find.my.phone.gps.location.tracker.ui.lookup

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
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.presentation.RewardedPromo
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.lookup.NumberLookup
import com.find.my.phone.gps.location.tracker.data.lookup.NumberReport
import com.find.my.phone.gps.location.tracker.data.lookup.RevealedNames
import com.find.my.phone.gps.location.tracker.data.premium.PremiumBilling
import com.find.my.phone.gps.location.tracker.databinding.TileDetailActionBinding
import com.find.my.phone.gps.location.tracker.databinding.TileDetailCellBinding
import com.find.my.phone.gps.location.tracker.databinding.TileDetailNameBinding
import com.find.my.phone.gps.location.tracker.databinding.ViewNumberDetailBinding
import com.find.my.phone.gps.location.tracker.ui.contacts.ContactAdapter
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Number Details (design 11c), once the result is unlocked: the registered area on a small
 * OpenStreetMap header, the card with the number, place, chips and Call · Message · Map · Copy,
 * the Location details grid, and Also known as — every name the lookup found, the best match
 * revealed by the unlock and each other one behind its own rewarded ad (Premium: all shown).
 * When the lookup server couldn't be reached a note offers Retry, which re-runs it in place.
 */
class NumberDetailActivity : CanvasActivity<ViewNumberDetailBinding>() {

    override val layoutId: Int = R.layout.view_number_detail

    private lateinit var report: NumberReport
    private var overlay: AreaPulseOverlay? = null
    private var revealing = false
    private var topInset = 0
    private var allNamesShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // osmdroid must be configured before the MapView is inflated (in super.onCreate).
        OsmMaps.init(this)
        super.onCreate(savedInstanceState)
    }

    override fun initView() {
        val given = IntentCompat.getSerializableExtra(intent, EXTRA_REPORT, NumberReport::class.java)
        if (given == null) {
            finish()
            return
        }
        report = given

        // The map runs under the status bar; the top bar sits below it.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val topPad = binding.mapTopBar.paddingTop
        val mapHeight = binding.mapBox.layoutParams.height
        ViewCompat.setOnApplyWindowInsetsListener(binding.detailRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.mapTopBar.updatePadding(top = topPad + bars.top)
            binding.mapBox.updateLayoutParams { height = mapHeight + bars.top }
            if (topInset != bars.top) {
                topInset = bars.top
                if (::report.isInitialized) centerPin()
            }
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.btnBack.setOnClickListener { goBack() }
        binding.btnShare.setOnClickListener { share() }
        binding.btnRetry.setOnClickListener { retry() }
        binding.mapTap.setOnClickListener { openFullMap() }
        binding.btnFullMap.setOnClickListener { openFullMap() }
        DesignMotion.pressScale(binding.btnFullMap)
        binding.identityCard.clipToOutline = false

        action(binding.actCall, R.drawable.ds_ic_call, R.string.ds_act_call, lead = true) { placeCall(report.e164) }
        action(binding.actSms, R.drawable.ds_ic_message, R.string.ds_act_sms) { sendSms() }
        action(binding.actMap, R.drawable.ds_ic_navigation, R.string.ds_act_map) { openFullMap() }
        action(binding.actCopy, R.drawable.ds_ic_copy, R.string.ds_act_copy) { copyNumber() }

        OsmMaps.style(binding.mapView, interactive = false)
        bind()
        placeOnMap()
    }

    // ── Card, grid, names ─────────────────────────────────────────────────

    private fun bind() {
        val r = report
        binding.tvIso.text = r.iso2
        binding.tvNumber.text = r.international
        binding.tvPlace.text = listOfNotNull(r.city, r.state).distinct().joinToString(", ").ifEmpty { r.countryName }
        binding.tvMapPlace.text = binding.tvPlace.text

        when {
            r.spam -> chip(R.string.ds_verdict_spam, R.drawable.ds_pill_danger, R.color.ds_danger)
            r.valid -> chip(R.string.ds_result_valid_short, R.drawable.ds_pill_success_soft, R.color.ds_success)
            else -> chip(R.string.ds_verdict_unverified, R.drawable.ds_pill_warn, R.color.ds_warn)
        }
        val line = r.lineType.label(this)
        binding.chipLine.text = line
        binding.chipLine.isVisible = line != null
        binding.offlineCard.isVisible = !r.serverReached

        cell(binding.cellCountry, R.string.ds_label_country, r.countryName)
        cell(binding.cellPlace, R.string.ds_detail_city_region, listOfNotNull(r.city, r.state).distinct().joinToString(", "))
        cell(binding.cellCarrier, R.string.ds_fact_carrier, r.carrier)
        cell(binding.cellLine, R.string.ds_fact_line, line)

        bindNames()
    }

    private fun chip(@StringRes text: Int, @DrawableRes pill: Int, color: Int) {
        binding.chipVerdict.setText(text)
        binding.chipVerdict.setBackgroundResource(pill)
        binding.chipVerdict.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun cell(cell: TileDetailCellBinding, @StringRes label: Int, value: String?) {
        cell.tvLabel.setText(label)
        val known = !value.isNullOrBlank()
        cell.tvValue.text = if (known) value else getString(R.string.ds_not_available)
        cell.tvValue.setTextColor(ContextCompat.getColor(this, if (known) R.color.ds_ink else R.color.ds_muted))
    }

    private fun action(tile: TileDetailActionBinding, @DrawableRes icon: Int, @StringRes label: Int, lead: Boolean = false, onClick: () -> Unit) {
        tile.ivIcon.setImageResource(icon)
        tile.tvLabel.setText(label)
        if (lead) {
            tile.iconBox.setBackgroundResource(R.drawable.ds_tile_primary_r16)
            tile.ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.white))
        }
        DesignMotion.pressScale(tile.actionRoot)
        tile.actionRoot.setOnClickListener { onClick() }
    }

    /** Every name found: the best match came with the unlock; the others one ad each. */
    private val names: List<String>
        get() = listOfNotNull(report.name) + report.otherNames

    private fun isRevealed(index: Int, name: String): Boolean =
        index == 0 && report.name != null || PremiumBilling.isPremium(this) || RevealedNames.isRevealed(this, report.e164, name)

    private fun bindNames() {
        val all = names
        binding.namesBlock.isVisible = all.isNotEmpty()
        binding.namesList.removeAllViews()
        // The lookup can return up to 100 names: the first few, then "Show all" on demand.
        val shown = if (allNamesShown) all else all.take(NAMES_PREVIEW)
        binding.btnShowAllNames.isVisible = shown.size < all.size
        binding.btnShowAllNames.text = getString(R.string.ds_names_show_all, all.size)
        binding.btnShowAllNames.setOnClickListener {
            allNamesShown = true
            bindNames()
        }
        shown.forEachIndexed { i, name ->
            val item = TileDetailNameBinding.inflate(LayoutInflater.from(this), binding.namesList, true)
            item.tvName.text = name
            item.nameDivider.isVisible = i < shown.lastIndex
            showName(item, name, isRevealed(i, name))
            item.btnReveal.setOnClickListener { revealName(item, name) }
        }
        updateRevealedCount()
        if (all.indices.any { !isRevealed(it, all[it]) }) RewardedPromo.preload(this)
    }

    private fun showName(item: TileDetailNameBinding, name: String, revealed: Boolean) {
        item.tvName.setBlurred(!revealed)
        item.tvRevealed.isVisible = revealed
        item.btnReveal.isVisible = !revealed
        if (revealed) {
            item.tvAvatar.text = initials(name)
            item.tvAvatar.setBackgroundResource(ContactAdapter.AVATARS[abs(name.hashCode()) % ContactAdapter.AVATARS.size])
        } else {
            item.tvAvatar.text = ""
            item.tvAvatar.setBackgroundResource(R.drawable.ds_circle_track)
        }
    }

    private fun initials(name: String): String =
        name.split(' ').filter { it.isNotBlank() }.take(2)
            .mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .joinToString("")

    private fun updateRevealedCount() {
        val all = names
        val revealed = all.indices.count { isRevealed(it, all[it]) }
        binding.tvRevealedCount.text = getString(R.string.ds_names_count, revealed, all.size)
        binding.tvNamesNote.isVisible = revealed < all.size
    }

    /** One rewarded ad reveals one name (remembered); closing the ad early keeps it hidden. */
    private fun revealName(item: TileDetailNameBinding, name: String) {
        if (revealing) return
        revealing = true
        item.tvReveal.isInvisible = true
        item.pbReveal.isVisible = true
        RewardedPromo().show(this) { result ->
            revealing = false
            item.tvReveal.isInvisible = false
            item.pbReveal.isVisible = false
            if (result != RewardedPromo.Result.EARNED) {
                if (result == RewardedPromo.Result.NOT_EARNED) {
                    Toast.makeText(this, R.string.ds_reward_not_earned, Toast.LENGTH_SHORT).show()
                }
                return@show
            }
            RevealedNames.reveal(this, report.e164, name)
            showName(item, name, revealed = true)
            updateRevealedCount()
        }
    }

    // ── Map ───────────────────────────────────────────────────────────────

    private fun placeOnMap() {
        overlay?.let { it.stop(); binding.mapView.overlays.remove(it) }
        overlay = OsmMaps.place(binding.mapView, report, coarse = false)
        centerPin()
    }

    /**
     * The pin goes in the middle of the strip left visible between the top bar (under the status
     * bar) and the card overlapping the header's bottom — half the difference below centre.
     */
    private fun centerPin() {
        val d = resources.displayMetrics.density
        val above = topInset + (46 * d).roundToInt()
        val below = (34 * d).roundToInt()
        binding.mapView.setMapCenterOffset(0, (above - below) / 2)
        binding.mapView.invalidate()
    }

    // ── Retry ─────────────────────────────────────────────────────────────

    private fun retry() {
        binding.btnRetry.isVisible = false
        binding.pbRetry.isVisible = true
        lifecycleScope.launch {
            // The E.164 number re-parses on its own; the region only matters when it doesn't.
            val fresh = NumberLookup.run(this@NumberDetailActivity, report.e164, report.iso2, "")
            binding.btnRetry.isVisible = true
            binding.pbRetry.isVisible = false
            if (!fresh.serverReached) {
                Toast.makeText(this@NumberDetailActivity, R.string.ds_offline_note, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val moved = fresh.pin != report.pin || fresh.precision != report.precision
            report = fresh
            bind()
            if (moved) placeOnMap()
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────

    private fun sendSms() = launch(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${report.e164}")))

    private fun copyNumber() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(report.e164, report.international))
        Toast.makeText(this, R.string.ds_copied, Toast.LENGTH_SHORT).show()
    }

    /** The area on a full-screen, interactive map (header Full view, a tap on the header, Map). */
    private fun openFullMap() = startActivity(MapFullActivity.intent(this, report))

    private fun share() {
        val r = report
        val pin = r.pin
        val link = if (pin != null) "https://www.google.com/maps/search/?api=1&query=${pin.lat},${pin.lng}"
        else "https://www.google.com/maps/search/?api=1&query=${Uri.encode(r.placeLabel)}"
        val text = listOfNotNull(
            r.name?.let { getString(R.string.ds_share_name, it) },
            getString(R.string.ds_share_number, r.international),
            r.placeLabel.takeIf { it.isNotEmpty() }?.let { getString(R.string.ds_share_place, it) },
            r.carrier?.let { getString(R.string.ds_share_carrier, it) },
            link,
        ).joinToString("\n")
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        launch(Intent.createChooser(send, getString(R.string.ds_share_title)))
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.ds_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        if (::report.isInitialized) binding.mapView.onResume()
    }

    override fun onPause() {
        if (::report.isInitialized) binding.mapView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        overlay?.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_REPORT = "report"
        private const val NAMES_PREVIEW = 10

        fun intent(context: Context, report: NumberReport): Intent =
            Intent(context, NumberDetailActivity::class.java).putExtra(EXTRA_REPORT, report)
    }
}
