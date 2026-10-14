package com.find.phone.gps.live.location.tracker.screens.chip

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.store.chip.MineChipNums
import com.find.phone.gps.live.location.tracker.databinding.ScreenDualSimDashboardBinding
import com.find.phone.gps.live.location.tracker.helpers.isNightMode
import java.util.Locale

class DoubleChipOverviewActivity : StageActivity<ScreenDualSimDashboardBinding>() {
    override val layoutId: Int = R.layout.screen_dual_sim_dashboard

    private var activeTab = 0
    private var lines: List<SubscriptionInfo> = emptyList()
    @Suppress("DEPRECATION")
    private var strengthWatcher: PhoneStateListener? = null
    private var watchedTm: TelephonyManager? = null

    override fun initView() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.lvDashRoot) { root, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = sys.top, bottom = sys.bottom)
            insets
        }
        binding.actBack.setOnClickListener { goBack() }
        lines = loadSubscriptions()
        buildTabs()
        fillDefaultRows()
        fillActiveLine()
    }

    override fun onStart() {
        super.onStart()
        lines.getOrNull(activeTab)?.let { startStrengthWatch(it) }
    }

    override fun onStop() {
        stopStrengthWatch()
        super.onStop()
    }

    @SuppressLint("MissingPermission")
    private fun loadSubscriptions(): List<SubscriptionInfo> {
        if (!MineChipNums.isGranted(this)) return emptyList()
        val subMgr = getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val raw = runCatching { subMgr.activeSubscriptionInfoList }.getOrNull()
        return raw.orEmpty().sortedBy { it.simSlotIndex }
    }

    private fun buildTabs() {
        val strip = binding.chipcardSelector
        strip.removeAllViews()
        if (lines.size < 2) {
            strip.visibility = View.GONE
            return
        }
        lines.forEachIndexed { idx, sub ->
            strip.addView(makeTab(idx, sub))
        }
        restyleTabs()
    }

    private fun makeTab(idx: Int, sub: SubscriptionInfo): TextView = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, px(36), 1f).also { lp ->
            if (idx > 0) lp.marginStart = px(4)
        }
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        textSize = 13f
        text = getString(R.string.dashboard_sim_fmt, sub.simSlotIndex + 1, sub.carrierName)
        setOnClickListener { switchTab(idx) }
    }

    private fun restyleTabs() {
        val strip = binding.chipcardSelector
        repeat(strip.childCount) { idx ->
            val tab = strip.getChildAt(idx) as TextView
            val active = idx == activeTab
            tab.setTextColor(ContextCompat.getColor(this, if (active) R.color.ds_primary else R.color.ds_muted))
            tab.typeface = ResourcesCompat.getFont(this, if (active) R.font.figtree_bold else R.font.figtree_semibold)
            tab.background = if (active) ContextCompat.getDrawable(this, R.drawable.lv_seg_thumb_r10) else null
            tab.elevation = if (active) px(2).toFloat() else 0f
        }
    }

    private fun switchTab(idx: Int) {
        if (idx == activeTab) return
        activeTab = idx
        restyleTabs()
        stopStrengthWatch()
        fillActiveLine()
        startStrengthWatch(lines[idx])
    }

    @SuppressLint("MissingPermission")
    private fun fillActiveLine() {
        val sub = lines.getOrNull(activeTab) ?: run {
            showEmptyLine()
            return
        }
        val tm = managerFor(sub)
        val netType = runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN)
        binding.txtNetwork.text = generationOf(netType)
        val roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false)
        binding.txtRoaming.setText(if (roaming) R.string.dashboard_on else R.string.dashboard_off)
        val iso = runCatching { tm.networkCountryIso }.getOrNull()
            ?: runCatching { tm.simCountryIso }.getOrNull()
        binding.txtCountry.text = countryFromIso(iso)
        binding.txtSimState.text = describeSimState(sub.simSlotIndex)
    }

    private fun showEmptyLine() {
        binding.txtLevel.setText(R.string.dashboard_na)
        binding.txtDbm.text = ""
        paintGauge(0)
        arrayOf(binding.txtNetwork, binding.txtRoaming, binding.txtCountry, binding.txtSimState)
            .forEach { it.setText(R.string.dashboard_na) }
    }

    @SuppressLint("MissingPermission")
    private fun fillDefaultRows() {
        binding.txtDefCalls.text = labelForSubId(SubscriptionManager.getDefaultVoiceSubscriptionId())
        binding.txtDefData.text = labelForSubId(SubscriptionManager.getDefaultDataSubscriptionId())
        binding.txtDefSms.text = labelForSubId(SubscriptionManager.getDefaultSmsSubscriptionId())
    }

    private fun labelForSubId(subId: Int): String =
        lines.firstOrNull { it.subscriptionId == subId }
            ?.let { getString(R.string.dashboard_sim_fmt, it.simSlotIndex + 1, it.carrierName) }
            ?: getString(R.string.dashboard_na)

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun startStrengthWatch(sub: SubscriptionInfo) {
        if (!MineChipNums.isGranted(this)) return
        val tm = managerFor(sub)
        val watcher = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                showStrength(signalStrength)
            }
        }
        strengthWatcher = watcher
        watchedTm = tm
        runCatching { tm.listen(watcher, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
    }

    @Suppress("DEPRECATION")
    private fun stopStrengthWatch() {
        strengthWatcher?.let { watcher ->
            runCatching { watchedTm?.listen(watcher, PhoneStateListener.LISTEN_NONE) }
            strengthWatcher = null
            watchedTm = null
        }
    }

    private fun showStrength(strength: SignalStrength) {
        val bars = strength.level.coerceIn(0, 4)
        paintGauge(bars)
        binding.txtLevel.setText(strengthWord(bars))
        val dbm: Int? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                strength.cellSignalStrengths.firstOrNull { it.dbm != Int.MAX_VALUE && it.dbm != 0 }?.dbm
            else -> null
        }
        binding.txtDbm.text = dbm?.let { getString(R.string.dashboard_live_fmt, it) } ?: ""
    }

    private fun paintGauge(bars: Int) {
        val litRes = if (bars <= 1) R.color.ds_danger else if (bars == 2) R.color.ds_warn else R.color.ds_success
        val lit = ContextCompat.getColor(this, litRes)
        val unlit = ContextCompat.getColor(this, R.color.ds_border)
        arrayOf(binding.part1, binding.part2, binding.part3, binding.part4).forEachIndexed { idx, seg ->
            ViewCompat.setBackgroundTintList(seg, ColorStateList.valueOf(if (idx < bars) lit else unlit))
        }
    }

    private fun managerFor(sub: SubscriptionInfo): TelephonyManager =
        (getSystemService(TelephonyManager::class.java) as TelephonyManager)
            .createForSubscriptionId(sub.subscriptionId)

    private fun strengthWord(bars: Int): Int = when (bars) {
        0 -> R.string.dashboard_no_signal
        1 -> R.string.dashboard_weak
        2 -> R.string.dashboard_fair
        3 -> R.string.dashboard_good
        else -> R.string.dashboard_excellent
    }

    private fun generationOf(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        in THREE_G_TYPES -> "3G"
        in TWO_G_TYPES -> "2G"
        else -> getString(R.string.dashboard_na)
    }

    private fun countryFromIso(iso: String?): String {
        if (iso.isNullOrBlank()) return getString(R.string.dashboard_na)
        val code = iso.uppercase(Locale.US)
        return Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }
    }

    @SuppressLint("MissingPermission")
    private fun describeSimState(slot: Int): String {
        val na = getString(R.string.dashboard_na)
        val tm = getSystemService(TelephonyManager::class.java) as? TelephonyManager ?: return na
        return when (runCatching { tm.getSimState(slot) }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)) {
            TelephonyManager.SIM_STATE_READY -> getString(R.string.dashboard_ready)
            TelephonyManager.SIM_STATE_ABSENT -> "Absent"
            TelephonyManager.SIM_STATE_PIN_REQUIRED, TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Locked"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
            TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
            else -> na
        }
    }

    private fun px(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val THREE_G_TYPES = intArrayOf(
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B,
        )
        private val TWO_G_TYPES = intArrayOf(
            TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT,
        )

        fun intent(context: Context): Intent = Intent(context, DoubleChipOverviewActivity::class.java)
    }
}
