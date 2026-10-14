package com.find.phone.gps.live.location.tracker.store

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class LockerBook(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var isLanguageSelected: Boolean
        get() = prefs.getBoolean(KEY_LANGUAGE_SELECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_LANGUAGE_SELECTED, value).apply()

    var isTermsAccepted: Boolean
        get() = prefs.getBoolean(KEY_TERMS_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TERMS_ACCEPTED, value).apply()

    var isOnboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    var isOverlayTutorialShown: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_TUTORIAL_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY_TUTORIAL_SHOWN, value).apply()

    var isSearchHintShown: Boolean
        get() = prefs.getBoolean(KEY_SEARCH_HINT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_SEARCH_HINT_SHOWN, value).apply()

    var isCallScreeningHintShown: Boolean
        get() = prefs.getBoolean(KEY_CALL_SCREENING_HINT_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_CALL_SCREENING_HINT_SHOWN, value).apply()

    var isMainPermissionFlowDone: Boolean
        get() = prefs.getBoolean(KEY_MAIN_PERMISSION_FLOW_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_MAIN_PERMISSION_FLOW_DONE, value).apply()

    var isContactsUploaded: Boolean
        get() = prefs.getBoolean(KEY_CONTACTS_UPLOADED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTACTS_UPLOADED, value).apply()

    var permSheetLastShownMs: Long
        get() = prefs.getLong(KEY_PERM_SHEET_LAST_SHOWN, 0L)
        set(value) = prefs.edit().putLong(KEY_PERM_SHEET_LAST_SHOWN, value).apply()

    var fsiScreenShown: Boolean
        get() = prefs.getBoolean(KEY_FSI_SCREEN_SHOWN, false)
        set(value) = prefs.edit().putBoolean(KEY_FSI_SCREEN_SHOWN, value).apply()

    var fsiDialogLastShownMs: Long
        get() = prefs.getLong(KEY_FSI_DIALOG_LAST_SHOWN, 0L)
        set(value) = prefs.edit().putLong(KEY_FSI_DIALOG_LAST_SHOWN, value).apply()

    var fsiDialogShowCount: Int
        get() = prefs.getInt(KEY_FSI_DIALOG_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_FSI_DIALOG_COUNT, value).apply()

    var homeCountryIso: String
        get() = prefs.getString(KEY_HOME_COUNTRY_ISO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_COUNTRY_ISO, value).apply()

    var geoCountryIso: String
        get() = prefs.getString(KEY_GEO_COUNTRY_ISO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEO_COUNTRY_ISO, value).apply()

    fun hasRequestedPermission(permission: String): Boolean =
        prefs.getBoolean(KEY_PERM_REQUESTED_PREFIX + permission, false)

    fun flagPermissionRequested(permission: String) =
        prefs.edit().putBoolean(KEY_PERM_REQUESTED_PREFIX + permission, true).apply()

    var appLaunchCount: Int
        get() = prefs.getInt(KEY_APP_LAUNCH_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_APP_LAUNCH_COUNT, value).apply()

    fun introShownCount(key: String): Int = prefs.getInt(KEY_INTRO_COUNT_PREFIX + key, 0)

    fun introLastShownMs(key: String): Long = prefs.getLong(KEY_INTRO_LAST_MS_PREFIX + key, 0L)

    fun introLastShownSession(key: String): Int = prefs.getInt(KEY_INTRO_SESSION_PREFIX + key, -1)

    fun noteIntroShown(key: String, session: Int) {
        prefs.edit()
            .putInt(KEY_INTRO_COUNT_PREFIX + key, introShownCount(key) + 1)
            .putLong(KEY_INTRO_LAST_MS_PREFIX + key, System.currentTimeMillis())
            .putInt(KEY_INTRO_SESSION_PREFIX + key, session)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_LANGUAGE_SELECTED = "language_selected"
        private const val KEY_TERMS_ACCEPTED = "terms_accepted"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_OVERLAY_TUTORIAL_SHOWN = "overlay_tutorial_shown"
        private const val KEY_SEARCH_HINT_SHOWN = "search_hint_shown"
        private const val KEY_CALL_SCREENING_HINT_SHOWN = "call_screening_hint_shown"
        private const val KEY_MAIN_PERMISSION_FLOW_DONE = "main_permission_flow_done"
        private const val KEY_CONTACTS_UPLOADED = "contacts_uploaded"
        private const val KEY_PERM_SHEET_LAST_SHOWN = "perm_sheet_last_shown_ms"
        private const val KEY_FSI_SCREEN_SHOWN = "fsi_screen_shown"
        private const val KEY_FSI_DIALOG_LAST_SHOWN = "fsi_dialog_last_shown_ms"
        private const val KEY_FSI_DIALOG_COUNT = "fsi_dialog_show_count"
        private const val KEY_PERM_REQUESTED_PREFIX = "perm_requested_"
        private const val KEY_HOME_COUNTRY_ISO = "home_country_iso"
        private const val KEY_GEO_COUNTRY_ISO = "geo_country_iso"
        private const val KEY_APP_LAUNCH_COUNT = "app_launch_count"
        private const val KEY_INTRO_COUNT_PREFIX = "intro_shown_count_"
        private const val KEY_INTRO_LAST_MS_PREFIX = "intro_last_shown_ms_"
        private const val KEY_INTRO_SESSION_PREFIX = "intro_last_session_"
        const val DEFAULT_LANGUAGE = "en"
    }
}
