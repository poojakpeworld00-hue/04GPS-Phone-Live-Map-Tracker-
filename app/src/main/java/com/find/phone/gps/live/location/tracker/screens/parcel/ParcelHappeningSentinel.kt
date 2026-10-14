package com.find.phone.gps.live.location.tracker.screens.parcel

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.logic.HomeSpotsProfile
import com.find.phone.gps.live.location.tracker.homeshell.HomeFlip
import com.find.phone.gps.live.location.tracker.induction.HomeStream
import java.lang.ref.WeakReference
import kotlin.concurrent.thread

object ParcelHappeningSentinel {
    private const val TAG = "PackageEvent"

    private const val PENDING_FILE = "package_event_pending"
    private const val KEY_INSTALLED = "pending_installed"
    private const val KEY_PACKAGE = "pending_package"
    private const val KEY_TIME = "pending_time"
    private const val KEY_LAST_SHOWN = "last_shown_at"

    private const val PENDING_TTL_MS = 10 * 60 * 1000L

    private var registered = false

    private var resumed = WeakReference<Activity>(null)

    fun register(app: Application) {
        if (registered) return
        registered = true
        app.registerActivityLifecycleCallbacks(Callbacks)

        ContextCompat.registerReceiver(
            app,
            Receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        thread { ParcelSpecificsStash.plantAll(app) }
    }

    private object Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart
            val app = context.applicationContext

            if (intent.action == Intent.ACTION_PACKAGE_ADDED && !pkg.isNullOrBlank()) {
                thread { ParcelSpecificsStash.remember(app, pkg) }
            }

            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            if (pkg.isNullOrBlank() || pkg == app.packageName) return

            val installed = when (intent.action) {
                Intent.ACTION_PACKAGE_ADDED -> true
                Intent.ACTION_PACKAGE_REMOVED -> false
                else -> return
            }
            onEvent(app, installed, pkg)
        }
    }

    private fun onEvent(context: Context, installed: Boolean, pkg: String) {
        runCatching {
            if (!gatesPass(context)) return
            val activity = resumed.get()
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                fire(activity, installed, pkg)
            } else {
                enqueue(context, installed, pkg)
                Log.d(TAG, "queued $pkg (installed=$installed) — no foreground screen")
            }
        }.onFailure { Log.w(TAG, "onEvent($pkg) failed", it) }
    }

    private fun fire(activity: Activity, installed: Boolean, pkg: String) {
        settings(activity).edit { putLong(KEY_LAST_SHOWN, System.currentTimeMillis()) }
        activity.startActivity(
            Intent(activity, ParcelVerdictActivity::class.java)
                .putExtra(ParcelVerdictActivity.EXTRA_INSTALLED, installed)
                .putExtra(ParcelVerdictActivity.EXTRA_PACKAGE, pkg)
        )
        Log.d(TAG, "showed $pkg (installed=$installed) from ${activity.javaClass.simpleName}")

        if (!installed) {
            val app = activity.applicationContext
            activity.window.decorView.postDelayed({
                thread { ParcelSpecificsStash.forget(app, pkg) }
            }, FORGET_DELAY_MS)
        }
    }

    private fun gatesPass(context: Context): Boolean {
        if (!SpotsLocker.getInstance(context).getBoolean("IsAdsON")) return false
        if (!HomeFlip.isEnabled(context)) return false
        if (!HomeStream.wasOnboardingCompleted(context)) return false
        val settings = HomeSpotsProfile.packageResultSettings(context)
        if (!settings.enabled) return false
        if (settings.minGapMs > 0L) {
            val last = settings(context).getLong(KEY_LAST_SHOWN, 0L)
            if (System.currentTimeMillis() - last < settings.minGapMs) {
                Log.d(TAG, "throttled (min_gap ${settings.minGapMs}ms)")
                return false
            }
        }
        return true
    }

    private fun enqueue(context: Context, installed: Boolean, pkg: String) {
        settings(context).edit {
            putBoolean(KEY_INSTALLED, installed)
            putString(KEY_PACKAGE, pkg)
            putLong(KEY_TIME, System.currentTimeMillis())
        }
    }

    private fun takePending(context: Context): Pair<Boolean, String>? {
        val data = settings(context)
        val pkg = data.getString(KEY_PACKAGE, null) ?: return null
        val installed = data.getBoolean(KEY_INSTALLED, true)
        val at = data.getLong(KEY_TIME, 0L)
        data.edit { remove(KEY_INSTALLED); remove(KEY_PACKAGE); remove(KEY_TIME) }
        if (System.currentTimeMillis() - at > PENDING_TTL_MS) return null
        return installed to pkg
    }

    private fun settings(context: Context) =
        context.applicationContext.getSharedPreferences(PENDING_FILE, Context.MODE_PRIVATE)

    private object Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is ParcelVerdictActivity) return
            resumed = WeakReference(activity)

            val (installed, pkg) = takePending(activity.applicationContext) ?: return
            runCatching {
                if (gatesPass(activity.applicationContext)) fire(activity, installed, pkg)
            }.onFailure { Log.w(TAG, "drain($pkg) failed", it) }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed.get() === activity) resumed.clear()
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumed.get() === activity) resumed.clear()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    private const val FORGET_DELAY_MS = 5_000L
}
