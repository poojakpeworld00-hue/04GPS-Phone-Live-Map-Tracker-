package com.find.phone.gps.live.location.tracker.screens.memo

import android.animation.Animator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.logKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.databinding.ScreenReminderAlertBinding
import com.find.phone.gps.live.location.tracker.memo.MemoProfile
import com.find.phone.gps.live.location.tracker.memo.MemoElement
import com.find.phone.gps.live.location.tracker.memo.MemoTimer
import com.find.phone.gps.live.location.tracker.memo.MemoShelf
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.loop
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.pvh
import com.find.phone.gps.live.location.tracker.helpers.OsModalAide

/**
 * A due reminder over the lock screen (reminders design 6): a bottom sheet rising over the dimmed
 * lock screen. Launched by the reminder notification's full-screen target; cancels that
 * notification as soon as it is up.
 *
 * The sheet's mid native ad loads only once the phone is unlocked with the sheet still up: Play
 * and AdMob do not allow ads on the locked display. `ScreenAds["MemoSirenActivity"].show`
 * in Remote Config switches it off like any other screen's ad.
 *
 * - Call now (a number is set) → unlock, then the dialer with the number filled in.
 *   Open (no number) → unlock, then the Reminders list.
 * - Snooze → the Remote Config choices (design 7); the reminder fires again after that.
 * - Done / Back / Home / Recents → close. The worker already moved the reminder on (a one-off is
 *   done, a repeating one is scheduled for its next time).
 */
class MemoSirenActivity : AppCompatActivity() {

    private lateinit var binding: ScreenReminderAlertBinding
    private var item: MemoElement? = null
    private var snoozeMinutes = 0
    private val animators = mutableListOf<Animator>()
    private var adRequested = false

