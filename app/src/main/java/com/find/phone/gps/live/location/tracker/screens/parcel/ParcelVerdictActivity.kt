package com.find.phone.gps.live.location.tracker.screens.parcel

import android.Manifest
import android.animation.ObjectAnimator
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSeatSpots
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.ScreenPackageResultBinding
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.helpers.OsModalAide

/**
 * Shown after another app is added or removed. An added app gets a check of the sensitive
 * permissions it requests (and which are already granted); a removed one gets a confirmation plus
 * the free storage and memory read from the device. The label/icon of a removed package come from
 * [ParcelSpecificsStash] since the package itself no longer exists.
 */
class ParcelVerdictActivity : StageActivity<ScreenPackageResultBinding>() {

    override val layoutId: Int = R.layout.screen_package_result

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exiting = false

    private val isInstallEvent: Boolean by lazy { intent.getBooleanExtra(EXTRA_INSTALLED, true) }
    private val targetPkg: String by lazy { intent.getStringExtra(EXTRA_PACKAGE).orEmpty() }

    private val stashedMeta by lazy {
        if (!isInstallEvent) ParcelSpecificsStash.read(this, targetPkg) else null
    }

    override fun initView() {
        // Leaving via Recents ends it; there is nothing to return to here.
        lifecycle.addObserver(OsModalAide(this) { finishAndRemoveTask() })
        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyEdgeInsets()
        paintHeader()
        bindActions()
        mountBodyNative()
        animateArrival()
        runScan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Back works exactly like Done. */
    override fun performBack() = dismissScreen()

    private fun applyEdgeInsets() {
        val content = binding.parcelContent
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.parcelHero.updatePadding(top = sys.top)
            val lp = binding.parcelActions.layoutParams as? ConstraintLayout.LayoutParams
            if (lp != null) {
                lp.bottomMargin = toPx(16) + sys.bottom
                binding.parcelActions.layoutParams = lp
            }
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    private fun loadIcon(): Drawable? {
        if (isInstallEvent) {
            return runCatching { packageManager.getApplicationIcon(targetPkg) }.getOrNull()
        }
        val file = stashedMeta?.iconFile ?: return null
        val bmp = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull() ?: return null
        return BitmapDrawable(resources, bmp)
    }

    private fun paintHeader() {
        val heading = if (isInstallEvent) R.string.pkgres_privacy_title else R.string.pkgres_optimize_title
        binding.run {
            parcelHero.setBackgroundResource(R.drawable.bgx_pkgres_hero_installed)
            parcelBadge.setBackgroundResource(R.drawable.bgx_pkgres_badge_installed)
            parcelBadgeIcon.setImageResource(if (isInstallEvent) R.drawable.icn_shield_check else R.drawable.icn_check)
            with(parcelStatus) {
                setBackgroundResource(R.drawable.bgx_pkgres_status_installed)
                setText(if (isInstallEvent) R.string.pkgres_privacy_status else R.string.pkgres_optimize_status)
                setTextColor(getColor(R.color.pkgres_installed))
            }
            parcelTitle.text = getString(heading, appLabel())
            parcelSubtitle.text = ""
            parcelAppIcon.setImageDrawable(loadIcon())
        }
    }

    private fun appLabel(): String {
        val live = if (isInstallEvent) runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPkg, 0)).toString()
        }.getOrNull() else null
        if (live != null) return live
        stashedMeta?.label?.takeIf { it.isNotBlank() }?.let { return it }
        intent.getStringExtra(EXTRA_LABEL)?.takeIf { it.isNotBlank() }?.let { return it }
        return targetPkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    // ---------------- scan ----------------

    private fun runScan() {
        val stages = when (isInstallEvent) {
            true -> listOf(R.string.pkgres_privacy_step_read, R.string.pkgres_privacy_step_sensitive, R.string.pkgres_privacy_step_allowed)
            false -> listOf(R.string.pkgres_optimize_step_removed, R.string.pkgres_optimize_step_storage, R.string.pkgres_optimize_step_memory)
        }
        val total = stages.size
        for (pos in 0 until total) {
            val label = stages[pos]
            mainHandler.postDelayed({
                binding.parcelStep.setText(label)
                binding.parcelProgressBar.setProgressCompat((pos + 1) * 100 / total, true)
            }, STAGE_STEP_MS * pos)
        }
        mainHandler.postDelayed({ showOutcome() }, STAGE_STEP_MS * total)
    }

