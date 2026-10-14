package com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.find.my.phone.gps.location.tracker.adcast.domain.AdsVault
import com.find.my.phone.gps.location.tracker.R

object FullScreenSpinner {

    private var dialog: Dialog? = null

    /** Remote Config: the interstitial loader on/off. Absent → the older `isLoaderForFB`. */
    private const val LOADER_SHOW_KEY = "Inter_Loader_Show"
    private const val LEGACY_LOADER_KEY = "isLoaderForFB"

    /**
     * Remote Config: how long (ms) the loader shows before a *preloaded* interstitial opens.
     * `0` / absent = open it straight away. Clamped to 3 s so a typo cannot park the user.
     */
    private const val LOADER_MS_KEY = "Inter_Loader_Ms"
    private const val MAX_LOADER_MS = 3_000

    /**
     * Whether the full-screen loader may show while an interstitial loads — `Inter_Loader_Show`,
     * falling back to `isLoaderForFB` for a config that does not carry the new key yet.
     */
    fun isEnabled(context: Context): Boolean {
        val vault = AdsVault.getInstance(context)
        val set = vault.getBoolean(LOADER_SHOW_KEY, true)
        // getBoolean has no "absent" answer, so ask with both defaults: they differ only when absent.
        val present = set == vault.getBoolean(LOADER_SHOW_KEY, false)
        return if (present) set else vault.getBoolean(LEGACY_LOADER_KEY)
    }

    /**
     * Runs [show] behind the loader for `Inter_Loader_Ms` — the "Loading ad…" beat before an
     * interstitial that is already loaded. Runs [show] immediately when the loader is off, the
     * delay is 0, or the activity is going away. [show] runs exactly once.
     */
    fun beforeShow(activity: Activity, show: () -> Unit) {
        val ms = AdsVault.getInstance(activity).getInt(LOADER_MS_KEY).coerceIn(0, MAX_LOADER_MS)
        if (ms == 0 || !isEnabled(activity) || activity.isFinishing || activity.isDestroyed) return show()
        show(activity, true)
        Handler(Looper.getMainLooper()).postDelayed({
            hide()
            if (!activity.isFinishing && !activity.isDestroyed) show()
        }, ms.toLong())
    }

    /**
     * Shows a full-screen transparent loader.
     * Checks if activity is alive before showing.
     */
    @Suppress("DEPRECATION")
    fun show(activity: Activity, isLoader: Boolean) {
        if (!isLoader) return

        try {
            if (activity.isFinishing || activity.isDestroyed) return

            // Dismiss existing one if still active
            dismissSafely()

            dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setContentView(R.layout.piece_fullscreen)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

                // FLAG_NOT_FOCUSABLE before show() prevents status-bar flicker
                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )

                // Immersive sticky — hide status bar + nav bar
                window?.decorView?.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

                show()

                // Restore focusability after show so touches register
                window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }

        } catch (e: Exception) {
            Log.e("FullScreenSpinner", "Error showing loader", e)
        }
    }

    fun hide() {
        dismissSafely()
    }

    private fun dismissSafely() {
        try {
            dialog?.let {
                if (it.isShowing) {
                    val context = it.context
                    if (context is Activity) {
                        if (!context.isFinishing && !context.isDestroyed) {
                            it.dismiss()
                        }
                    } else {
                        it.dismiss()
                    }
                }
            }
        } catch (e: Exception) {
            // Log.e("FullScreenSpinner", "Error dismissing loader", e)
        } finally {
            dialog = null
        }
    }
}


