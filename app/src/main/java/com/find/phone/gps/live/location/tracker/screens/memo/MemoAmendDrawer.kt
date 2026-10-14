package com.find.phone.gps.live.location.tracker.screens.memo

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
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.BsheetReminderEditBinding
import com.find.phone.gps.live.location.tracker.memo.MemoElement
import com.find.phone.gps.live.location.tracker.memo.Repeat
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.Calendar

/**
 * New / edit reminder (reminders design 3). Title and time are required; number and note are
 * optional. "When" chips set the time in one tap; Date / Time open the system pickers. [onSave]
 * gets the finished reminder (new id for a new one); [onDelete] only exists when editing.
 */
class MemoAmendDrawer(
    private val activity: Activity,
    private val existing: MemoElement?,
    private val onPickContact: (onNumber: (String) -> Unit) -> Unit,
    private val onSave: (item: MemoElement, isNew: Boolean) -> Unit,
    private val onDelete: (MemoElement) -> Unit,
) {
    private val themed = ContextThemeWrapper(activity, R.style.Theme_GpsTracker)
    private val binding = BsheetReminderEditBinding.inflate(LayoutInflater.from(themed))
    private val dialog = BottomSheetDialog(themed, R.style.Ds_BottomSheetDialog)

    private val pick = Calendar.getInstance()
    private var repeat = Repeat.ONCE

    fun show() = with(binding) {
        if (existing != null) {
            txtSheetTitle.setText(R.string.rem_edit_edit)
            actDelete.isVisible = true
            inputTitle.setText(existing.title)
            inputNumber.setText(existing.number)
            inputNote.setText(existing.note)
            // A done or past one-off comes back as "in an hour" rather than a time already gone.
            val at = if (existing.dateTime > System.currentTimeMillis()) existing.dateTime else existing.baseTime
            pick.timeInMillis = at
            repeat = existing.repeatOrOnce
            if (at <= System.currentTimeMillis() && repeat == Repeat.ONCE) highlightChip(pillHour)
        } else {
            highlightChip(pillHour)
        }

        pillHour.setOnClickListener { highlightChip(pillHour) }
        pillTonight.setOnClickListener { highlightChip(pillTonight) }
        pillTomorrow.setOnClickListener { highlightChip(pillTomorrow) }
        pillCustom.setOnClickListener { highlightChip(pillCustom); chooseDate(thenTime = true) }
        lvFieldDate.setOnClickListener { highlightChip(pillCustom); chooseDate(thenTime = false) }
        lvFieldTime.setOnClickListener { highlightChip(pillCustom); chooseTime() }
        partOnce.setOnClickListener { setRepeat(Repeat.ONCE) }
        partDaily.setOnClickListener { setRepeat(Repeat.DAILY) }
        partWeekdays.setOnClickListener { setRepeat(Repeat.WEEKDAYS) }
        actPickContact.setOnClickListener { onPickContact { number -> inputNumber.setText(number) } }
        actDelete.setOnClickListener { existing?.let { dialog.dismiss(); onDelete(it) } }
        CraftMovement.pressScale(actSave)
        actSave.setOnClickListener { save() }

        setRepeat(repeat)
        paintWhen()

        dialog.setContentView(root)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
        if (existing == null) inputTitle.requestFocus()
    }

    private fun highlightChip(view: TextView) {
        val now = Calendar.getInstance()
        when (view.id) {
            R.id.pillHour -> pick.timeInMillis = roundUp(now.timeInMillis + 60 * 60_000L)
            R.id.pillTonight -> pick.atHour(now, 20, if (now.get(Calendar.HOUR_OF_DAY) >= 20) 1 else 0)
            R.id.pillTomorrow -> pick.atHour(now, 9, 1)
            else -> Unit // Custom keeps the current pick until the pickers change it
        }
        listOf(binding.pillHour, binding.pillTonight, binding.pillTomorrow, binding.pillCustom).forEach {
            val on = it === view
            it.setBackgroundResource(if (on) R.drawable.lv_reminder_chip_selected else R.drawable.lv_reminder_chip_tint)
            it.setTextColor(ContextCompat.getColor(themed, if (on) R.color.white else R.color.ds_primary))
        }
        paintWhen()
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

    private fun chooseDate(thenTime: Boolean) {
        DatePickerDialog(themed, { _, y, m, d ->
            pick.set(y, m, d)
            paintWhen()
            if (thenTime) chooseTime()
        }, pick.get(Calendar.YEAR), pick.get(Calendar.MONTH), pick.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis() - 1000
        }.show()
    }

    private fun chooseTime() {
        TimePickerDialog(themed, { _, h, min ->
            pick.set(Calendar.HOUR_OF_DAY, h); pick.set(Calendar.MINUTE, min)
            pick.set(Calendar.SECOND, 0); pick.set(Calendar.MILLISECOND, 0)
            paintWhen()
        }, pick.get(Calendar.HOUR_OF_DAY), pick.get(Calendar.MINUTE), DateFormat.is24HourFormat(themed)).show()
    }

    private fun setRepeat(value: String) {
        repeat = value
        val segs = mapOf(Repeat.ONCE to binding.partOnce, Repeat.DAILY to binding.partDaily, Repeat.WEEKDAYS to binding.partWeekdays)
        segs.forEach { (key, view) ->
            val on = key == value
            view.background = if (on) ContextCompat.getDrawable(themed, R.drawable.lv_reminder_seg_on) else null
            view.setTextColor(ContextCompat.getColor(themed, if (on) R.color.ds_primary else R.color.ds_muted))
            view.typeface = ResourcesCompat.getFont(themed, if (on) R.font.figtree_bold else R.font.figtree_semibold)
        }
    }

    private fun paintWhen() {
        binding.txtDate.text = MemoShaper.day(themed, pick.timeInMillis)
        binding.txtTime.text = MemoShaper.time(themed, pick.timeInMillis)
        binding.txtError.isVisible = false
    }

    private fun save() {
        val title = binding.inputTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) return presentError(R.string.rem_err_title)
        val at = pick.timeInMillis
        // A one-off must be in the future; a repeating one just starts at its next time.
        if (repeat == Repeat.ONCE && at <= System.currentTimeMillis() + 30_000L) return presentError(R.string.rem_err_past)

        val item = (existing ?: MemoElement(title = title, dateTime = at)).copy(
            title = title,
            number = binding.inputNumber.text?.toString()?.trim().orEmpty(),
            note = binding.inputNote.text?.toString()?.trim().orEmpty(),
            dateTime = at,
            baseTime = at,
            repeat = repeat,
            enabled = true,
            done = false,
        )
        dialog.dismiss()
        onSave(item, existing == null)
    }

    private fun presentError(res: Int) {
        binding.txtError.setText(res)
        binding.txtError.isVisible = true
        binding.txtError.announceForAccessibility(binding.txtError.text)
    }
}