    private fun showOutcome() {
        if (isFinishing || isDestroyed) return
        when {
            isInstallEvent -> showPermissionOutcome()
            else -> showCleanupOutcome()
        }
        binding.parcelProgress.visibility = View.GONE
        // No rows (nothing sensitive requested): keep the card hidden, the subtitle explains.
        binding.parcelChecks.takeIf { it.childCount > 0 }?.run {
            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(260L).start()
        }
        binding.parcelOpen.isEnabled = true
    }

    private fun showPermissionOutcome() {
        val found = requestedSensitive()
        binding.parcelTitle.text = getString(R.string.pkgres_privacy_done_title, appLabel())
        binding.parcelSubtitle.text = when {
            found.isEmpty() -> getString(R.string.pkgres_privacy_summary_none)
            else -> getString(R.string.pkgres_privacy_summary, found.size, found.count { it.second })
        }
        for ((labelRes, granted) in found) {
            val state = if (granted) R.string.pkgres_privacy_allowed else R.string.pkgres_privacy_not_allowed
            val dot = if (granted) R.color.pkgres_removed else R.color.pkgres_installed
            appendRow(getString(labelRes), getString(state), dot)
        }
    }

    private fun showCleanupOutcome() {
        binding.parcelTitle.text = getString(R.string.pkgres_optimize_done_title, appLabel())
        binding.parcelSubtitle.setText(R.string.pkgres_optimize_summary)
        appendRow(getString(R.string.pkgres_optimize_row_removed), getString(R.string.pkgres_optimize_removed), R.color.pkgres_installed)

        runCatching {
            val fs = StatFs(Environment.getDataDirectory().path)
            val summary = getString(R.string.pkgres_of_total, humanBytes(fs.availableBytes), humanBytes(fs.totalBytes))
            appendRow(getString(R.string.pkgres_optimize_row_storage), summary, R.color.pkgres_installed)
        }
        runCatching {
            val mem = ActivityManager.MemoryInfo()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
            val summary = getString(R.string.pkgres_of_total, humanBytes(mem.availMem), humanBytes(mem.totalMem))
            appendRow(getString(R.string.pkgres_optimize_row_memory), summary, R.color.pkgres_installed)
        }
    }

