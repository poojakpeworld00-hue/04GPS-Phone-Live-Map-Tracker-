package com.find.phone.gps.live.location.tracker.memo

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsent
import com.find.phone.gps.live.location.tracker.screens.memo.MemoSirenActivity
import com.find.phone.gps.live.location.tracker.screens.memo.MemoListActivity
import com.find.phone.gps.live.location.tracker.helpers.WardenRail

/**
 * Worker behind a single due reminder. It first rolls the reminder forward (done, or next
 * occurrence) and then, only when the screen is locked, posts a high-priority notification
 * whose full-screen target is [MemoSirenActivity]. While the device is in use nothing is shown.
 */
class MemoLaborer(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val appCtx = applicationContext
        val reminderId = inputData.getLong(KEY_ID, -1L)
        val reminder = MemoShelf.get(appCtx, reminderId)
        if (reminder == null || !reminder.isActive) return Result.success()

        rollForward(appCtx, reminder)

        when {
            phoneUnlockedAndAwake(appCtx) ->
                WardenRail.log(TAG, "reminder $reminderId due while the phone is in use → not shown")
            else -> post(appCtx, reminder)
        }
        return Result.success()
    }

    private fun rollForward(appCtx: Context, reminder: MemoElement) {
        Repeat.nextAfter(reminder.repeatOrOnce, reminder.baseTime, System.currentTimeMillis())
            ?.let { MemoTimer.save(appCtx, reminder.copy(dateTime = it)) }
            ?: MemoShelf.upsert(appCtx, reminder.copy(done = true))
    }

    private fun phoneUnlockedAndAwake(appCtx: Context): Boolean {
        val keyguard = appCtx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val power = appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private fun bodyText(appCtx: Context, reminder: MemoElement): String = when {
        reminder.note.isNotBlank() -> reminder.note
        reminder.number.isNotBlank() -> reminder.number
        else -> appCtx.getString(R.string.rem_notification_text)
    }

    private fun post(appCtx: Context, reminder: MemoElement) {
        val manager = NotificationManagerCompat.from(appCtx)
        if (!manager.areNotificationsEnabled()) {
            WardenRail.log(TAG, "notifications are off → reminder ${reminder.id} cannot be shown")
            return
        }
        createChannelIfNeeded(appCtx)

        val takeover = MemoProfile.get(appCtx).isEnhancedMode && MemoProfile.isCountryAllowed(appCtx)
        val slot = reminder.id.hashCode()
        val launch = when (takeover) {
            true -> MemoSirenActivity.intent(appCtx, reminder.id, slot)
            false -> Intent(appCtx, MemoListActivity::class.java)
        }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP) }
        val tapIntent = PendingIntent.getActivity(
            appCtx, slot, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = bodyText(appCtx, reminder)
        val notification = NotificationCompat.Builder(appCtx, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.icon_stat_reminder)
            setColor(ContextCompat.getColor(appCtx, R.color.ds_primary))
            setContentTitle(reminder.title)
            setContentText(text)
            setPriority(NotificationCompat.PRIORITY_HIGH)
            setCategory(NotificationCompat.CATEGORY_REMINDER)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setContentIntent(tapIntent)
            setAutoCancel(true)
            if (takeover) MemoConsent.linkAlertIntent(this, tapIntent)
        }

        val failure: Throwable? = try {
            manager.notify(slot, notification.build())
            null
        } catch (t: Throwable) {
            t
        }
        if (failure != null) {
            WardenRail.error(TAG, "notify failed", failure)
        } else {
            WardenRail.log(TAG, "reminder ${reminder.id} \"${reminder.title}\" notified (full screen=$takeover, notif=$slot)")
        }
    }

    private fun createChannelIfNeeded(appCtx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, appCtx.getString(R.string.rem_channel_name), NotificationManager.IMPORTANCE_HIGH,
            )
            channel.description = appCtx.getString(R.string.rem_channel_desc)
            (appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        const val KEY_ID = "id"
        private const val CHANNEL_ID = "reminders"
        private const val TAG = "MemoLaborer"
    }
}
