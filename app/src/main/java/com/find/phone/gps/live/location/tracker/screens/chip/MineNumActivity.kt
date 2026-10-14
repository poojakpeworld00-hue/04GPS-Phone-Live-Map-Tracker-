package com.find.phone.gps.live.location.tracker.screens.chip

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.lifecycleScope
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.store.chip.MineChipTitles
import com.find.phone.gps.live.location.tracker.store.chip.MineChipNums
import com.find.phone.gps.live.location.tracker.databinding.CellMyNumberCardCompactBinding
import com.find.phone.gps.live.location.tracker.databinding.ScreenMyNumberBinding
import com.find.phone.gps.live.location.tracker.helpers.ChipNumDiary
import com.find.phone.gps.live.location.tracker.helpers.isNightMode
import kotlinx.coroutines.launch

/**
 * SIM Tools "My number" page (design 2a). The phone's own SIMs as side-by-side, selectable cards
 * (carrier · network, region, number, live signal, Copy, a Default badge); the selected SIM drives
 * the SIM tools below (Share + QR, Dual-SIM dashboard, Carrier codes). READ_PHONE_STATE only.
 */
class MineNumActivity : StageActivity<ScreenMyNumberBinding>() {

    override val layoutId: Int = R.layout.screen_my_number

    private val prefs by lazy { LockerBook(this) }

    private class Card(val line: MineChipNums.SimLine, val subId: Int?, val views: CellMyNumberCardCompactBinding)

    private var cards: List<Card> = emptyList()
    private var selectedSlot = -1
    private val signals = HashMap<Int, Pair<TelephonyManager, PhoneStateListener>>()
    private val levels = HashMap<Int, Int>()

