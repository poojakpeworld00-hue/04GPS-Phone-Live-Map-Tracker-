package com.find.phone.gps.live.location.tracker.store

import android.content.Context
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker

object LingoBook {
    private const val TAG = "LingoBook"

    fun apply(languageTag: String) {
        try {
            AppCompatDelegate.setApplicationLocales(
                LocaleListCompat.forLanguageTags(languageTag)
            )
        } catch (e: IllegalStateException) {
            if (BuildConfig.DEBUG) Log.w(TAG, "locale '$languageTag' refused: ${e.message}")
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "locale '$languageTag' failed: ${e.message}")
        }
    }

    fun enactSaved(context: Context) {
        val savedLang = CoreLocker.selectedLanguage(context)
        if (savedLang.isEmpty()) return

        val normalizedLang = if (savedLang == "in") "id" else savedLang
        val current = context.resources.configuration.locales[0].language
        if (normalizedLang == current) return

        apply(normalizedLang)
    }

}
