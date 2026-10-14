package com.find.phone.gps.live.location.tracker.screens.memo

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.lifecycle.lifecycleScope
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.foundation.StageActivity
import com.find.phone.gps.live.location.tracker.databinding.DlgReminderDeleteBinding
import com.find.phone.gps.live.location.tracker.databinding.CellReminderBinding
import com.find.phone.gps.live.location.tracker.databinding.ScreenReminderListBinding
import com.find.phone.gps.live.location.tracker.memo.CuePortal
import com.find.phone.gps.live.location.tracker.memo.MemoElement
import com.find.phone.gps.live.location.tracker.memo.MemoTimer
import com.find.phone.gps.live.location.tracker.memo.MemoShelf
import com.find.phone.gps.live.location.tracker.memo.Repeat
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsent
import com.find.phone.gps.live.location.tracker.memo.grant.MemoCueModal
import com.find.phone.gps.live.location.tracker.screens.people.BuddyChooserActivity
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.helpers.followAdContainer
import com.find.phone.gps.live.location.tracker.helpers.isNightMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reminders (design 2): Upcoming and Done, a switch per reminder, "+ New reminder". Opened from
 * the drawer. Also the host of the "Turn on reminders" prompt, which appears after the user saves
 * their first reminder (design 4), so the ask always comes with a reason.
 */
class MemoListActivity : StageActivity<ScreenReminderListBinding>() {

    override val layoutId: Int = R.layout.screen_reminder_list

    private var accessPollJob: Job? = null
    private var pendingNumber: ((String) -> Unit)? = null
    private var afterNotificationAsk: (() -> Unit)? = null

