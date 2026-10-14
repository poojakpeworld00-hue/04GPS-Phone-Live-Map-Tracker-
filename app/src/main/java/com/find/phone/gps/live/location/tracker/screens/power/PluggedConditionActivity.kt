package com.find.phone.gps.live.location.tracker.screens.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSeatSpots
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotTallyBook
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.render.StraightHrefOpener
import com.find.phone.gps.live.location.tracker.monetize.render.SliderSpotDriver
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaserRibbon
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.helpers.OsModalAide
import com.find.phone.gps.live.location.tracker.helpers.enactNativeAdTheme

/**
 * Power connected / disconnected screen, started by [JuiceHappeningSentinel] once its gates pass.
 * Paints the live battery snapshot, fills the bottom native slot per `system_ads.<trigger>.native`
 * and plays the configured `close_ad` on exit.
 */
class PluggedConditionActivity : AppCompatActivity() {

    private var exiting = false

    private val eventKey: String
        get() = intent.getStringExtra(EXTRA_TRIGGER) ?: TRIGGER_CHARGE

    private val adCfg by lazy { HomeSpotsProfile.systemAdSettings(this, eventKey) }

    private val endsWithFullNative: Boolean
        get() = adCfg.closeAd.trim().lowercase() in setOf("full_native", "fullnative", "full")

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = paintBattery(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enactNativeAdTheme()
        lifecycle.addObserver(OsModalAide(this) { finishAndRemoveTask() })
        wakeAboveKeyguard()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.screen_charging_status)