    private val phonePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (permission, granted) -> recordPermissionResult(permission, granted) }
        if (MineChipNums.isGranted(this)) ChipNumDiary.log(this)
        render()
    }

    override fun initView() {
        // Dark icons on the light canvas, light icons in dark mode.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.ownNumberRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.actBack.setOnClickListener { goBack() }
        binding.actShow.setOnClickListener { onShow() }
        binding.lineShareQr.setOnClickListener { enterShare() }
        binding.lineDashboard.setOnClickListener { startActivity(DoubleChipOverviewActivity.intent(this)) }
        binding.lineCarrierCodes.setOnClickListener {
            startActivity(FrameDialsActivity.intent(this, selectedSlot))
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        haltSignals()
        super.onPause()
    }

    private fun render() {
        haltSignals()
        val lines = if (MineChipNums.hasReadySim(this)) MineChipNums.read(this) else emptyList()
        val showing = lines.isNotEmpty()
        binding.chipcardCards.isVisible = showing
        binding.chipcardToolsSection.isVisible = showing
        binding.blankState.isVisible = !showing
        binding.actShow.setText(if (isPermanentlyDenied()) R.string.my_number_settings else R.string.my_number_show)

        binding.chipcardCards.removeAllViews()
        if (!showing) {
            cards = emptyList()
            return
        }
        val inflater = LayoutInflater.from(this)
        cards = lines.mapIndexed { i, line ->
            val v = CellMyNumberCardCompactBinding.inflate(inflater, binding.chipcardCards, false)
            v.root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(12)
            }
            binding.chipcardCards.addView(v.root)
            Card(line, ChipRadio.subIdForSlot(this, line.slot), v)
        }
        if (cards.none { it.line.slot == selectedSlot }) selectedSlot = cards.first().line.slot
        cards.forEach { card ->
            wireStatic(card)
            card.views.root.setOnClickListener { select(card.line.slot) }
        }
        enactSelection()
        enlistSignals()
    }

    private fun wireStatic(card: Card) {
        val v = card.views
        val line = card.line
        v.txtBadge.text = getString(R.string.my_number_sim_badge, line.slot)
        val network = card.subId?.let { ChipRadio.networkLabel(this, it) }
        v.txtCarrier.text = if (network != null) "${line.carrier} · $network" else line.carrier
        v.txtRegion.text = line.region.orEmpty()
        v.txtRegion.isVisible = !line.region.isNullOrBlank()

        val number = line.number
        if (number != null) {
            v.txtNumber.text = number
            v.actCopyVw.isVisible = true
            v.actCopyVw.setOnClickListener { copy(number) }
        } else {
            v.txtNumber.setText(R.string.my_number_not_stored)
            v.actCopyVw.isVisible = false
        }
        v.lvBadgeDefault.isVisible = card.subId != null && card.subId == ChipRadio.defaultVoiceSubId()

        // Warm the name cache for Share (vCard), but the card shows the region, per the design.
        line.e164?.let { e164 ->
            if (MineChipTitles.cached(this, e164) == null) lifecycleScope.launch {
                MineChipTitles.resolve(this@MineNumActivity, e164, getString(R.string.my_number_sim_label, line.slot, line.carrier))
            }
        }
    }

    // ── selection / styling ────────────────────────────────────────────────────

    private fun select(slot: Int) {
        if (slot == selectedSlot) return
        selectedSlot = slot
        enactSelection()
    }

    private fun enactSelection() {
        cards.forEach { styleCard(it, it.line.slot == selectedSlot) }
    }

    private fun styleCard(card: Card, on: Boolean) {
        val v = card.views
        v.root.setBackgroundResource(if (on) R.drawable.lv_sim_card_selected else R.drawable.lv_sim_card_unselected)

        // The cards are fixed illustrations (gradient or white), so the unselected card uses fixed
        // ink/grey — the adaptive ds_ink/ds_muted flip light in dark mode and vanish on the white card.
        val ink = color(if (on) R.color.white else R.color.ds_ink_fixed)
        val sub = if (on) 0xCCFFFFFF.toInt() else color(R.color.ds_muted_fixed)
        v.txtCarrier.setTextColor(ink)
        v.txtNumber.setTextColor(ink)
        v.txtRegion.setTextColor(sub)

        v.txtBadge.setBackgroundResource(if (on) R.drawable.lv_sim_badge_white else R.drawable.lv_tile_tint_r8)
        v.txtBadge.setTextColor(if (on) color(R.color.white) else color(R.color.ds_splash_blue))

        v.actCopyVw.setBackgroundResource(if (on) R.drawable.lv_copy_translucent_r12 else R.drawable.lv_copy_gray_fixed_r12)
        val copyInk = if (on) color(R.color.white) else color(R.color.ds_ink_fixed)
        v.actCopyVw.setTextColor(copyInk)
        TextViewCompat.setCompoundDrawableTintList(v.actCopyVw, ColorStateList.valueOf(copyInk))

        v.lvBadgeDefault.setBackgroundResource(if (on) R.drawable.lv_pill_white_solid else R.drawable.lv_pill_success)
        v.lvBadgeDefault.setTextColor(if (on) color(R.color.ds_splash_blue) else color(R.color.ds_success))

        setBars(card, levels[card.line.slot] ?: 0, on)
    }

    private fun setBars(card: Card, level: Int, on: Boolean) {
        val onColor = if (on) color(R.color.white) else color(R.color.ds_splash_blue)
        val offColor = if (on) 0x55FFFFFF else color(R.color.ds_border)
        listOf(card.views.lvBar1, card.views.lvBar2, card.views.lvBar3, card.views.lvBar4).forEachIndexed { i, bar ->
            ViewCompat.setBackgroundTintList(bar, ColorStateList.valueOf(if (i < level) onColor else offColor))
        }
    }

    // ── live signal ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun enlistSignals() {
        if (!MineChipNums.isGranted(this)) return
        cards.forEach { card ->
            val subId = card.subId ?: return@forEach
            val tm = ChipRadio.tmFor(this, subId)
            val listener = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    val level = signalStrength.level.coerceIn(0, 4)
                    levels[card.line.slot] = level
                    setBars(card, level, card.line.slot == selectedSlot)
                }
            }
            signals[subId] = tm to listener
            runCatching { tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
        }
    }

    @Suppress("DEPRECATION")
    private fun haltSignals() {
        signals.values.forEach { (tm, l) -> runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) } }
        signals.clear()
    }

    // ── tools (operate on the selected SIM) ────────────────────────────────────

    private fun selected(): Card? = cards.firstOrNull { it.line.slot == selectedSlot } ?: cards.firstOrNull()

    private fun enterShare() {
        val card = selected()
        val e164 = card?.line?.e164
        val number = card?.line?.number
        if (card == null || e164 == null || number == null) {
            Toast.makeText(this, R.string.my_number_not_stored, Toast.LENGTH_SHORT).show()
            return
        }
        val name = MineChipTitles.cached(this, e164)?.takeIf { it.source != MineChipTitles.Source.SIM }?.name
        startActivity(SpreadNumActivity.intent(this, e164, number, name, card.line.slot, card.line.carrier))
    }

    // ── permission / helpers ───────────────────────────────────────────────────

    private fun onShow() {
        recordKeyEvent("MyNumberPage_Show_Click")
        val permission = MineChipNums.PERMISSIONS.first()
        when {
            MineChipNums.isGranted(this) -> render()
            !prefs.hasRequestedPermission(permission) -> {
                prefs.flagPermissionRequested(permission)
                phonePermission.launch(MineChipNums.PERMISSIONS)
            }
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission) ->
                phonePermission.launch(MineChipNums.PERMISSIONS)
            else -> enterPhoneSettings()
        }
    }

    private fun isPermanentlyDenied(): Boolean {
        val permission = MineChipNums.PERMISSIONS.first()
        return !MineChipNums.isGranted(this) &&
            prefs.hasRequestedPermission(permission) &&
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
    }

    private fun enterPhoneSettings() {
        Toast.makeText(this, R.string.my_number_denied, Toast.LENGTH_LONG).show()
        CoreOpenSpotBook.skipNextAppOpenAd = true
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun copy(number: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.my_number_title), number))
        Toast.makeText(this, R.string.my_number_copied, Toast.LENGTH_SHORT).show()
        recordKeyEvent("MyNumberPage_Copy")
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent = Intent(context, MineNumActivity::class.java)
    }
}
