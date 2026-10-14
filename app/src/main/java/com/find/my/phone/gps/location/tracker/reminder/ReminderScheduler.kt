package com.find.my.phone.gps.location.tracker.reminder

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Turns the stored reminders into WorkManager jobs. One unique job per reminder,
 * `reminder_<id>`, replaced whenever the reminder changes; WorkManager keeps it across app kills
 * and reboots. The job carries only the id: the worker re-reads the reminder, so an edit made
 * after scheduling is what fires.
 */
object ReminderScheduler {

    private fun workName(id: Long) = "reminder_$id"

    /** Schedules [item] for its next time, or cancels it when it is off / done / in the past. */
    fun sync(context: Context, item: ReminderItem) {
        if (!item.isActive) return cancel(context, item.id)
        val now = System.currentTimeMillis()
        val at = if (item.dateTime > now) item.dateTime
        else Repeat.nextAfter(item.repeatOrOnce, item.baseTime, now) ?: return cancel(context, item.id)
        if (at != item.dateTime) ReminderStore.upsert(context, item.copy(dateTime = at))

        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(at - now, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_ID to item.id))
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(item.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context, id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    /** Saves and schedules in one step: what the edit sheet and the list switch call. */
    fun save(context: Context, item: ReminderItem) {
        ReminderStore.upsert(context, item)
        sync(context, item)
    }

    fun delete(context: Context, id: Long) {
        cancel(context, id)
        ReminderStore.remove(context, id)
    }

    /** Fires [id] again in [minutes]; a repeating reminder then goes back to its usual time. */
    fun snooze(context: Context, id: Long, minutes: Int) {
        val item = ReminderStore.get(context, id) ?: return
        save(context, item.copy(dateTime = System.currentTimeMillis() + minutes * 60_000L, done = false, enabled = true))
    }

    /** Done on the alert: a one-off is finished; a repeating one waits for its next time. */
    fun markDone(context: Context, id: Long) {
        val item = ReminderStore.get(context, id) ?: return
        val next = Repeat.nextAfter(item.repeatOrOnce, item.baseTime, System.currentTimeMillis())
        if (next == null) {
            cancel(context, id)
            ReminderStore.upsert(context, item.copy(done = true))
        } else {
            save(context, item.copy(dateTime = next))
        }
    }

    private const val TAG = "reminder"
}
