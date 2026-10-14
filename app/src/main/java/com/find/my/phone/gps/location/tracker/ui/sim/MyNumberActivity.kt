package com.find.my.phone.gps.location.tracker.ui.sim

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
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.domain.logPermissionResult
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.data.sim.MySimNames
import com.find.my.phone.gps.location.tracker.data.sim.MySimNumbers
import com.find.my.phone.gps.location.tracker.databinding.TileMyNumberCardCompactBinding
import com.find.my.phone.gps.location.tracker.databinding.ViewMyNumberBinding
import com.find.my.phone.gps.location.tracker.util.SimNumberLog
import com.find.my.phone.gps.location.tracker.util.isNightMode
import kotlinx.coroutines.launch

/**
 * SIM Tools "My number" page (design 2a). The phone's own SIMs as side-by-side, selectable cards
 * (carrier · network, region, number, live signal, Copy, a Default badge); the selected SIM drives
 * the SIM tools below (Share + QR, Dual-SIM dashboard, Carrier codes). READ_PHONE_STATE only.
 */
class MyNumberActivity : CanvasActivity<ViewMyNumberBinding>() {

    override val layoutId: Int = R.layout.view_my_number

    private val prefs by lazy { VaultRegistry(this) }

    private class Card(val line: MySimNumbers.SimLine, val subId: Int?, val views: TileMyNumberCardCompactBinding)

    private var cards: List<Card> = emptyList()
    private var selectedSlot = -1
    private val signals = HashMap<Int, Pair<TelephonyManager, PhoneStateListener>>()
    private val levels = HashMap<Int, Int>()

