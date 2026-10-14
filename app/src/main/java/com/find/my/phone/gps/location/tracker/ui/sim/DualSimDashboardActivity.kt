package com.find.my.phone.gps.location.tracker.ui.sim

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
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.base.CanvasActivity
import com.find.my.phone.gps.location.tracker.data.sim.MySimNumbers
import com.find.my.phone.gps.location.tracker.databinding.ViewDualSimDashboardBinding
import com.find.my.phone.gps.location.tracker.util.isNightMode
import java.util.Locale

/**
 * SIM Tools "Dual-SIM dashboard" (design 1b). Per-SIM: a live signal gauge (SignalStrength),
 * network type, roaming, country and SIM state; plus the default SIM for calls, data and SMS.
 * Read-only, READ_PHONE_STATE only. Signal updates while the screen is resumed.
 */
class DualSimDashboardActivity : CanvasActivity<ViewDualSimDashboardBinding>() {

    override val layoutId: Int = R.layout.view_dual_sim_dashboard

    private var subs: List<SubscriptionInfo> = emptyList()
    private var selectedIndex = 0
    private var listening: TelephonyManager? = null
    @Suppress("DEPRECATION")
    private var listener: PhoneStateListener? = null

    override fun initView() {
        // Dark icons on the light canvas, light icons in dark mode.
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.dashRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }
        subs = activeSubs()
        buildSelector()
        renderDefaults()
        renderSelected()
    }

    override fun onStart() {
        super.onStart()
        if (subs.isNotEmpty()) registerSignal(subs[selectedIndex])
    }

    override fun onStop() {
        stopSignal()
        super.onStop()
    }

    @SuppressLint("MissingPermission")
    private fun activeSubs(): List<SubscriptionInfo> {
        if (!MySimNumbers.isGranted(this)) return emptyList()
        val sm = getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching { sm.activeSubscriptionInfoList }.getOrNull().orEmpty()
            .sortedBy { it.simSlotIndex }
    }

    private fun buildSelector() {
        binding.simSelector.removeAllViews()
        if (subs.size < 2) {
            binding.simSelector.visibility = View.GONE
            return
        }
        subs.forEachIndexed { i, info ->
            val tab = TextView(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, dp(36), 1f).also { lp ->
                    if (i > 0) lp.marginStart = dp(4)
                }
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 13f
                text = getString(R.string.dashboard_sim_fmt, info.simSlotIndex + 1, info.carrierName)
                setOnClickListener { select(i) }
            }
            binding.simSelector.addView(tab)
        }
        styleSelector()
    }

    private fun styleSelector() {
        for (i in 0 until binding.simSelector.childCount) {
            val tab = binding.simSelector.getChildAt(i) as TextView
            val on = i == selectedIndex
            tab.setTextColor(ContextCompat.getColor(this, if (on) R.color.ds_primary else R.color.ds_muted))
            tab.typeface = ResourcesCompat.getFont(this, if (on) R.font.figtree_bold else R.font.figtree_semibold)
            tab.background = if (on) ContextCompat.getDrawable(this, R.drawable.ds_seg_thumb_r10) else null
            tab.elevation = if (on) dp(2).toFloat() else 0f
        }
    }

    private fun select(i: Int) {
        if (i == selectedIndex) return
        selectedIndex = i
        styleSelector()
        stopSignal()
        renderSelected()
        registerSignal(subs[i])
    }

    @SuppressLint("MissingPermission")
    private fun renderSelected() {
        val info = subs.getOrNull(selectedIndex)
        if (info == null) {
            binding.tvLevel.setText(R.string.dashboard_na)
            binding.tvDbm.text = ""
            setMeter(0)
            listOf(binding.tvNetwork, binding.tvRoaming, binding.tvCountry, binding.tvSimState)
                .forEach { it.setText(R.string.dashboard_na) }
            return
        }
        val tm = telephonyFor(info)
        binding.tvNetwork.text = networkLabel(runCatching { tm.dataNetworkType }.getOrDefault(TelephonyManager.NETWORK_TYPE_UNKNOWN))
        binding.tvRoaming.setText(if (runCatching { tm.isNetworkRoaming }.getOrDefault(false)) R.string.dashboard_on else R.string.dashboard_off)
        binding.tvCountry.text = countryName(runCatching { tm.networkCountryIso }.getOrNull()
            ?: runCatching { tm.simCountryIso }.getOrNull())
        binding.tvSimState.text = simStateLabel(info.simSlotIndex)
    }

    @SuppressLint("MissingPermission")
    private fun renderDefaults() {
        binding.tvDefCalls.text = subLabel(SubscriptionManager.getDefaultVoiceSubscriptionId())
        binding.tvDefData.text = subLabel(SubscriptionManager.getDefaultDataSubscriptionId())
        binding.tvDefSms.text = subLabel(SubscriptionManager.getDefaultSmsSubscriptionId())
    }

    private fun subLabel(subId: Int): String {
        val info = subs.firstOrNull { it.subscriptionId == subId } ?: return getString(R.string.dashboard_na)
        return getString(R.string.dashboard_sim_fmt, info.simSlotIndex + 1, info.carrierName)
    }

    // ── live signal ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun registerSignal(info: SubscriptionInfo) {
        if (!MySimNumbers.isGranted(this)) return
        val tm = telephonyFor(info)
        val l = object : PhoneStateListener() {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                onSignal(signalStrength)
            }
        }
        listener = l
        listening = tm
        runCatching { tm.listen(l, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS) }
    }

    @Suppress("DEPRECATION")
    private fun stopSignal() {
        val l = listener ?: return
        runCatching { listening?.listen(l, PhoneStateListener.LISTEN_NONE) }
        listener = null
        listening = null
    }

    private fun onSignal(ss: SignalStrength) {
        val level = ss.level.coerceIn(0, 4)
        setMeter(level)
        binding.tvLevel.setText(levelLabel(level))
        val dbm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ss.cellSignalStrengths.firstOrNull { it.dbm != Int.MAX_VALUE && it.dbm != 0 }?.dbm
        } else null
        binding.tvDbm.text = if (dbm != null) getString(R.string.dashboard_live_fmt, dbm) else ""
    }

    private fun setMeter(level: Int) {
        val segs = listOf(binding.seg1, binding.seg2, binding.seg3, binding.seg4)
        val onColor = ContextCompat.getColor(this, when {
            level <= 1 -> R.color.ds_danger
            level == 2 -> R.color.ds_warn
            else -> R.color.ds_success
        })
        val off = ContextCompat.getColor(this, R.color.ds_border)
        segs.forEachIndexed { i, v ->
            ViewCompat.setBackgroundTintList(v, ColorStateList.valueOf(if (i < level) onColor else off))
        }
    }

    // ── labels ───────────────────────────────────────────────────────────────

    private fun telephonyFor(info: SubscriptionInfo): TelephonyManager =
        (getSystemService(TelephonyManager::class.java) as TelephonyManager)
            .createForSubscriptionId(info.subscriptionId)

    private fun levelLabel(level: Int): Int = when (level) {
        0 -> R.string.dashboard_no_signal
        1 -> R.string.dashboard_weak
        2 -> R.string.dashboard_fair
        3 -> R.string.dashboard_good
        else -> R.string.dashboard_excellent
    }

    private fun networkLabel(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
        else -> getString(R.string.dashboard_na)
    }

    private fun countryName(iso: String?): String {
        if (iso.isNullOrBlank()) return getString(R.string.dashboard_na)
        val name = Locale("", iso.uppercase(Locale.US)).getDisplayCountry(Locale.ENGLISH)
        return name.ifBlank { iso.uppercase(Locale.US) }
    }

    @SuppressLint("MissingPermission")
    private fun simStateLabel(slot: Int): String {
        val tm = getSystemService(TelephonyManager::class.java) as? TelephonyManager
            ?: return getString(R.string.dashboard_na)
        val state = runCatching { tm.getSimState(slot) }.getOrDefault(TelephonyManager.SIM_STATE_UNKNOWN)
        return when (state) {
            TelephonyManager.SIM_STATE_READY -> getString(R.string.dashboard_ready)
            TelephonyManager.SIM_STATE_ABSENT -> "Absent"
            TelephonyManager.SIM_STATE_PIN_REQUIRED,
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "Locked"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "Network locked"
            TelephonyManager.SIM_STATE_NOT_READY -> "Not ready"
            else -> getString(R.string.dashboard_na)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context): Intent = Intent(context, DualSimDashboardActivity::class.java)
    }
}