    /** The user unlocked while the sheet is up: now the ad may show. */
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = loadAdIfUnlocked()
    }

    private val systemDialogHelper by lazy {
        OsModalAide(this, closeOnHome = true) { if (!isFinishing && !isDestroyed) finishAndRemoveTask() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ScreenReminderAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The sheet sits on the navigation bar: its 22dp bottom padding plus the bar.
        val sheetBottom = binding.sirenSheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.sirenRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.sirenSheet.updatePadding(bottom = sheetBottom + bars.bottom)
            insets
        }
        ContextCompat.registerReceiver(
            this, unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
        )
        lifecycle.addObserver(systemDialogHelper)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.lvSnoozeOverlay.isVisible) binding.lvSnoozeOverlay.isVisible = false else finishAndRemoveTask()
            }
        })
        bind(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        bind(intent)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun bind(intent: Intent) {
        NotificationManagerCompat.from(this).cancel(intent.getIntExtra(EXTRA_NOTIF_ID, 0))
        val reminder = MemoShelf.get(this, intent.getLongExtra(EXTRA_ID, -1L))
        if (reminder == null) { finishAndRemoveTask(); return }
        item = reminder
        logKeyEvent("Reminder_Alert_Show")

        binding.sirenTitle.text = reminder.title
        binding.sirenNote.text = reminder.note
        binding.sirenNote.isVisible = reminder.note.isNotBlank()
        binding.sirenNumber.text = reminder.number
        binding.sirenNumber.isVisible = reminder.number.isNotBlank()

        val call = reminder.number.isNotBlank()
        binding.actPrimary.setText(if (call) R.string.rem_call_now else R.string.rem_open)
        binding.actPrimary.setIconResource(if (call) R.drawable.lv_ic_call else 0)
        binding.actPrimary.backgroundTintList =
            ContextCompat.getColorStateList(this, if (call) R.color.ds_success else R.color.ds_primary)
        binding.actPrimary.setOnClickListener { if (call) callBack(reminder) else openList() }
        binding.actSecondary.setOnClickListener { showSnooze() }
        binding.actDone.setOnClickListener {
            logKeyEvent("Reminder_Alert_Done")
            finishAndRemoveTask()
        }
        listOf(binding.actPrimary, binding.actSecondary, binding.actDone).forEach { CraftMovement.pressScale(it) }
        binding.lvSnoozeOverlay.isVisible = false
        riseSheet()
        startMotion()
    }

    override fun onResume() {
        super.onResume()
        loadAdIfUnlocked()
    }

    /** The mid native ad, once per alert and never while the keyguard is up. */
    private fun loadAdIfUnlocked() {
        if (adRequested || isFinishing || isDestroyed) return
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) return
        adRequested = true
        binding.sirenAdFrame.isVisible = true
        EmbeddedTeaser().showMidNative(this, binding.sirenAdFrame)
        logKeyEvent("Reminder_Alert_Ad")
    }

    /** The sheet rises from below (300 ms, decelerate) while the dim fades in. */
    private fun riseSheet() {
        if (CraftMovement.animationsDisabled(this)) return
        val sheet = binding.sirenSheet
        sheet.post {
            sheet.translationY = sheet.height.toFloat()
            sheet.animate().translationY(0f).setDuration(300L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
        }
        binding.sirenRoot.background?.alpha = 0
        android.animation.ValueAnimator.ofInt(0, 255).apply {
            duration = 300L
            addUpdateListener { binding.sirenRoot.background?.alpha = it.animatedValue as Int }
            start()
        }
    }

    // ───────────────────────────── actions ─────────────────────────────

    private fun callBack(reminder: MemoElement) {
        logKeyEvent("Reminder_Alert_Call")
        afterUnlock {
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(reminder.number)))) }
            finishAndRemoveTask()
        }
    }

    private fun openList() {
        logKeyEvent("Reminder_Alert_Open")
        afterUnlock {
            startActivity(MemoListActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishAndRemoveTask()
        }
    }

    /** Asks the user to unlock first (the dialer / app can't show over the lock screen). */
    private fun afterUnlock(then: () -> Unit) {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isKeyguardLocked) { then(); return }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = then()
        })
    }

    /** Snooze choices from `action_delay_options`, two per row; the second is preselected. */
    private fun showSnooze() {
        val options = MemoProfile.get(this).actionDelayOptions.split(',')
            .mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..24 * 60 }.distinct()
            .ifEmpty { listOf(5, 10, 30, 60) }
        snoozeMinutes = options.getOrElse(1) { options.first() }

        val container = binding.lvSnoozeOptions
        container.removeAllViews()
        val tiles = mutableListOf<TextView>()
        options.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, minutes ->
                val tile = TextView(this).apply {
                    text = label(minutes)
                    gravity = android.view.Gravity.CENTER
                    textSize = 15f
                    typeface = ResourcesCompat.getFont(context, R.font.figtree_bold)
                    setTextColor(ContextCompat.getColorStateList(context, R.color.lv_reminder_option_text))
                    setBackgroundResource(R.drawable.lv_reminder_option)
                    isSelected = minutes == snoozeMinutes
                    setOnClickListener {
                        snoozeMinutes = minutes
                        tiles.forEach { t -> t.isSelected = t === this }
                    }
                }
                tiles += tile
                row.addView(tile, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    if (i == 1) marginStart = dp(10)
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(10) })
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(10) })
        }
        binding.lvSnoozeOverlay.setOnClickListener { binding.lvSnoozeOverlay.isVisible = false }
        binding.actSnoozeConfirm.setOnClickListener { snooze() }
        binding.lvSnoozeOverlay.isVisible = true
    }

    private fun snooze() {
        val reminder = item ?: return finishAndRemoveTask()
        MemoTimer.snooze(this, reminder.id, snoozeMinutes)
        logKeyEvent("Reminder_Alert_Snooze_$snoozeMinutes")
        val until = MemoShaper.time(this, System.currentTimeMillis() + snoozeMinutes * 60_000L)
        Toast.makeText(this, getString(R.string.rem_snoozed, until), Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }

    private fun label(minutes: Int): String =
        if (minutes % 60 == 0) resources.getQuantityString(R.plurals.rem_snooze_hours, minutes / 60, minutes / 60)
        else getString(R.string.rem_snooze_min, minutes)

    // ───────────────────────────── motion ─────────────────────────────

    /** Ring pulse (2.4s) and a bell swing every 3s, as on the prompt. */
    private fun startMotion() {
        CraftMovement.cancelAll(animators)
        if (CraftMovement.animationsDisabled(this)) return
        val e = CraftMovement.EASE_OUT
        animators += loop(
            binding.sirenRing, 2400L, 0L,
            pvh(View.SCALE_X, e, 0f to 0.9f, 1f to 1.35f),
            pvh(View.SCALE_Y, e, 0f to 0.9f, 1f to 1.35f),
            pvh(View.ALPHA, e, 0f to 0.9f, 1f to 0f),
        )
        val bell = binding.sirenBell
        bell.post { bell.pivotX = bell.width / 2f; bell.pivotY = bell.height * 0.12f }
        animators += loop(
            bell, 3000L, 0L,
            pvh(View.ROTATION, CraftMovement.EASE_IN_OUT,
                0f to 0f, 0.3f to 0f, 0.4f to 16f, 0.5f to -13f, 0.6f to 9f, 0.7f to -5f, 0.8f to 2f, 0.9f to 0f, 1f to 0f),
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlockReceiver) }
        CraftMovement.cancelAll(animators)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "reminder_id"
        private const val EXTRA_NOTIF_ID = "notif_id"

        fun intent(context: Context, id: Long, notifId: Int): Intent =
            Intent(context, MemoSirenActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NOTIF_ID, notifId)
    }
}
