package com.find.phone.gps.live.location.tracker.store

import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.find.phone.gps.live.location.tracker.helpers.CoreLocker
import java.util.Locale

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

    fun localized(base: Context): Context {
        val tag = savedTag(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        val current = base.resources.configuration.locales[0]
        if (current != null && current.language == locale.language) return base
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }

    private fun savedTag(context: Context): String? =
        CoreLocker.selectedLanguage(context).takeIf { it.isNotEmpty() }?.let { if (it == "in") "id" else it }

    fun enactSaved(context: Context) {
        val savedLang = CoreLocker.selectedLanguage(context)
        if (savedLang.isEmpty()) return

        val normalizedLang = if (savedLang == "in") "id" else savedLang
        val current = context.resources.configuration.locales[0].language
        if (normalizedLang == current) return

        apply(normalizedLang)
    }

}
