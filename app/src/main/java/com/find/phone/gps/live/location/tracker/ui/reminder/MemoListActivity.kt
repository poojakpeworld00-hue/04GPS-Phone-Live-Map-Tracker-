package com.find.phone.gps.live.location.tracker.ui.reminder

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
import com.find.phone.gps.live.location.tracker.adcast.domain.logKeyEvent
import com.find.phone.gps.live.location.tracker.adcast.presentation.EmbeddedTeaser
import com.find.phone.gps.live.location.tracker.base.StageActivity
import com.find.phone.gps.live.location.tracker.databinding.DialogReminderDeleteBinding
import com.find.phone.gps.live.location.tracker.databinding.TileReminderBinding
import com.find.phone.gps.live.location.tracker.databinding.ViewReminderListBinding
import com.find.phone.gps.live.location.tracker.reminder.CuePortal
import com.find.phone.gps.live.location.tracker.reminder.MemoElement
import com.find.phone.gps.live.location.tracker.reminder.MemoTimer
import com.find.phone.gps.live.location.tracker.reminder.MemoShelf
import com.find.phone.gps.live.location.tracker.reminder.Repeat
import com.find.phone.gps.live.location.tracker.reminder.access.MemoConsent
import com.find.phone.gps.live.location.tracker.reminder.access.MemoCueModal
import com.find.phone.gps.live.location.tracker.ui.contacts.BuddyChooserActivity
import com.find.phone.gps.live.location.tracker.ui.design.CraftMovement
import com.find.phone.gps.live.location.tracker.util.followAdContainer
import com.find.phone.gps.live.location.tracker.util.isNightMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reminders (design 2): Upcoming and Done, a switch per reminder, "+ New reminder". Opened from
 * the drawer. Also the host of the "Turn on reminders" prompt, which appears after the user saves
 * their first reminder (design 4), so the ask always comes with a reason.
 */
class MemoListActivity : StageActivity<ViewReminderListBinding>() {

    override val layoutId: Int = R.layout.view_reminder_list

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
        ViewCompat.setOnApplyWindowInsetsListener(binding.remindersRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.btnBack.setOnClickListener { goBack() }
        CraftMovement.pressScale(binding.btnNewReminder)
        binding.btnNewReminder.setOnClickListener { openSheet(null) }
        logKeyEvent("Reminders_Open")

        // Bottom mid native (ScreenAds["MemoListActivity"].show turns it off).
        EmbeddedTeaser().showMidNative(this, binding.adNativeFrame, binding.adShimmer)
        binding.adNativeDivider.followAdContainer(binding.adNativeFrame)
    }

    override fun onResume() {
        super.onResume()
        accessPollJob?.cancel()
        MemoConsent.onHostResume(this)
        MemoShelf.seedDefaultsIfNeeded(this)
        render()
    }

    override fun onDestroy() {
        accessPollJob?.cancel()
        MemoCueModal.dismissIfShowing()
        super.onDestroy()
    }

    // ───────────────────────────── list ─────────────────────────────

    private fun render() {
        val container = binding.rvReminders
        container.removeAllViews()
        val all = MemoShelf.load(this)
        val upcoming = all.filter { !it.done }.sortedBy { it.dateTime }
        val done = all.filter { it.done }.sortedByDescending { it.dateTime }

        binding.emptyState.isVisible = all.isEmpty()
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

    private fun card(item: MemoElement) = TileReminderBinding.inflate(LayoutInflater.from(this), binding.rvReminders, false).apply {
        tvTitle.text = item.title
        tvWhen.text = MemoShaper.dayAndTime(this@MemoListActivity, item.dateTime)
        tvNumber.text = item.number
        tvNumber.isVisible = item.number.isNotBlank()
        tvRepeat.text = MemoShaper.repeatLabel(this@MemoListActivity, item.repeatOrOnce)

        // A number makes it a call-back (phone, purple); otherwise a plain reminder (bell, blue).
        val call = item.number.isNotBlank()
        iconTile.setBackgroundResource(if (call) R.drawable.ds_tile_purple_r12 else R.drawable.ds_tile_tint_r12)
        icon.setImageResource(if (call) R.drawable.ds_ic_call else R.drawable.ps_ic_bell)
        ImageViewCompat.setImageTintList(
            icon, ContextCompat.getColorStateList(this@MemoListActivity, if (call) R.color.ds_purple else R.color.ds_primary),
        )

        itemReminder.alpha = if (item.done) 0.6f else 1f
        tvTitle.paintFlags = if (item.done) tvTitle.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        else tvTitle.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()

        swEnabled.setOnCheckedChangeListener(null)
        swEnabled.isChecked = item.isActive
        swEnabled.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }

        itemReminder.setOnClickListener { openSheet(item) }
        itemReminder.setOnLongClickListener { confirmDelete(item); true }
    }.root

