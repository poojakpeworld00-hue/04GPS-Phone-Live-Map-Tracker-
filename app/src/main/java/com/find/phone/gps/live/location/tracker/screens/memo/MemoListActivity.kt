package com.find.phone.gps.live.location.tracker.screens.memo

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
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

class MemoListActivity : StageActivity<ScreenReminderListBinding>() {
    override val layoutId: Int = R.layout.screen_reminder_list

    private var onNumberPicked: ((String) -> Unit)? = null
    private var postNotifAsk: (() -> Unit)? = null
    private var grantWatchJob: Job? = null

    private val buddyPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val picked = res.data?.getStringExtra(BuddyChooserActivity.EXTRA_NUMBER)
        if (picked != null) onNumberPicked?.invoke(picked)
        onNumberPicked = null
    }

    private val notifAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        val follow = postNotifAsk.also { postNotifAsk = null }
        window.decorView.post { follow?.invoke() }
    }

    override fun initView() {
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isNightMode(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.lvRemindersRoot) { root, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            root.updatePadding(top = sys.top, bottom = sys.bottom)
            insets
        }
        binding.actBack.setOnClickListener { goBack() }
        binding.actNewReminder.let { addBtn ->
            CraftMovement.pressScale(addBtn)
            addBtn.setOnClickListener { openEditor(null) }
        }
        recordKeyEvent("Reminders_Open")

        EmbeddedTeaser().presentMidNative(this, binding.spotNativeFrame, binding.spotShimmer)
        binding.spotNativeDivider.followAdContainer(binding.spotNativeFrame)
    }

    override fun onResume() {
        super.onResume()
        grantWatchJob?.cancel()
        MemoConsent.onHostResume(this)
        MemoShelf.plantDefaultsIfNeeded(this)
        rebuildList()
    }

    override fun onDestroy() {
        grantWatchJob?.cancel()
        MemoCueModal.retireIfShowing()
        super.onDestroy()
    }

    private fun rebuildList() {
        val list = binding.listReminders
        list.removeAllViews()
        val everything = MemoShelf.load(this)
        val (finished, pending) = everything.partition { it.done }
        val upcomingSorted = pending.sortedBy { it.dateTime }
        val finishedSorted = finished.sortedByDescending { it.dateTime }

        binding.blankState.isVisible = everything.isEmpty()
        appendGroup(R.string.rem_upcoming, upcomingSorted)
        appendGroup(R.string.rem_done_section, finishedSorted)
    }

    private fun appendGroup(headerRes: Int, entries: List<MemoElement>) {
        if (entries.isEmpty()) return
        val list = binding.listReminders
        list.addView(groupHeader(headerRes))
        entries.forEach { list.addView(buildRow(it)) }
    }

    private fun groupHeader(textRes: Int): TextView = TextView(this).apply {
        setText(textRes)
        isAllCaps = true
        letterSpacing = 0.08f
        textSize = 11f
        setTextColor(ContextCompat.getColor(context, R.color.ds_muted))
        typeface = ResourcesCompat.getFont(context, R.font.figtree_bold)
        layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(px(4), px(10), 0, px(8)) }
    }

    private fun buildRow(entry: MemoElement): View {
        val row = CellReminderBinding.inflate(LayoutInflater.from(this), binding.listReminders, false)
        val ctx = this
        with(row) {
            txtTitle.text = entry.title
            txtWhen.text = MemoShaper.dayAndTime(ctx, entry.dateTime)
            txtNumber.text = entry.number
            txtNumber.isVisible = entry.number.isNotBlank()
            txtRepeat.text = MemoShaper.repeatLabel(ctx, entry.repeatOrOnce)

            val isCallBack = entry.number.isNotBlank()
            badgeTile.setBackgroundResource(if (isCallBack) R.drawable.lv_tile_purple_r12 else R.drawable.lv_tile_tint_r12)
            badgeVw.setImageResource(if (isCallBack) R.drawable.lv_ic_call else R.drawable.pmt_ic_bell)
            ImageViewCompat.setImageTintList(
                badgeVw, ContextCompat.getColorStateList(ctx, if (isCallBack) R.color.ds_purple else R.color.ds_primary),
            )

            elemReminder.alpha = if (entry.done) 0.6f else 1f
            val flags = txtTitle.paintFlags
            txtTitle.paintFlags = when {
                entry.done -> flags or Paint.STRIKE_THRU_TEXT_FLAG
                else -> flags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            lvSwEnabled.setOnCheckedChangeListener(null)
            lvSwEnabled.isChecked = entry.isActive
            lvSwEnabled.setOnCheckedChangeListener { _, isOn -> handleSwitch(entry, isOn) }

            elemReminder.setOnClickListener { openEditor(entry) }
            elemReminder.setOnLongClickListener {
                confirmRemoval(entry)
                true
            }
        }
        return row.root
    }

    private fun handleSwitch(entry: MemoElement, isOn: Boolean) {
        val expiredOneOff = entry.repeatOrOnce == Repeat.ONCE && entry.dateTime <= System.currentTimeMillis()
        if (isOn && expiredOneOff) {
            rebuildList()
            openEditor(entry)
            return
        }
        MemoTimer.save(this, entry.copy(enabled = isOn, done = false))
        recordKeyEvent(if (isOn) "Reminder_Enable" else "Reminder_Disable")
        rebuildList()
    }

    private fun openEditor(entry: MemoElement?) {
        MemoAmendDrawer(
            activity = this,
            existing = entry,
            onPickContact = { callback ->
                onNumberPicked = callback
                buddyPicker.launch(BuddyChooserActivity.intent(this))
            },
            onSave = { saved, isNew -> persistEdit(saved, isNew) },
            onDelete = { confirmRemoval(it) },
        ).show()
    }

    private fun persistEdit(entry: MemoElement, created: Boolean) {
        MemoTimer.save(this, entry)
        rebuildList()
        val fresh = MemoShelf.get(this, entry.id) ?: entry
        val whenText = MemoShaper.dayAndTime(this, fresh.dateTime)
        Toast.makeText(this, getString(R.string.rem_saved, whenText), Toast.LENGTH_SHORT).show()
        recordKeyEvent(if (created) "Reminder_Create" else "Reminder_Edit")
        if (!created) return
        CuePortal.onReminderCreated(this)
        promptAfterFirstSave(fresh)
    }

    private fun promptAfterFirstSave(entry: MemoElement) {
        MemoConsent.prime(
            activity = this,
            returnTo = MemoListActivity::class.java,
            reminderTitle = entry.title,
            reminderTime = MemoShaper.time(this, entry.dateTime),
            onReady = { if (notificationsBlocked()) askNotifications {} },
            onSettingsOpened = { watchForGrant() },
            onManage = { proceed ->
                when {
                    notificationsBlocked() -> askNotifications(proceed)
                    else -> proceed()
                }
            },
        )
    }

    private fun notificationsBlocked(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return !NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun askNotifications(next: () -> Unit) {
        postNotifAsk = next
        runCatching { notifAsk.launch(Manifest.permission.POST_NOTIFICATIONS) }
            .onFailure {
                postNotifAsk = null
                next()
            }
    }

    private fun watchForGrant() {
        grantWatchJob?.cancel()
        grantWatchJob = lifecycleScope.launch {
            val host = this@MemoListActivity
            while (isActive) {
                if (!MemoConsent.isGranted(host)) {
                    delay(500L)
                    continue
                }
                runCatching { startActivity(MemoConsent.returnIntent(host, MemoListActivity::class.java)) }
                break
            }
        }
    }

    private fun confirmRemoval(entry: MemoElement) {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val content = DlgReminderDeleteBinding.inflate(layoutInflater)
        dlg.setContentView(content.root)
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((resources.displayMetrics.widthPixels * 0.88f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        content.txtDeleteBody.text = getString(R.string.rem_delete_body, entry.title)
        content.actCancel.setOnClickListener { dlg.dismiss() }
        content.actConfirmDelete.setOnClickListener {
            dlg.dismiss()
            MemoTimer.delete(this, entry.id)
            recordKeyEvent("Reminder_Delete")
            rebuildList()
        }
        dlg.show()
    }

    private fun px(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun intent(context: Context) = Intent(context, MemoListActivity::class.java)
    }
}
