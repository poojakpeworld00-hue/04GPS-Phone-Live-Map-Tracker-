package com.find.my.phone.gps.location.tracker.reminder.access

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.reminder.PromptGate
import com.find.my.phone.gps.location.tracker.reminder.ReminderConfig

/** Why [ReminderAccess.prime] did not show its prompt. */
enum class PromptSkipReason {
    /** `is_feature_prompt` off, or the country is excluded. */
    DISABLED,
    /** Already allowed, or the device has nothing to allow. */
    ALREADY_GRANTED,
    /** `prompt_frequency` held it back this time. */
    FREQUENCY_SUPPRESSED,
}

/**
 * "Reminder access": whether a due reminder may fill the screen while the phone is locked.
 *
 * The ONLY file that names the platform pieces behind it (the Android 14+ per-app setting, its
 * Settings page, the grant check and the notification builder call). Everything else, and every
 * word the user sees, speaks of reminders.
 */
object ReminderAccess {

    private const val ACTION_MANAGE = "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT"

    /** True while the user is on the Settings page this object opened. */
    private var armed = false

    private fun manageIntent(c: Context) = Intent(ACTION_MANAGE, Uri.parse("package:" + c.packageName))

    /** True when this device has the per-app Settings page for it. */
    fun alertAccessAvailable(c: Context): Boolean =
        runCatching { manageIntent(c).resolveActivity(c.packageManager) != null }.getOrDefault(false)

    /** Android 14+ grant state; below 34 there is nothing to grant (reported false here). */
    fun isGranted(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.canUseFullScreenIntent()
        }
        return false
    }

    /** Something to switch on: ungranted on 14+, or a Settings page exists below that. */
    fun shouldOfferManage(c: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 34) !isGranted(c) else alertAccessAvailable(c)

    /** Usable: granted on 14+, allowed by default on 13 and lower. Gate features on this. */
    fun isAllowed(c: Context): Boolean = !shouldOfferManage(c)

    /** The one place the project asks for the full-screen presentation. */
    fun attachAlertIntent(builder: NotificationCompat.Builder, target: PendingIntent) {
        builder.setFullScreenIntent(target, true)
    }

    /**
     * Opens the Settings page. From an Activity it opens IN-TASK (no NEW_TASK): while the page
     * sits on top of our own task, Android 15+ still lets that Activity bring itself back with
     * [returnIntent] once the switch is on. A new task would make the return a blocked background
     * launch. NO_HISTORY finishes the page as soon as we come back over it, so it never lingers.
     * The return must not trigger an App Open ad.
     */
    fun openSettings(c: Context) {
        AppOpenAdRegistry.skipNextAppOpenAd = true
        c.logKeyEvent("Reminder_Access_Settings")
        val flags = Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
            (if (c is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { c.startActivity(manageIntent(c).addFlags(flags)) }
    }

    /**
     * Brings [host] back over the Settings page. CLEAR_TOP finishes everything stacked above the
     * existing [host] in our task (the Settings pages, which NO_HISTORY alone does not remove: the
     * page we open hands over to an inner one) and SINGLE_TOP reuses that instance instead of
     * recreating it. Never CLEAR_TASK: if the system refuses the start, nothing of ours is lost.
     */
    fun returnIntent(c: Context, host: Class<out Activity>): Intent =
        Intent(c, host).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun shouldShowHint(reason: PromptSkipReason): Boolean = reason == PromptSkipReason.FREQUENCY_SUPPRESSED

    /**
     * The "Turn on reminders" prompt, in one call. Three gates, in order: `is_feature_prompt`
     * (and the country filter), something to allow, `prompt_frequency`. Skipped → [onReady] with
     * the reason. Shown → the button runs [onManage] (if given, e.g. to ask for notifications
     * first) and then opens Settings, starts [ReminderAccessWatcher] and fires [onSettingsOpened]
     * so the host can poll too. [onClosed] fires when the prompt goes away.
     *
     * [reminderTitle] / [reminderTime] fill `{title}` / `{time}` in `feature_desc`; without them
     * a description that uses placeholders falls back to the generic one.
     */
    fun prime(
        activity: Activity,
        returnTo: Class<out Activity>,
        reminderTitle: String? = null,
        reminderTime: String? = null,
        onReady: ((PromptSkipReason) -> Unit)? = null,
        onSettingsOpened: (() -> Unit)? = null,
        onClosed: (() -> Unit)? = null,
        onManage: ((proceed: () -> Unit) -> Unit)? = null,
    ) {
        val cfg = ReminderConfig.get(activity)
        if (!cfg.isFeaturePrompt || !ReminderConfig.isCountryAllowed(activity)) {
            onReady?.invoke(PromptSkipReason.DISABLED); return
        }
        if (!shouldOfferManage(activity)) { onReady?.invoke(PromptSkipReason.ALREADY_GRANTED); return }
        if (!PromptGate.shouldShow(activity)) { onReady?.invoke(PromptSkipReason.FREQUENCY_SUPPRESSED); return }

        val title = cfg.featureTitle.ifBlank { activity.getString(R.string.rem_prompt_title) }
        val desc = describe(activity, cfg.featureDesc, reminderTitle, reminderTime)
        val button = cfg.featureAction.ifBlank { activity.getString(R.string.rem_prompt_action) }

        PromptGate.markShown(activity)
        activity.logKeyEvent("Reminder_Prompt_Show")
        ReminderPromptDialog.show(
            activity, title, desc, button,
            onDismiss = { onClosed?.invoke() },
            onButton = {
                activity.logKeyEvent("Reminder_Prompt_Allow")
                val open: () -> Unit = {
                    openSettings(activity)
                    ReminderAccessWatcher.start(activity, returnTo)
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

    /** Host onResume: stops the watcher and closes the prompt once the user is back. */
    fun onHostResume(activity: Activity) {
        if (!armed) return
        armed = false
        ReminderAccessWatcher.stop(activity)
        ReminderPromptDialog.dismissIfShowing()
    }
}
