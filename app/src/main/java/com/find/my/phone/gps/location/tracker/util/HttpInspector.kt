package com.find.my.phone.gps.location.tracker.util

import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.chuckerteam.chucker.api.RetentionManager
import com.find.my.phone.gps.location.tracker.GpsTrackerApp
import okhttp3.OkHttpClient

/**
 * Chucker on an OkHttp client: every request and response is listed in the "Chucker"
 * notification on debug builds. Release builds link `library-no-op`, where the same calls do
 * nothing, so nothing is recorded or shown there.
 *
 * [redact] headers are masked in the recording (the lookup `x-api-key`), so a shared debug build
 * does not hand the key to whoever opens the inspector.
 */
fun OkHttpClient.Builder.addHttpInspector(vararg redact: String): OkHttpClient.Builder {
    val context = GpsTrackerApp.appContext
    val collector = ChuckerCollector(
        context = context,
        showNotification = true,
        retentionPeriod = RetentionManager.Period.ONE_DAY,
    )
    return addInterceptor(
        ChuckerInterceptor.Builder(context)
            .collector(collector)
            // A phonebook upload can be megabytes; keep the recording to the first 250 KB.
            .maxContentLength(250_000L)
            .redactHeaders(*redact)
            .alwaysReadResponseBody(true)
            .build()
    )
}