    private val contactPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.getStringExtra(BuddyChooserActivity.EXTRA_NUMBER)?.let { pendingNumber?.invoke(it) }
        pendingNumber = null
    }

    /** Whatever the answer, carry on: the reminder is saved either way. */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Deferred: the result lands around the resume that follows the system dialog, and
        // opening Settings inside it races onResume / onHostResume.
        val next = afterNotificationAsk
        afterNotificationAsk = null
        window.decorView.post { next?.invoke() }
    }

    override fun initView() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.lvRemindersRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.actBack.setOnClickListener { goBack() }
        CraftMovement.pressScale(binding.actNewReminder)
        binding.actNewReminder.setOnClickListener { enterSheet(null) }
        recordKeyEvent("Reminders_Open")

        // Bottom mid native (ScreenAds["MemoListActivity"].show turns it off).
        EmbeddedTeaser().presentMidNative(this, binding.spotNativeFrame, binding.spotShimmer)
        binding.spotNativeDivider.followAdContainer(binding.spotNativeFrame)
    }

    override fun onResume() {
        super.onResume()
        accessPollJob?.cancel()
        MemoConsent.onHostResume(this)
        MemoShelf.plantDefaultsIfNeeded(this)
        render()
    }

    override fun onDestroy() {
        accessPollJob?.cancel()
        MemoCueModal.retireIfShowing()
        super.onDestroy()
    }

    // ───────────────────────────── list ─────────────────────────────

    private fun render() {
        val container = binding.listReminders
        container.removeAllViews()
        val all = MemoShelf.load(this)
        val upcoming = all.filter { !it.done }.sortedBy { it.dateTime }
        val done = all.filter { it.done }.sortedByDescending { it.dateTime }

        binding.blankState.isVisible = all.isEmpty()
        if (upcoming.isNotEmpty()) {
            container.addView(sectionLabel(R.string.rem_upcoming))
            upcoming.forEach { container.addView(card(it)) }
        }
        if (done.isNotEmpty()) {
            container.addView(sectionLabel(R.string.rem_done_section))
            done.forEach { container.addView(card(it)) }
        }
    }

    private fun sectionLabel(res: Int) = TextView(this).apply {
        setText(res)
        isAllCaps = true
        letterSpacing = 0.08f
        textSize = 11f
        setTextColor(ContextCompat.getColor(context, R.color.ds_muted))
        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.figtree_bold)
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(4), dp(10), 0, dp(8)) }
    }

    private fun card(item: MemoElement) = CellReminderBinding.inflate(LayoutInflater.from(this), binding.listReminders, false).apply {
        txtTitle.text = item.title
        txtWhen.text = MemoShaper.dayAndTime(this@MemoListActivity, item.dateTime)
        txtNumber.text = item.number
        txtNumber.isVisible = item.number.isNotBlank()
        txtRepeat.text = MemoShaper.repeatLabel(this@MemoListActivity, item.repeatOrOnce)

        // A number makes it a call-back (phone, purple); otherwise a plain reminder (bell, blue).
        val call = item.number.isNotBlank()
        badgeTile.setBackgroundResource(if (call) R.drawable.lv_tile_purple_r12 else R.drawable.lv_tile_tint_r12)
        badgeVw.setImageResource(if (call) R.drawable.lv_ic_call else R.drawable.pmt_ic_bell)
        ImageViewCompat.setImageTintList(
            badgeVw, ContextCompat.getColorStateList(this@MemoListActivity, if (call) R.color.ds_purple else R.color.ds_primary),
        )

        elemReminder.alpha = if (item.done) 0.6f else 1f
        txtTitle.paintFlags = if (item.done) txtTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else txtTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        lvSwEnabled.setOnCheckedChangeListener(null)
        lvSwEnabled.isChecked = item.isActive
        lvSwEnabled.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }

        elemReminder.setOnClickListener { enterSheet(item) }
        elemReminder.setOnLongClickListener { affirmDelete(item); true }
    }.root

    private fun onToggle(item: MemoElement, on: Boolean) {
        // Turning a finished one-off back on needs a new time: open it instead.
        if (on && item.repeatOrOnce == Repeat.ONCE && item.dateTime <= System.currentTimeMillis()) {
            render()
            enterSheet(item)
            return
        }
        MemoTimer.save(this, item.copy(enabled = on, done = false))
        recordKeyEvent(if (on) "Reminder_Enable" else "Reminder_Disable")
        render()
    }

    // ───────────────────────────── edit / delete ─────────────────────────────

    private fun enterSheet(item: MemoElement?) {
        MemoAmendDrawer(
            activity = this,
            existing = item,
            onPickContact = { onNumber ->
                pendingNumber = onNumber
                contactPicker.launch(BuddyChooserActivity.intent(this))
            },
            onSave = { saved, isNew -> onSaved(saved, isNew) },
            onDelete = { affirmDelete(it) },
        ).show()
    }

    private fun onSaved(item: MemoElement, isNew: Boolean) {
        MemoTimer.save(this, item)
        render()
        val stored = MemoShelf.get(this, item.id) ?: item
        Toast.makeText(this, getString(R.string.rem_saved, MemoShaper.dayAndTime(this, stored.dateTime)), Toast.LENGTH_SHORT).show()
        recordKeyEvent(if (isNew) "Reminder_Create" else "Reminder_Edit")
        if (isNew) {
            CuePortal.onReminderCreated(this)
            queryAfterSave(stored)
        }
    }

    /**
     * After a save: the "Turn on reminders" prompt (Remote Config gated). Its button first asks
     * for notifications when they are off (13+), then opens the reminders setting. When the
     * prompt does not show but notifications are off, ask for those anyway: nothing can be shown
     * without them.
     */
    private fun queryAfterSave(item: MemoElement) {
        MemoConsent.prime(
            activity = this,
            returnTo = MemoListActivity::class.java,
            reminderTitle = item.title,
            reminderTime = MemoShaper.time(this, item.dateTime),
            onReady = { if (needsNotificationPermission()) queryNotifications {} },
            onSettingsOpened = { beginAccessPoll() },
            onManage = { proceed -> if (needsNotificationPermission()) queryNotifications(proceed) else proceed() },
        )
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun queryNotifications(then: () -> Unit) {
        afterNotificationAsk = then
        runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure { afterNotificationAsk = null; then() }
    }

    /** The primary return path: Settings sits in this task, so this Activity may bring itself back. */
    private fun beginAccessPoll() {
        accessPollJob?.cancel()
        accessPollJob = lifecycleScope.launch {
            while (isActive) {
                if (MemoConsent.isGranted(this@MemoListActivity)) {
                    runCatching { startActivity(MemoConsent.returnIntent(this@MemoListActivity, MemoListActivity::class.java)) }
                    break
                }
                delay(500L)
            }
        }
    }

    private fun affirmDelete(item: MemoElement) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = DlgReminderDeleteBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        view.txtDeleteBody.text = getString(R.string.rem_delete_body, item.title)
        view.actCancel.setOnClickListener { dialog.dismiss() }
        view.actConfirmDelete.setOnClickListener {
            dialog.dismiss()
            MemoTimer.delete(this, item.id)
            recordKeyEvent("Reminder_Delete")
            render()
        }
        dialog.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: android.content.Context) = Intent(context, MemoListActivity::class.java)
    }
}
