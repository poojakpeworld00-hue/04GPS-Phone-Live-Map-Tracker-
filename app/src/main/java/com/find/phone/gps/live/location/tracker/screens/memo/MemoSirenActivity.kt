package com.find.phone.gps.live.location.tracker.screens.memo

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
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
 * Lock-screen sheet for a reminder that is due. Raised by the notification's full-screen intent
 * and dismisses that notification right away. The native ad waits until the device is unlocked.
 */
class MemoSirenActivity : AppCompatActivity() {

    private lateinit var ui: ScreenReminderAlertBinding
    private val runningAnims = mutableListOf<Animator>()
    private var activeMemo: MemoElement? = null
    private var adFetched = false
    private var pickedDelay = 0

    private val osDialogWatcher by lazy {
        OsModalAide(this, closeOnHome = true) { if (!isFinishing && !isDestroyed) finishAndRemoveTask() }
    }

    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = maybeLoadAd()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showAboveKeyguard()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ui = ScreenReminderAlertBinding.inflate(layoutInflater)
        setContentView(ui.root)
        val basePad = ui.sirenSheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(ui.sirenRoot) { _, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            ui.sirenSheet.updatePadding(bottom = basePad + sys.bottom)
            insets
        }
        ContextCompat.registerReceiver(
            this, userPresentReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED,
        )
        lifecycle.addObserver(osDialogWatcher)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    ui.lvSnoozeOverlay.isVisible -> ui.lvSnoozeOverlay.isVisible = false
                    else -> finishAndRemoveTask()
                }
            }
        })
        populateFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        populateFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        maybeLoadAd()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(userPresentReceiver) }
        CraftMovement.abortAll(runningAnims)
        super.onDestroy()
    }

    private fun keyguard(): KeyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    private fun showAboveKeyguard() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 -> {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
            }
            else -> {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun populateFrom(source: Intent) {
        NotificationManagerCompat.from(this).cancel(source.getIntExtra(EXTRA_NOTIF_ID, 0))
        val memo = MemoShelf.get(this, source.getLongExtra(EXTRA_ID, -1L)) ?: run {
            finishAndRemoveTask()
            return
        }
        activeMemo = memo
        recordKeyEvent("Reminder_Alert_Show")

        with(ui) {
            sirenTitle.text = memo.title
            sirenNote.text = memo.note
            sirenNote.isVisible = memo.note.isNotBlank()
            sirenNumber.text = memo.number
            sirenNumber.isVisible = memo.number.isNotBlank()
        }

        val hasNumber = memo.number.isNotBlank()
        ui.actPrimary.apply {
            setText(if (hasNumber) R.string.rem_call_now else R.string.rem_open)
            setIconResource(if (hasNumber) R.drawable.lv_ic_call else 0)
            backgroundTintList = ContextCompat.getColorStateList(
                this@MemoSirenActivity, if (hasNumber) R.color.ds_success else R.color.ds_primary,
            )
            setOnClickListener { if (hasNumber) dialMemoNumber(memo) else openMemoList() }
        }
        ui.actSecondary.setOnClickListener { openSnoozePicker() }
        ui.actDone.setOnClickListener {
            recordKeyEvent("Reminder_Alert_Done")
            finishAndRemoveTask()
        }
        arrayOf(ui.actPrimary, ui.actSecondary, ui.actDone).forEach { CraftMovement.pressScale(it) }
        ui.lvSnoozeOverlay.isVisible = false
        slideSheetIn()
        startAmbientMotion()
    }

    private fun maybeLoadAd() {
        val blocked = adFetched || isFinishing || isDestroyed || keyguard().isKeyguardLocked
        if (blocked) return
        adFetched = true
        ui.sirenAdFrame.isVisible = true
        EmbeddedTeaser().presentMidNative(this, ui.sirenAdFrame)
        recordKeyEvent("Reminder_Alert_Ad")
    }

    private fun slideSheetIn() {
        if (CraftMovement.animationsDisabled(this)) return
        val panel = ui.sirenSheet
        panel.post {
            panel.translationY = panel.height.toFloat()
            panel.animate()
                .translationY(0f)
                .setDuration(SHEET_RISE_MS)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
        ui.sirenRoot.background?.alpha = 0
        ValueAnimator.ofInt(0, 255).also { fader ->
            fader.duration = SHEET_RISE_MS
            fader.addUpdateListener { ui.sirenRoot.background?.alpha = it.animatedValue as Int }
            fader.start()
        }
    }

    private fun dialMemoNumber(memo: MemoElement) {
        recordKeyEvent("Reminder_Alert_Call")
        whenUnlocked {
            runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(memo.number)))) }
            finishAndRemoveTask()
        }
    }

    private fun openMemoList() {
        recordKeyEvent("Reminder_Alert_Open")
        whenUnlocked {
            startActivity(MemoListActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finishAndRemoveTask()
        }
    }

    private fun whenUnlocked(action: () -> Unit) {
        val guard = keyguard()
        if (guard.isKeyguardLocked) {
            guard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = action()
            })
        } else {
            action()
        }
    }

    private fun parseDelayChoices(): List<Int> =
        MemoProfile.get(this).actionDelayOptions.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..24 * 60 }
            .distinct()
            .ifEmpty { listOf(5, 10, 30, 60) }

    private fun openSnoozePicker() {
        val choices = parseDelayChoices()
        pickedDelay = choices.getOrElse(1) { choices.first() }

        val holder = ui.lvSnoozeOptions
        holder.removeAllViews()
        val chips = ArrayList<TextView>()
        for (rowItems in choices.chunked(2)) {
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { col, mins ->
                val chip = buildDelayChip(mins, chips)
                chips.add(chip)
                rowView.addView(chip, LinearLayout.LayoutParams(0, px(52), 1f).apply {
                    if (col == 1) marginStart = px(10)
                })
            }
            if (rowItems.size == 1) {
                rowView.addView(View(this), LinearLayout.LayoutParams(0, px(52), 1f).apply { marginStart = px(10) })
            }
            holder.addView(
                rowView,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = px(10) },
            )
        }
        ui.lvSnoozeOverlay.setOnClickListener { ui.lvSnoozeOverlay.isVisible = false }
        ui.actSnoozeConfirm.setOnClickListener { applySnooze() }
        ui.lvSnoozeOverlay.isVisible = true
    }

    private fun buildDelayChip(mins: Int, group: List<TextView>): TextView = TextView(this).apply {
        text = delayCaption(mins)
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = ResourcesCompat.getFont(context, R.font.figtree_bold)
        setTextColor(ContextCompat.getColorStateList(context, R.color.lv_reminder_option_text))
        setBackgroundResource(R.drawable.lv_reminder_option)
        isSelected = mins == pickedDelay
        setOnClickListener {
            pickedDelay = mins
            group.forEach { other -> other.isSelected = other === this }
        }
    }

    private fun applySnooze() {
        val memo = activeMemo ?: return finishAndRemoveTask()
        MemoTimer.snooze(this, memo.id, pickedDelay)
        recordKeyEvent("Reminder_Alert_Snooze_$pickedDelay")
        val wakeAt = MemoShaper.time(this, System.currentTimeMillis() + pickedDelay * 60_000L)
        Toast.makeText(this, getString(R.string.rem_snoozed, wakeAt), Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
    }

    private fun delayCaption(mins: Int): String {
        if (mins % 60 != 0) return getString(R.string.rem_snooze_min, mins)
        val hrs = mins / 60
        return resources.getQuantityString(R.plurals.rem_snooze_hours, hrs, hrs)
    }

    private fun startAmbientMotion() {
        CraftMovement.abortAll(runningAnims)
        if (CraftMovement.animationsDisabled(this)) return
        val ease = CraftMovement.EASE_OUT
        runningAnims += loop(
            ui.sirenRing, 2400L, 0L,
            pvh(View.SCALE_X, ease, 0f to 0.9f, 1f to 1.35f),
            pvh(View.SCALE_Y, ease, 0f to 0.9f, 1f to 1.35f),
            pvh(View.ALPHA, ease, 0f to 0.9f, 1f to 0f),
        )
        val bellView = ui.sirenBell
        bellView.post {
            bellView.pivotX = bellView.width / 2f
            bellView.pivotY = bellView.height * 0.12f
        }
        runningAnims += loop(
            bellView, 3000L, 0L,
            pvh(View.ROTATION, CraftMovement.EASE_IN_OUT,
                0f to 0f, 0.3f to 0f, 0.4f to 16f, 0.5f to -13f, 0.6f to 9f, 0.7f to -5f, 0.8f to 2f, 0.9f to 0f, 1f to 0f),
        )
    }

    private fun px(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_ID = "reminder_id"
        private const val EXTRA_NOTIF_ID = "notif_id"
        private const val SHEET_RISE_MS = 300L

        fun intent(context: Context, id: Long, notifId: Int): Intent =
            Intent(context, MemoSirenActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NOTIF_ID, notifId)
    }
}
