package com.find.phone.gps.live.location.tracker.memo

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Maps each stored reminder to a unique WorkManager job named `reminder_<id>` (REPLACE policy).
 * Only the id travels with the job; the worker reloads the reminder when it runs.
 */
object MemoTimer {

    private const val TAG = "reminder"
    private const val MINUTE_MS = 60_000L

    private fun uniqueName(id: Long) = "reminder_$id"

    private fun work(context: Context) = WorkManager.getInstance(context)

    /** Picks the fire time: the stored one if still ahead, else the next repeat, else null. */
    private fun fireTime(item: MemoElement, now: Long): Long? =
        item.dateTime.takeIf { it > now } ?: Repeat.nextAfter(item.repeatOrOnce, item.baseTime, now)

    fun sync(context: Context, item: MemoElement) {
        if (!item.isActive) {
            cancel(context, item.id)
            return
        }
        val now = System.currentTimeMillis()
        val target = fireTime(item, now)
        if (target == null) {
            cancel(context, item.id)
            return
        }
        if (target != item.dateTime) MemoShelf.upsert(context, item.copy(dateTime = target))

        val job = OneTimeWorkRequestBuilder<MemoLaborer>()
            .setInitialDelay(target - now, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(MemoLaborer.KEY_ID to item.id))
            .addTag(TAG)
            .build()
        work(context).enqueueUniqueWork(uniqueName(item.id), ExistingWorkPolicy.REPLACE, job)
    }

    fun cancel(context: Context, id: Long) {
        work(context).cancelUniqueWork(uniqueName(id))
    }

    fun save(context: Context, item: MemoElement) {
        MemoShelf.upsert(context, item)
        sync(context, item)
    }

    fun delete(context: Context, id: Long) {
        cancel(context, id)
        MemoShelf.remove(context, id)
    }

    fun snooze(context: Context, id: Long, minutes: Int) {
        MemoShelf.get(context, id)?.let { stored ->
            val wakeAt = System.currentTimeMillis() + minutes * MINUTE_MS
            save(context, stored.copy(dateTime = wakeAt, done = false, enabled = true))
        }
    }

    fun flagDone(context: Context, id: Long) {
        val stored = MemoShelf.get(context, id) ?: return
        when (val upcoming = Repeat.nextAfter(stored.repeatOrOnce, stored.baseTime, System.currentTimeMillis())) {
            null -> {
                cancel(context, id)
                MemoShelf.upsert(context, stored.copy(done = true))
            }
            else -> save(context, stored.copy(dateTime = upcoming))
        }
    }
}