    private fun onToggle(item: MemoElement, on: Boolean) {
        // Turning a finished one-off back on needs a new time: open it instead.
        if (on && item.repeatOrOnce == Repeat.ONCE && item.dateTime <= System.currentTimeMillis()) {
            render()
            openSheet(item)
            return
        }
        MemoTimer.save(this, item.copy(enabled = on, done = false))
        logKeyEvent(if (on) "Reminder_Enable" else "Reminder_Disable")
        render()
    }

    // ───────────────────────────── edit / delete ─────────────────────────────

    private fun openSheet(item: MemoElement?) {
        MemoAmendDrawer(
            activity = this,
            existing = item,
            onPickContact = { onNumber ->
                pendingNumber = onNumber
                contactPicker.launch(BuddyChooserActivity.intent(this))
            },
            onSave = { saved, isNew -> onSaved(saved, isNew) },
            onDelete = { confirmDelete(it) },
        ).show()
    }

    private fun onSaved(item: MemoElement, isNew: Boolean) {
        MemoTimer.save(this, item)
        render()
        val stored = MemoShelf.get(this, item.id) ?: item
        Toast.makeText(this, getString(R.string.rem_saved, MemoShaper.dayAndTime(this, stored.dateTime)), Toast.LENGTH_SHORT).show()
        logKeyEvent(if (isNew) "Reminder_Create" else "Reminder_Edit")
        if (isNew) {
            CuePortal.onReminderCreated(this)
            askAfterSave(stored)
        }
    }

    /**
     * After a save: the "Turn on reminders" prompt (Remote Config gated). Its button first asks
     * for notifications when they are off (13+), then opens the reminders setting. When the
     * prompt does not show but notifications are off, ask for those anyway: nothing can be shown
     * without them.
     */
    private fun askAfterSave(item: MemoElement) {
        MemoConsent.prime(
            activity = this,
            returnTo = MemoListActivity::class.java,
            reminderTitle = item.title,
            reminderTime = MemoShaper.time(this, item.dateTime),
            onReady = { if (needsNotificationPermission()) askNotifications {} },
            onSettingsOpened = { startAccessPoll() },
            onManage = { proceed -> if (needsNotificationPermission()) askNotifications(proceed) else proceed() },
        )
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun askNotifications(then: () -> Unit) {
        afterNotificationAsk = then
        runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure { afterNotificationAsk = null; then() }
    }

    /** The primary return path: Settings sits in this task, so this Activity may bring itself back. */
    private fun startAccessPoll() {
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

    private fun confirmDelete(item: MemoElement) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = DialogReminderDeleteBinding.inflate(layoutInflater)
        dialog.setContentView(view.root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        view.tvDeleteBody.text = getString(R.string.rem_delete_body, item.title)
        view.btnCancel.setOnClickListener { dialog.dismiss() }
        view.btnConfirmDelete.setOnClickListener {
            dialog.dismiss()
            MemoTimer.delete(this, item.id)
            logKeyEvent("Reminder_Delete")
            render()
        }
        dialog.show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: android.content.Context) = Intent(context, MemoListActivity::class.java)
    }
}
