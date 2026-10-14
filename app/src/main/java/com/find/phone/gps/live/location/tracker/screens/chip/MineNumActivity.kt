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
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
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
 * Shows the device's own SIM lines as selectable cards; the chosen line feeds the SIM tools
 * underneath. Needs READ_PHONE_STATE only.
 */
class MineNumActivity : StageActivity<ScreenMyNumberBinding>() {

    override val layoutId: Int = R.layout.screen_my_number

    private class SimTile(
        val line: MineChipNums.SimLine,
        val subId: Int?,
        val views: CellMyNumberCardCompactBinding,
    )

    private val lockerStore by lazy { LockerBook(this) }
    private val barLevels = HashMap<Int, Int>()
    private val signalHooks = HashMap<Int, Pair<TelephonyManager, PhoneStateListener>>()
    private var activeSlot = -1
    private var tiles: List<SimTile> = emptyList()

    private val phoneStateAsk = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { outcome ->
        for ((perm, ok) in outcome) recordPermissionResult(perm, ok)
        if (MineChipNums.isGranted(this)) ChipNumDiary.log(this)
        refreshSims()
    }

    override fun initView() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.ownNumberRoot) { root, insets ->
            insets.getInsets(WindowInsetsCompat.Type.systemBars()).let { sys ->
                root.updatePadding(top = sys.top, bottom = sys.bottom)
            }
            insets
        }
        with(binding) {
            actBack.setOnClickListener { goBack() }
            actShow.setOnClickListener { handleShowTap() }
            lineShareQr.setOnClickListener { launchShare() }
            lineDashboard.setOnClickListener { startActivity(DoubleChipOverviewActivity.intent(this@MineNumActivity)) }
            lineCarrierCodes.setOnClickListener {
                startActivity(FrameDialsActivity.intent(this@MineNumActivity, activeSlot))
            }
        }
        refreshSims()
    }

    override fun onResume() {
        super.onResume()
        refreshSims()
    }

    override fun onPause() {
        detachSignalListeners()
        super.onPause()
    }

    private fun refreshSims() {
        detachSignalListeners()
        val simLines = when {
            MineChipNums.hasReadySim(this) -> MineChipNums.read(this)
            else -> emptyList()
        }
        val anyLine = simLines.isNotEmpty()
        binding.chipcardCards.isVisible = anyLine
        binding.chipcardToolsSection.isVisible = anyLine
        binding.blankState.isVisible = !anyLine
        binding.actShow.setText(if (phoneAccessBlocked()) R.string.my_number_settings else R.string.my_number_show)

        val strip = binding.chipcardCards
        strip.removeAllViews()
        if (!anyLine) {
            tiles = emptyList()
            return
        }
        val inflater = LayoutInflater.from(this)
        tiles = simLines.mapIndexed { index, simLine ->
            val cell = CellMyNumberCardCompactBinding.inflate(inflater, strip, false)
            cell.root.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { lp ->
                if (index > 0) lp.marginStart = px(12)
            }
            strip.addView(cell.root)
            SimTile(simLine, ChipRadio.subIdForSlot(this, simLine.slot), cell)
        }
        val slotStillPresent = tiles.any { it.line.slot == activeSlot }
        if (!slotStillPresent) activeSlot = tiles.first().line.slot
        for (tile in tiles) {
            fillTile(tile)
            tile.views.root.setOnClickListener { pickSlot(tile.line.slot) }
        }
        paintAll()
        attachSignalListeners()
    }

    private fun fillTile(tile: SimTile) {
        val cell = tile.views
        val simLine = tile.line
        cell.txtBadge.text = getString(R.string.my_number_sim_badge, simLine.slot)
        val netLabel = tile.subId?.let { ChipRadio.networkLabel(this, it) }
        cell.txtCarrier.text = netLabel?.let { "${simLine.carrier} · $it" } ?: simLine.carrier
        cell.txtRegion.text = simLine.region.orEmpty()
        cell.txtRegion.isVisible = !simLine.region.isNullOrBlank()

        when (val stored = simLine.number) {
            null -> {
                cell.txtNumber.setText(R.string.my_number_not_stored)
                cell.actCopyVw.isVisible = false
            }
            else -> {
                cell.txtNumber.text = stored
                cell.actCopyVw.isVisible = true
                cell.actCopyVw.setOnClickListener { copyToClipboard(stored) }
            }
        }
        cell.lvBadgeDefault.isVisible = tile.subId != null && tile.subId == ChipRadio.defaultVoiceSubId()

        val e164 = simLine.e164 ?: return
        if (MineChipTitles.cached(this, e164) != null) return
        lifecycleScope.launch {
            MineChipTitles.resolve(
                this@MineNumActivity, e164,
                getString(R.string.my_number_sim_label, simLine.slot, simLine.carrier),
            )
        }
    }

    private fun pickSlot(slot: Int) {
        if (slot != activeSlot) {
            activeSlot = slot
            paintAll()
        }
    }

    private fun paintAll() = tiles.forEach { paintTile(it, it.line.slot == activeSlot) }

    private fun paintTile(tile: SimTile, chosen: Boolean) {
        val cell = tile.views
        cell.root.setBackgroundResource(if (chosen) R.drawable.lv_sim_card_selected else R.drawable.lv_sim_card_unselected)

        // Fixed palette on purpose: the adaptive ink colours would disappear on the white card at night.
        val mainInk = tint(if (chosen) R.color.white else R.color.ds_ink_fixed)
        val dimInk = if (chosen) 0xCCFFFFFF.toInt() else tint(R.color.ds_muted_fixed)
        cell.txtCarrier.setTextColor(mainInk)
        cell.txtNumber.setTextColor(mainInk)
        cell.txtRegion.setTextColor(dimInk)

        cell.txtBadge.setBackgroundResource(if (chosen) R.drawable.lv_sim_badge_white else R.drawable.lv_tile_tint_r8)
        cell.txtBadge.setTextColor(tint(if (chosen) R.color.white else R.color.ds_splash_blue))

        cell.actCopyVw.setBackgroundResource(if (chosen) R.drawable.lv_copy_translucent_r12 else R.drawable.lv_copy_gray_fixed_r12)
        val copyTint = tint(if (chosen) R.color.white else R.color.ds_ink_fixed)
        cell.actCopyVw.setTextColor(copyTint)
        TextViewCompat.setCompoundDrawableTintList(cell.actCopyVw, ColorStateList.valueOf(copyTint))

        cell.lvBadgeDefault.setBackgroundResource(if (chosen) R.drawable.lv_pill_white_solid else R.drawable.lv_pill_success)
        cell.lvBadgeDefault.setTextColor(tint(if (chosen) R.color.ds_splash_blue else R.color.ds_success))

        paintBars(tile, barLevels[tile.line.slot] ?: 0, chosen)
    }

    private fun paintBars(tile: SimTile, strength: Int, chosen: Boolean) {
        val litTint = tint(if (chosen) R.color.white else R.color.ds_splash_blue)
        val dimTint = if (chosen) 0x55FFFFFF else tint(R.color.ds_border)
        val v = tile.views
        arrayOf(v.lvBar1, v.lvBar2, v.lvBar3, v.lvBar4).forEachIndexed { idx, bar ->
            val c = if (idx < strength) litTint else dimTint
            ViewCompat.setBackgroundTintList(bar, ColorStateList.valueOf(c))
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun attachSignalListeners() {
        if (!MineChipNums.isGranted(this)) return
        for (tile in tiles) {
            val subId = tile.subId ?: continue
            val telephony = ChipRadio.tmFor(this, subId)
            val watcher = object : PhoneStateListener() {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                    val bars = signalStrength.level.coerceIn(0, 4)
                    barLevels[tile.line.slot] = bars
                    paintBars(tile, bars, tile.line.slot == activeSlot)
                }
            }
            signalHooks[subId] = telephony to watcher
            runCatching { telephony.listen(watcher, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
        }
    }

    @Suppress("DEPRECATION")
    private fun detachSignalListeners() {
        for ((telephony, watcher) in signalHooks.values) {
            runCatching { telephony.listen(watcher, PhoneStateListener.LISTEN_NONE) }
        }
        signalHooks.clear()
    }

    private fun currentTile(): SimTile? =
        tiles.firstOrNull { it.line.slot == activeSlot } ?: tiles.firstOrNull()

    private fun launchShare() {
        val tile = currentTile()
        val e164 = tile?.line?.e164
        val digits = tile?.line?.number
        if (tile == null || e164 == null || digits == null) {
            Toast.makeText(this, R.string.my_number_not_stored, Toast.LENGTH_SHORT).show()
            return
        }
        val ownerName = MineChipTitles.cached(this, e164)
            ?.takeUnless { it.source == MineChipTitles.Source.SIM }
            ?.name
        startActivity(SpreadNumActivity.intent(this, e164, digits, ownerName, tile.line.slot, tile.line.carrier))
    }

    private fun handleShowTap() {
        recordKeyEvent("MyNumberPage_Show_Click")
        val perm = MineChipNums.PERMISSIONS.first()
        if (MineChipNums.isGranted(this)) {
            refreshSims()
        } else if (!lockerStore.hasRequestedPermission(perm)) {
            lockerStore.flagPermissionRequested(perm)
            phoneStateAsk.launch(MineChipNums.PERMISSIONS)
        } else if (ActivityCompat.shouldShowRequestPermissionRationale(this, perm)) {
            phoneStateAsk.launch(MineChipNums.PERMISSIONS)
        } else {
            openAppDetails()
        }
    }

    private fun phoneAccessBlocked(): Boolean {
        if (MineChipNums.isGranted(this)) return false
        val perm = MineChipNums.PERMISSIONS.first()
        if (!lockerStore.hasRequestedPermission(perm)) return false
        return !ActivityCompat.shouldShowRequestPermissionRationale(this, perm)
    }

    private fun openAppDetails() {
        Toast.makeText(this, R.string.my_number_denied, Toast.LENGTH_LONG).show()
        CoreOpenSpotBook.skipNextAppOpenAd = true
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun copyToClipboard(value: String) {
        val board = getSystemService(ClipboardManager::class.java) ?: return
        board.setPrimaryClip(ClipData.newPlainText(getString(R.string.my_number_title), value))
        Toast.makeText(this, R.string.my_number_copied, Toast.LENGTH_SHORT).show()
        recordKeyEvent("MyNumberPage_Copy")
    }

    private fun tint(id: Int) = ContextCompat.getColor(this, id)
    private fun px(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent = Intent(context, MineNumActivity::class.java)
    }
}
