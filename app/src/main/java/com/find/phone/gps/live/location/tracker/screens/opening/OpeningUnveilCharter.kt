package com.find.phone.gps.live.location.tracker.screens.opening

import android.content.Context
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.memo.MemoProfile
import com.find.phone.gps.live.location.tracker.memo.grant.MemoConsent

object OpeningUnveilCharter {
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    fun mustShow(context: Context, key: String, config: OpeningUnveil): Boolean {
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

            CueTempo.APP_LAUNCHES ->
                config.interval > 0 && prefs.appLaunchCount % config.interval == 0
        }
    }

    fun flagShown(context: Context, key: String) {
        val prefs = LockerBook(context)
        val session = prefs.appLaunchCount
        if (prefs.introLastShownSession(key) == session) return
        prefs.noteIntroShown(key, session)
    }

    fun mustShowLanguage(context: Context): Boolean =
        mustShow(context, OpeningUnveilProfile.LANGUAGE, OpeningUnveilProfile.language(context))

    fun mustShowTerms(context: Context): Boolean =
        mustShow(context, OpeningUnveilProfile.TERMS, OpeningUnveilProfile.terms(context))

    fun mustShowOnboarding(context: Context): Boolean =
        mustShow(context, OpeningUnveilProfile.ONBOARDING, OpeningUnveilProfile.onboarding(context))

    fun mustShowRemindersIntro(context: Context): Boolean =
        MemoProfile.get(context).isIntroScreen &&
            MemoConsent.mustOfferManage(context) &&
            MemoProfile.isCountryAllowed(context) &&
            mustShow(context, OpeningUnveilProfile.REMINDERS_INTRO, OpeningUnveilProfile.remindersIntro(context))

    fun mustShowPermissionSheet(context: Context): Boolean =
        mustShow(context, OpeningUnveilProfile.PERMISSION_SHEET, OpeningUnveilProfile.permissionSheet(context))
}