        val body = findViewById<View>(R.id.juiceFunctionalContent)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.juiceRoot)) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            body.updatePadding(top = sys.top)
            findViewById<View>(R.id.juiceAdSlot).updatePadding(bottom = sys.bottom)
            insets
        }

        closeButton().setOnClickListener { leaveScreen() }
        onBackPressedDispatcher.addCallback(this) { leaveScreen() }
        paintBattery(registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        loadBodyNative()
        // Warm the exit ad while the user is looking at the screen.
        if (endsWithFullNative) SliderSpotDriver.preload(this, buildFullNativeFlow())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(powerReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    override fun onStop() {
        runCatching { unregisterReceiver(powerReceiver) }
        super.onStop()
    }

    private fun buildFullNativeFlow() = HomeSpotsProfile.DrawerAdFlow(
        enabled = true,
        counter = 0,
        sequence = listOf(
            HomeSpotsProfile.DrawerAdSpec(
                HomeSpotsProfile.DrawerAdType.FULLSCREEN_NATIVE,
                SpotsLocker.getInstance(this).getString("googleNative").orEmpty(),
            )
        ),
        startFromFirst = true,
    )

    private fun closeButton(): ImageButton = findViewById(R.id.actCloseCharging)

    private fun label(id: Int): TextView = findViewById(id)

    private fun percentOf(snapshot: Intent?): Int {
        if (snapshot == null) return 0
        val raw = snapshot.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val max = snapshot.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (raw < 0 || max <= 0) 0 else raw * 100 / max
    }

    private fun paintBattery(snapshot: Intent?) {
        val pct = percentOf(snapshot)
        val plugFlags = snapshot?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val onPower = plugFlags != 0

        findViewById<PluggedChargeView>(R.id.juiceEnergyView).run {
            setConnected(onPower)
            setProgress(pct, animate = true)
        }
        label(R.id.juicePercentage).text = getString(R.string.charging_percentage_value, pct)

        if (onPower) paintCharging(pct, plugFlags) else paintUnplugged(pct)
    }

    private fun paintCharging(pct: Int, plugFlags: Int) {
        val source = describeSource(plugFlags)
        label(R.id.juiceModeLabel).setText(R.string.charging_live_energy)
        label(R.id.juiceTitle).setText(R.string.charging_connected_title)
        label(R.id.juiceSubtitle).text = getString(R.string.charging_connected_subtitle, source)
        label(R.id.juiceMetricLeftLabel).setText(R.string.charging_until_full)
        label(R.id.juiceMetricLeftValue).text = fullChargeEta(pct)
        label(R.id.juiceMetricRightLabel).setText(R.string.charging_power_source)
        label(R.id.juiceMetricRightValue).text = describeSource(plugFlags)
        label(R.id.juiceStatusText).setText(R.string.charging_power_flow_active)
        findViewById<View>(R.id.juiceInfoCard).visibility = View.VISIBLE
    }

    private fun paintUnplugged(pct: Int) {
        label(R.id.juiceModeLabel).setText(R.string.charging_session_complete)
        label(R.id.juiceTitle).setText(R.string.charging_disconnected_title)
        label(R.id.juiceSubtitle).text = getString(R.string.charging_ready_to_use, pct)
        label(R.id.juiceStatusText).setText(R.string.charging_disconnected_safely)
        findViewById<View>(R.id.juiceInfoCard).visibility = View.GONE
    }

    private fun fullChargeEta(pct: Int): String {
        if (pct >= 100) return getString(R.string.charging_time_full)
        val etaMs = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                (getSystemService(BATTERY_SERVICE) as? BatteryManager)?.computeChargeTimeRemaining() ?: -1L
            else -> -1L
        }
        if (etaMs <= 0L) return getString(R.string.charging_time_calculating)
        val totalMin = ((etaMs + 30_000) / 60_000).coerceAtLeast(1L)
        val hrs = totalMin / 60
        val mins = totalMin % 60
        return when {
            hrs > 0 -> getString(R.string.charging_time_hours_minutes, hrs, mins)
            else -> getString(R.string.charging_time_minutes, mins)
        }
    }

    private fun describeSource(plugFlags: Int): String {
        val res = when {
            plugFlags and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> R.string.charging_source_wireless
            plugFlags and BatteryManager.BATTERY_PLUGGED_USB != 0 -> R.string.charging_source_usb
            plugFlags and BatteryManager.BATTERY_PLUGGED_AC != 0 -> R.string.charging_source_ac
            else -> R.string.charging_source_power
        }
        return getString(res)
    }

    private fun loadBodyNative() {
        if (!adCfg.showsBodyNative) return
        val holder = findViewById<FrameLayout>(R.id.juiceAdSlot)
        holder.visibility = View.VISIBLE
        when (adCfg.nativeType.trim().lowercase()) {
            "big", "big_native", "bignative" -> EmbeddedTeaser().presentBigNative(this, holder)
            "mid2", "mid_native2", "midnative2", "mediumnative2" -> EmbeddedTeaser().presentMidNative2(this, holder)
            "native_banner", "nativebanner" -> EmbeddedTeaserRibbon().presentNativeBannerNative(this, holder)
            else -> EmbeddedTeaser().presentMidNative(this, holder)
        }
    }

    /** Leaves after the configured exit ad; the screen always closes, ad or not. */
    private fun leaveScreen() {
        if (exiting) return
        exiting = true
        closeButton().isEnabled = false
        if (HomeSeatSpots.hasOwnFlow(this, eventKey)) {
            HomeSeatSpots.showInterstitial(this, eventKey) { finishIfAlive() }
            return
        }
        when {
            adCfg.closeShowsInterstitial -> {
                // Bypass the global inter counter: this screen throttles itself.
                SpotTallyBook.interCounter = SpotsLocker.getInstance(this).getInt("InterCounter")
                TakeoverStandard().presentInterAds(this) { finishIfAlive() }
            }
            endsWithFullNative ->
                SliderSpotDriver.run(this, buildFullNativeFlow(), "__charging_full_native_ptr") { finishIfAlive() }
            adCfg.closeShowsLink -> {
                StraightHrefOpener.open(this, HomeSpotsProfile.directLink(this))
                finishIfAlive()
            }
            else -> finishIfAlive()
        }
    }

    private fun finishIfAlive() {
        if (isFinishing) return
        finish()
    }

    private fun wakeAboveKeyguard() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    companion object {
        const val EXTRA_TRIGGER = "system_ad_trigger"
        const val TRIGGER_CHARGE = "charge"
        const val TRIGGER_DISCHARGE = "discharge"
    }
}