    private fun queryPackage(): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                targetPkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(targetPkg, PackageManager.GET_PERMISSIONS)
        }
    }.getOrNull()

    /** Each sensitive group the app requests, paired with whether anything in it is granted. */
    private fun requestedSensitive(): List<Pair<Int, Boolean>> {
        val pkg = queryPackage() ?: return emptyList()
        val wanted = pkg.requestedPermissions ?: return emptyList()
        val held = HashSet<String>()
        wanted.forEachIndexed { idx, perm -> if (flagGranted(pkg, idx)) held += perm }
        val out = ArrayList<Pair<Int, Boolean>>()
        for ((labelRes, members) in PRIVACY_GROUPS) {
            val requestedHere = members.filter { it in wanted }
            if (requestedHere.isNotEmpty()) out += labelRes to requestedHere.any { it in held }
        }
        return out
    }

    private fun flagGranted(pkg: PackageInfo, idx: Int): Boolean {
        val bits = pkg.requestedPermissionsFlags ?: return false
        if (idx >= bits.size) return false
        return bits[idx] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
    }

    private fun appendRow(heading: String, detail: String, dotColor: Int) {
        val holder = binding.parcelChecks
        LayoutInflater.from(this).inflate(R.layout.cell_pkgres_check, holder, false).also { row ->
            row.findViewById<TextView>(R.id.parcelRowTitle).text = heading
            row.findViewById<TextView>(R.id.parcelRowValue).text = detail
            row.findViewById<View>(R.id.parcelRowDot).background.mutate().setTint(getColor(dotColor))
            holder.addView(row)
        }
    }

    private fun humanBytes(bytes: Long): String = Formatter.formatShortFileSize(this, bytes)

    // ---------------- buttons ----------------

    private fun bindActions() {
        binding.parcelClose.setOnClickListener { dismissScreen() }
        binding.parcelDone.setOnClickListener { dismissScreen() }
        val open = binding.parcelOpen
        if (!isInstallEvent) {
            // A removed app has nothing to review; Done takes the whole row.
            open.visibility = View.GONE
            val done = binding.parcelDone
            (done.layoutParams as? android.widget.LinearLayout.LayoutParams)?.also { lp ->
                lp.marginEnd = 0
                done.layoutParams = lp
            }
            return
        }
        open.setText(R.string.pkgres_privacy_review)
        open.isEnabled = false // enabled once the scan finishes
        open.setOnClickListener { openTargetSettings() }
    }

    private fun openTargetSettings() {
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", targetPkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(details) }
        finishIfAlive()
    }

    private fun fadeRise(view: View, order: Int) {
        val delayMs = 90L * order
        view.alpha = 0f
        ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, toPx(12).toFloat(), 0f).apply {
            startDelay = delayMs
            duration = 320L
            start()
        }
        view.animate().alpha(1f).setStartDelay(delayMs).setDuration(320L).start()
    }

    private fun animateArrival() {
        with(binding.parcelAppIcon) {
            scaleX = 0.6f
            scaleY = 0.6f
            animate()
                .scaleX(1f).scaleY(1f)
                .setInterpolator(OvershootInterpolator(2.2f))
                .setDuration(420L)
                .start()
        }
        binding.parcelBadge.alpha = 0f
        binding.parcelBadge.animate().alpha(1f).setStartDelay(260L).setDuration(220L).start()
        val rising = listOf<View>(binding.parcelStatus, binding.parcelTitle, binding.parcelSubtitle, binding.parcelBody)
        rising.forEachIndexed { order, view -> fadeRise(view, order) }
    }

    private fun mountBodyNative() {
        val wanted = HomeSpotsProfile.packageResultSettings(this).bodyNative
        if (wanted) {
            binding.spotNativeFrame.visibility = View.VISIBLE
            EmbeddedTeaser().presentMidNative2(this, binding.spotNativeFrame, binding.spotShimmer)
        }
    }

    private fun dismissScreen() {
        if (exiting) return
        exiting = true
        listOf<View>(binding.parcelClose, binding.parcelDone, binding.parcelOpen).forEach { it.isEnabled = false }
        // `install_*` / `uninstall_*`: an interstitial first when configured, then finish.
        val placement = if (isInstallEvent) "install" else "uninstall"
        if (!HomeSeatSpots.hasOwnFlow(this, placement)) {
            finishIfAlive()
            return
        }
        HomeSeatSpots.showInterstitial(this, placement) { finishIfAlive() }
    }

    private fun finishIfAlive() {
        if (isFinishing) return
        finish()
    }

    private fun toPx(value: Int) = Math.round(value * resources.displayMetrics.density)

    companion object {
        const val EXTRA_INSTALLED = "package_installed"
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_LABEL = "package_label"
        const val EXTRA_VERSION = "package_version"
        const val EXTRA_CLASS = "package_class"

        private const val STAGE_STEP_MS = 700L

        private val PRIVACY_GROUPS: List<Pair<Int, List<String>>> = listOf(
            R.string.pkgres_perm_location to listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                "android.permission.ACCESS_BACKGROUND_LOCATION",
            ),
            R.string.pkgres_perm_camera to listOf(Manifest.permission.CAMERA),
            R.string.pkgres_perm_microphone to listOf(Manifest.permission.RECORD_AUDIO),
            R.string.pkgres_perm_contacts to listOf(
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.GET_ACCOUNTS,
            ),
            R.string.pkgres_perm_sms to listOf(
                Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            ),
            R.string.pkgres_perm_call_log to listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG),
            R.string.pkgres_perm_phone to listOf(
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE, "android.permission.READ_PHONE_NUMBERS",
            ),
            R.string.pkgres_perm_media to listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO",
                "android.permission.READ_MEDIA_AUDIO",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            ),
            R.string.pkgres_perm_calendar to listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            R.string.pkgres_perm_sensors to listOf(Manifest.permission.BODY_SENSORS),
            R.string.pkgres_perm_nearby to listOf(
                "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT", "android.permission.NEARBY_WIFI_DEVICES",
            ),
            R.string.pkgres_perm_notifications to listOf("android.permission.POST_NOTIFICATIONS"),
        )
    }
}
