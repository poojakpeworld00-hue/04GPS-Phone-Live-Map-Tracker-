package com.find.phone.gps.live.location.tracker.helpers

import android.util.Log
import com.find.phone.gps.live.location.tracker.BuildConfig
import com.google.firebase.crashlytics.FirebaseCrashlytics

object WardenRail {
    private const val TAG = "WardenRail"

    fun log(tag: String = TAG, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }

    fun error(tag: String = TAG, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.e(tag, message, throwable)
            return
        }

        runCatching {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.log("$tag: $message")
            if (throwable != null) crashlytics.recordException(throwable)
        }
    }

    inline fun execDebug(block: () -> Unit) {
        if (BuildConfig.DEBUG) block()
    }
}