    private val phonePermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result.forEach { (permission, granted) -> logPermissionResult(permission, granted) }
        if (MySimNumbers.isGranted(this)) SimNumberLog.log(this)
        render()
    }

    override fun initView() {
        // Dark icons on the light canvas, light icons in dark mode.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.myNumberRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }
        binding.btnShow.setOnClickListener { onShow() }
        binding.rowShareQr.setOnClickListener { openShare() }
        binding.rowDashboard.setOnClickListener { startActivity(DualSimDashboardActivity.intent(this)) }
        binding.rowCarrierCodes.setOnClickListener {
            startActivity(CarrierCodesActivity.intent(this, selectedSlot))
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        stopSignals()
        super.onPause()
    }

    private fun render() {
        stopSignals()
        val lines = if (MySimNumbers.hasReadySim(this)) MySimNumbers.read(this) else emptyList()
        val showing = lines.isNotEmpty()
        binding.simCards.isVisible = showing
        binding.simToolsSection.isVisible = showing
        binding.emptyState.isVisible = !showing
        binding.btnShow.setText(if (isPermanentlyDenied()) R.string.my_number_settings else R.string.my_number_show)

        binding.simCards.removeAllViews()
        if (!showing) {
            cards = emptyList()
            return
        }
        val inflater = LayoutInflater.from(this)
        cards = lines.mapIndexed { i, line ->
            val v = TileMyNumberCardCompactBinding.inflate(inflater, binding.simCards, false)
            v.root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(12)
            }
            binding.simCards.addView(v.root)
            Card(line, SimRadio.subIdForSlot(this, line.slot), v)
        }
        if (cards.none { it.line.slot == selectedSlot }) selectedSlot = cards.first().line.slot
        cards.forEach { card ->
            bindStatic(card)
            card.views.root.setOnClickListener { select(card.line.slot) }
        }
        applySelection()
        registerSignals()
    }

    private fun bindStatic(card: Card) {
        val v = card.views
        val line = card.line
        v.tvBadge.text = getString(R.string.my_number_sim_badge, line.slot)
        val network = card.subId?.let { SimRadio.networkLabel(this, it) }
        v.tvCarrier.text = if (network != null) "${line.carrier} · $network" else line.carrier
        v.tvRegion.text = line.region.orEmpty()
        v.tvRegion.isVisible = !line.region.isNullOrBlank()

        val number = line.number
        if (number != null) {
            v.tvNumber.text = number
            v.btnCopy.isVisible = true
            v.btnCopy.setOnClickListener { copy(number) }
        } else {
            v.tvNumber.setText(R.string.my_number_not_stored)
            v.btnCopy.isVisible = false
        }
        v.badgeDefault.isVisible = card.subId != null && card.subId == SimRadio.defaultVoiceSubId()

        // Warm the name cache for Share (vCard), but the card shows the region, per the design.
        line.e164?.let { e164 ->
            if (MySimNames.cached(this, e164) == null) lifecycleScope.launch {
                MySimNames.resolve(this@MyNumberActivity, e164, getString(R.string.my_number_sim_label, line.slot, line.carrier))
            }
        }
    }

    // ── selection / styling ────────────────────────────────────────────────────

    private fun select(slot: Int) {
        if (slot == selectedSlot) return
        selectedSlot = slot
        applySelection()
    }

    private fun applySelection() {
        cards.forEach { styleCard(it, it.line.slot == selectedSlot) }
    }

    private fun styleCard(card: Card, on: Boolean) {
        val v = card.views
        v.root.setBackgroundResource(if (on) R.drawable.ds_sim_card_selected else R.drawable.ds_sim_card_unselected)

        // The cards are fixed illustrations (gradient or white), so the unselected card uses fixed
        // ink/grey — the adaptive ds_ink/ds_muted flip light in dark mode and vanish on the white card.
        val ink = color(if (on) R.color.white else R.color.ds_ink_fixed)
        val sub = if (on) 0xCCFFFFFF.toInt() else color(R.color.ds_muted_fixed)
        v.tvCarrier.setTextColor(ink)
        v.tvNumber.setTextColor(ink)
        v.tvRegion.setTextColor(sub)

        v.tvBadge.setBackgroundResource(if (on) R.drawable.ds_sim_badge_white else R.drawable.ds_tile_tint_r8)
        v.tvBadge.setTextColor(if (on) color(R.color.white) else color(R.color.ds_splash_blue))

        v.btnCopy.setBackgroundResource(if (on) R.drawable.ds_copy_translucent_r12 else R.drawable.ds_copy_gray_fixed_r12)
        val copyInk = if (on) color(R.color.white) else color(R.color.ds_ink_fixed)
        v.btnCopy.setTextColor(copyInk)
        TextViewCompat.setCompoundDrawableTintList(v.btnCopy, ColorStateList.valueOf(copyInk))

        v.badgeDefault.setBackgroundResource(if (on) R.drawable.ds_pill_white_solid else R.drawable.ds_pill_success)
        v.badgeDefault.setTextColor(if (on) color(R.color.ds_splash_blue) else color(R.color.ds_success))

        setBars(card, levels[card.line.slot] ?: 0, on)
    }

    private fun setBars(card: Card, level: Int, on: Boolean) {
        val onColor = if (on) color(R.color.white) else color(R.color.ds_splash_blue)
        val offColor = if (on) 0x55FFFFFF else color(R.color.ds_border)
        listOf(card.views.bar1, card.views.bar2, card.views.bar3, card.views.bar4).forEachIndexed { i, bar ->
            ViewCompat.setBackgroundTintList(bar, ColorStateList.valueOf(if (i < level) onColor else offColor))
        }
    }

    // ── live signal ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerSignals() {
        if (!MySimNumbers.isGranted(this)) return
        cards.forEach { card ->
            val subId = card.subId ?: return@forEach
            val tm = SimRadio.tmFor(this, subId)
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
    private fun stopSignals() {
        signals.values.forEach { (tm, l) -> runCatching { tm.listen(l, PhoneStateListener.LISTEN_NONE) } }
        signals.clear()
    }

    // ── tools (operate on the selected SIM) ────────────────────────────────────

    private fun selected(): Card? = cards.firstOrNull { it.line.slot == selectedSlot } ?: cards.firstOrNull()

    private fun openShare() {
        val card = selected()
        val e164 = card?.line?.e164
        val number = card?.line?.number
        if (card == null || e164 == null || number == null) {
            Toast.makeText(this, R.string.my_number_not_stored, Toast.LENGTH_SHORT).show()
            return
        }
        val name = MySimNames.cached(this, e164)?.takeIf { it.source != MySimNames.Source.SIM }?.name
        startActivity(ShareNumberActivity.intent(this, e164, number, name, card.line.slot, card.line.carrier))
    }

    // ── permission / helpers ───────────────────────────────────────────────────

    private fun onShow() {
        logKeyEvent("MyNumberPage_Show_Click")
        val permission = MySimNumbers.PERMISSIONS.first()
        when {
            MySimNumbers.isGranted(this) -> render()
            !prefs.hasRequestedPermission(permission) -> {
                prefs.markPermissionRequested(permission)
                phonePermission.launch(MySimNumbers.PERMISSIONS)
            }
            androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission) ->
                phonePermission.launch(MySimNumbers.PERMISSIONS)
            else -> openPhoneSettings()
        }
    }

    private fun isPermanentlyDenied(): Boolean {
        val permission = MySimNumbers.PERMISSIONS.first()
        return !MySimNumbers.isGranted(this) &&
            prefs.hasRequestedPermission(permission) &&
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
    }

    private fun openPhoneSettings() {
        Toast.makeText(this, R.string.my_number_denied, Toast.LENGTH_LONG).show()
        AppOpenAdRegistry.skipNextAppOpenAd = true
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun copy(number: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.my_number_title), number))
        Toast.makeText(this, R.string.my_number_copied, Toast.LENGTH_SHORT).show()
        logKeyEvent("MyNumberPage_Copy")
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent = Intent(context, MyNumberActivity::class.java)
    }
}
