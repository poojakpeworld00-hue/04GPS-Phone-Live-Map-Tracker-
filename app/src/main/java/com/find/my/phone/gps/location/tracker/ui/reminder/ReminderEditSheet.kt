package com.find.my.phone.gps.location.tracker.ui.reminder

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.databinding.SheetReminderEditBinding
import com.find.my.phone.gps.location.tracker.reminder.ReminderItem
import com.find.my.phone.gps.location.tracker.reminder.Repeat
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Calendar

/**
 * New / edit reminder (reminders design 3). Title and time are required; number and note are
 * optional. "When" chips set the time in one tap; Date / Time open the system pickers. [onSave]
 * gets the finished reminder (new id for a new one); [onDelete] only exists when editing.
 */
class ReminderEditSheet(
    private val activity: Activity,
    private val existing: ReminderItem?,
    private val onPickContact: (onNumber: (String) -> Unit) -> Unit,
    private val onSave: (item: ReminderItem, isNew: Boolean) -> Unit,
    private val onDelete: (ReminderItem) -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = SheetReminderEditBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)

    private val pick = Calendar.getInstance()
    private var repeat = Repeat.ONCE

    fun show() = with(binding) {
        if (existing != null) {
            tvSheetTitle.setText(R.string.rem_edit_edit)
            btnDelete.isVisible = true
            etTitle.setText(existing.title)
            etNumber.setText(existing.number)
            etNote.setText(existing.note)
            // A done or past one-off comes back as "in an hour" rather than a time already gone.
            val at = if (existing.dateTime > System.currentTimeMillis()) existing.dateTime else existing.baseTime
            pick.timeInMillis = at
            repeat = existing.repeatOrOnce
            if (at <= System.currentTimeMillis() && repeat == Repeat.ONCE) selectChip(chipHour)
        } else {
            selectChip(chipHour)
        }

        chipHour.setOnClickListener { selectChip(chipHour) }
        chipTonight.setOnClickListener { selectChip(chipTonight) }
        chipTomorrow.setOnClickListener { selectChip(chipTomorrow) }
        chipCustom.setOnClickListener { selectChip(chipCustom); pickDate(thenTime = true) }
        fieldDate.setOnClickListener { selectChip(chipCustom); pickDate(thenTime = false) }
        fieldTime.setOnClickListener { selectChip(chipCustom); pickTime() }
        segOnce.setOnClickListener { setRepeat(Repeat.ONCE) }
        segDaily.setOnClickListener { setRepeat(Repeat.DAILY) }
        segWeekdays.setOnClickListener { setRepeat(Repeat.WEEKDAYS) }
        btnPickContact.setOnClickListener { onPickContact { number -> etNumber.setText(number) } }
        btnDelete.setOnClickListener { existing?.let { dialog.dismiss(); onDelete(it) } }
        DesignMotion.pressScale(btnSave)
        btnSave.setOnClickListener { save() }

        setRepeat(repeat)
        renderWhen()

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        if (existing == null) etTitle.requestFocus()
    }

    private fun selectChip(view: TextView) {
        val now = Calendar.getInstance()
        when (view.id) {
            R.id.chipHour -> pick.timeInMillis = roundUp(now.timeInMillis + 60 * 60_000L)
            R.id.chipTonight -> pick.atHour(now, 20, if (now.get(Calendar.HOUR_OF_DAY) >= 20) 1 else 0)
            R.id.chipTomorrow -> pick.atHour(now, 9, 1)
            else -> Unit // Custom keeps the current pick until the pickers change it
        }
        listOf(binding.chipHour, binding.chipTonight, binding.chipTomorrow, binding.chipCustom).forEach {
            val on = it === view
            it.setBackgroundResource(if (on) R.drawable.ds_reminder_chip_selected else R.drawable.ds_reminder_chip_tint)
            it.setTextColor(ContextCompat.getColor(themed, if (on) R.color.white else R.color.ds_primary))
        }
        renderWhen()
    }

    private fun Calendar.atHour(from: Calendar, hour: Int, plusDays: Int) {
        timeInMillis = from.timeInMillis
        add(Calendar.DAY_OF_YEAR, plusDays)
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** Next 5-minute mark, so "in 1 hour" reads 7:35 rather than 7:32. */
    private fun roundUp(millis: Long): Long {
        val step = 5 * 60_000L
        return ((millis + step - 1) / step) * step
    }

    private fun pickDate(thenTime: Boolean) {
        DatePickerDialog(themed, { _, y, m, d ->
            pick.set(y, m, d)
            renderWhen()
            if (thenTime) pickTime()
        }, pick.get(Calendar.YEAR), pick.get(Calendar.MONTH), pick.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    private fun pickTime() {
        TimePickerDialog(themed, { _, h, min ->
            pick.set(Calendar.HOUR_OF_DAY, h); pick.set(Calendar.MINUTE, min)
            pick.set(Calendar.SECOND, 0); pick.set(Calendar.MILLISECOND, 0)
            renderWhen()
        }, pick.get(Calendar.HOUR_OF_DAY), pick.get(Calendar.MINUTE), DateFormat.is24HourFormat(themed)).show()
    }

    private fun setRepeat(value: String) {
        repeat = value
        val segs = mapOf(Repeat.ONCE to binding.segOnce, Repeat.DAILY to binding.segDaily, Repeat.WEEKDAYS to binding.segWeekdays)
        segs.forEach { (key, view) ->
            val on = key == value
            view.background = if (on) ContextCompat.getDrawable(themed, R.drawable.ds_reminder_seg_on) else null
            view.setTextColor(ContextCompat.getColor(themed, if (on) R.color.ds_primary else R.color.ds_muted))
            view.typeface = ResourcesCompat.getFont(themed, if (on) R.font.figtree_bold else R.font.figtree_semibold)
        }
    }

    private fun renderWhen() {
        binding.tvDate.text = ReminderFormat.day(themed, pick.timeInMillis)
        binding.tvTime.text = ReminderFormat.time(themed, pick.timeInMillis)
        binding.tvError.isVisible = false
    }

    private fun save() {
        val title = binding.etTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return showError(R.string.rem_err_title)
        val at = pick.timeInMillis
        // A one-off must be in the future; a repeating one just starts at its next time.
        if (repeat == Repeat.ONCE && at <= System.currentTimeMillis() + 30_000L) return showError(R.string.rem_err_past)

        val item = (existing ?: ReminderItem(title = title, dateTime = at)).copy(
            title = title,
            number = binding.etNumber.text?.toString()?.trim().orEmpty(),
            note = binding.etNote.text?.toString()?.trim().orEmpty(),
            dateTime = at,
            baseTime = at,
            repeat = repeat,
            enabled = true,
            done = false,
        )
        dialog.dismiss()
        onSave(item, existing == null)
    }

    private fun showError(res: Int) {
        binding.tvError.setText(res)
        binding.tvError.isVisible = true
        binding.tvError.announceForAccessibility(binding.tvError.text)
    }
}
