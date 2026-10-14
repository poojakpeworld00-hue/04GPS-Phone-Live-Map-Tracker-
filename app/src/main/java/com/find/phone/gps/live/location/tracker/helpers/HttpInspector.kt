package com.find.phone.gps.live.location.tracker.helpers

import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.chuckerteam.chucker.api.RetentionManager
import com.find.phone.gps.live.location.tracker.NavTracerCore
import okhttp3.OkHttpClient

fun OkHttpClient.Builder.appendHttpInspector(vararg redact: String): OkHttpClient.Builder {
    val context = NavTracerCore.appContext
    val collector = ChuckerCollector(
        context = context,
        showNotification = true,
        retentionPeriod = RetentionManager.Period.ONE_DAY,
    )
    return addInterceptor(
        ChuckerInterceptor.Builder(context)
            .collector(collector)

            .maxContentLength(250_000L)
            .redactHeaders(*redact)
            .alwaysReadResponseBody(true)
            .build()
    )
}
