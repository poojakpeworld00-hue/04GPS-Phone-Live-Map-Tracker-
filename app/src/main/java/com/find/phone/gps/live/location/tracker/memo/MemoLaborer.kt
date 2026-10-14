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
 * Fires one reminder. Posts a high-importance notification whose full-screen target is
 * [MemoSirenActivity]; that screen cancels the notification once it is up.
 *
 * Product decision: reminders only show while the phone is locked. In use, the full-screen
 * target would only surface as a heads-up banner, so the reminder is not shown at all. Either way
 * the reminder moves on: a one-off is marked done, a repeating one is scheduled for its next time.
 */
class MemoLaborer(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val id = inputData.getLong(KEY_ID, -1L)
        val item = MemoShelf.get(ctx, id)?.takeIf { it.isActive } ?: return Result.success()

        advance(ctx, item)

        if (isDeviceInUse(ctx)) {
            WardenRail.log(TAG, "reminder $id due while the phone is in use → not shown")
            return Result.success()
        }
        notify(ctx, item)
        return Result.success()
    }

    /** One-off → done; repeating → its next time. */
    private fun advance(ctx: Context, item: MemoElement) {
        val next = Repeat.nextAfter(item.repeatOrOnce, item.baseTime, System.currentTimeMillis())
        if (next == null) MemoShelf.upsert(ctx, item.copy(done = true))
        else MemoTimer.save(ctx, item.copy(dateTime = next))
    }

    private fun isDeviceInUse(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isInteractive && !km.isKeyguardLocked
    }

    private fun notify(ctx: Context, item: MemoElement) {
        val nmc = NotificationManagerCompat.from(ctx)
        if (!nmc.areNotificationsEnabled()) {
            WardenRail.log(TAG, "notifications are off → reminder ${item.id} cannot be shown")
            return
        }
        ensureChannel(ctx)

        // `is_enhanced_mode` off (or the country is excluded): a plain notification into the list.
        val fullScreen = MemoProfile.get(ctx).isEnhancedMode && MemoProfile.isCountryAllowed(ctx)
        val notifId = item.id.hashCode()
        val target = if (fullScreen) MemoSirenActivity.intent(ctx, item.id, notifId)
        else Intent(ctx, MemoListActivity::class.java)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            ctx, notifId, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = item.note.ifBlank { item.number }.ifBlank { ctx.getString(R.string.rem_notification_text) }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_stat_reminder)
            .setColor(ContextCompat.getColor(ctx, R.color.ds_primary))
            .setContentTitle(item.title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setAutoCancel(true)
        if (fullScreen) MemoConsent.attachAlertIntent(builder, pending)

        runCatching { nmc.notify(notifId, builder.build()) }
            .onSuccess {
                WardenRail.log(TAG, "reminder ${item.id} \"${item.title}\" notified (full screen=$fullScreen, notif=$notifId)")
            }
            .onFailure { WardenRail.error(TAG, "notify failed", it) }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, ctx.getString(R.string.rem_channel_name), NotificationManager.IMPORTANCE_HIGH)
                .apply { description = ctx.getString(R.string.rem_channel_desc) }
        )
    }

    companion object {
        const val KEY_ID = "id"
        private const val CHANNEL_ID = "reminders"
        private const val TAG = "MemoLaborer"
    }
}
