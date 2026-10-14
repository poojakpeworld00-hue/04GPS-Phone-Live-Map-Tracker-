package com.find.phone.gps.live.location.tracker.memo.grant

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.memo.CuePortal
import com.find.phone.gps.live.location.tracker.memo.MemoProfile

enum class CuePassMotive {
    DISABLED,

    ALREADY_GRANTED,

    FREQUENCY_SUPPRESSED,
}

object MemoConsent {
    private const val ACTION_MANAGE = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

    private var armed = false

    private fun manageIntent(c: Context) = Intent(ACTION_MANAGE, Uri.parse("package:" + c.packageName))

    fun alertAccessAvailable(c: Context): Boolean =
        runCatching { manageIntent(c).resolveActivity(c.packageManager) != null }.getOrDefault(false)

    fun isGranted(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.canUseFullScreenIntent()
        }
        return false
    }

    fun mustOfferManage(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) !isGranted(c) else alertAccessAvailable(c)

    fun isAllowed(c: Context): Boolean = !mustOfferManage(c)

    fun linkAlertIntent(builder: NotificationCompat.Builder, target: PendingIntent) {
        builder.setFullScreenIntent(target, true)
    }

    fun enterSettings(c: Context) {
        CoreOpenSpotBook.skipNextAppOpenAd = true
        c.recordKeyEvent("Reminder_Access_Settings")
        val flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            (if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(manageIntent(c).addFlags(flags)) }
    }

    fun returnIntent(c: Context, host: Class<out Activity>): Intent =
        Intent(c, host).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun mustShowHint(reason: CuePassMotive): Boolean = reason == CuePassMotive.FREQUENCY_SUPPRESSED

    fun prime(
        activity: Activity,
        returnTo: Class<out Activity>,
        reminderTitle: String? = null,
        reminderTime: String? = null,
        onReady: ((CuePassMotive) -> Unit)? = null,
        onSettingsOpened: (() -> Unit)? = null,
        onClosed: (() -> Unit)? = null,
        onManage: ((proceed: () -> Unit) -> Unit)? = null,
    ) {
        val cfg = MemoProfile.get(activity)
        if (!cfg.isFeaturePrompt || !MemoProfile.isCountryAllowed(activity)) {
            onReady?.invoke(CuePassMotive.DISABLED); return
        }
        if (!mustOfferManage(activity)) { onReady?.invoke(CuePassMotive.ALREADY_GRANTED); return }
        if (!CuePortal.mustShow(activity)) { onReady?.invoke(CuePassMotive.FREQUENCY_SUPPRESSED); return }

        val title = cfg.featureTitle.ifBlank { activity.getString(R.string.rem_prompt_title) }
        val desc = describe(activity, cfg.featureDesc, reminderTitle, reminderTime)
        val button = cfg.featureAction.ifBlank { activity.getString(R.string.rem_prompt_action) }

        CuePortal.flagShown(activity)
        activity.recordKeyEvent("Reminder_Prompt_Show")
        MemoCueModal.show(
            activity, title, desc, button,
            onDismiss = { onClosed?.invoke() },
            onButton = {
                activity.recordKeyEvent("Reminder_Prompt_Allow")
                val open: () -> Unit = {
                    enterSettings(activity)
                    MemoConsentSentinel.start(activity, returnTo)
                    armed = true
                    onSettingsOpened?.invoke()
                }
                if (onManage != null) onManage(open) else open()
            },
        )
    }

    private fun describe(c: Context, remote: String, title: String?, time: String?): String {
        val generic = c.getString(R.string.rem_prompt_desc)
        if (remote.isBlank()) return generic
        val usesPlaceholders = "{title}" in remote || "{time}" in remote
        if (usesPlaceholders && (title.isNullOrBlank() || time.isNullOrBlank())) return generic
        return remote.replace("{title}", title.orEmpty()).replace("{time}", time.orEmpty())
    }

    fun onHostResume(activity: Activity) {
        if (!armed) return
        armed = false
        MemoConsentSentinel.stop(activity)
        MemoCueModal.retireIfShowing()
    }
}
