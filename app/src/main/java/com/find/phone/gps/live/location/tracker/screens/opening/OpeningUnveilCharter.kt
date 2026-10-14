package com.find.phone.gps.live.location.tracker.screens.opening

import android.content.Context
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.memo.MemoProfile
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsent

/**
 * Decides whether an intro screen (Language / Onboarding) shows this launch, from
 * its [OpeningUnveil] Remote Config policy plus the persisted ledger in
 * [LockerBook]. A screen calls [markShown] when it actually appears.
 *
 * Session = one cold start: [LockerBook.appLaunchCount] is bumped once per launch
 * (in Splash), so `app_launches` counts launches and [markShown] can de-dupe within
 * a single launch even if the gate is evaluated at several points in the funnel.
 */
object OpeningUnveilCharter {

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun shouldShow(context: Context, key: String, config: OpeningUnveil): Boolean {
        if (!config.enabled) return false
        val prefs = LockerBook(context)
        return when (config.frequency) {
            CueTempo.NEVER -> false
            CueTempo.ALWAYS -> true
            CueTempo.ONCE -> prefs.introShownCount(key) == 0
            CueTempo.EVERY_DAYS -> {
                val last = prefs.introLastShownMs(key)
                if (last == 0L) true
                else (System.currentTimeMillis() - last) / DAY_MS >= config.interval
            }
            // Show on every Nth launch (interval must be positive to be meaningful).
            CueTempo.APP_LAUNCHES ->
                config.interval > 0 && prefs.appLaunchCount % config.interval == 0
        }
    }

    /** Record that [key] was shown this launch (bumps count + stamps time, once per launch). */
    fun markShown(context: Context, key: String) {
        val prefs = LockerBook(context)
        val session = prefs.appLaunchCount
        if (prefs.introLastShownSession(key) == session) return
        prefs.recordIntroShown(key, session)
    }

    fun shouldShowLanguage(context: Context): Boolean =
        shouldShow(context, OpeningUnveilProfile.LANGUAGE, OpeningUnveilProfile.language(context))

    fun shouldShowTerms(context: Context): Boolean =
        shouldShow(context, OpeningUnveilProfile.TERMS, OpeningUnveilProfile.terms(context))

    fun shouldShowOnboarding(context: Context): Boolean =
        shouldShow(context, OpeningUnveilProfile.ONBOARDING, OpeningUnveilProfile.onboarding(context))

    /**
     * The reminders intro on later launches: `intro_display.reminders_intro` cadence, AND
     * feature_config's `is_intro_screen` + country filter, AND reminders still to be allowed.
     */
    fun shouldShowRemindersIntro(context: Context): Boolean =
        MemoProfile.get(context).isIntroScreen &&
            MemoConsent.shouldOfferManage(context) &&
            MemoProfile.isCountryAllowed(context) &&
            shouldShow(context, OpeningUnveilProfile.REMINDERS_INTRO, OpeningUnveilProfile.remindersIntro(context))

    /** Frequency gate only — callers still AND this with their own "is anything pending?" check. */
    fun shouldShowPermissionSheet(context: Context): Boolean =
        shouldShow(context, OpeningUnveilProfile.PERMISSION_SHEET, OpeningUnveilProfile.permissionSheet(context))
}
