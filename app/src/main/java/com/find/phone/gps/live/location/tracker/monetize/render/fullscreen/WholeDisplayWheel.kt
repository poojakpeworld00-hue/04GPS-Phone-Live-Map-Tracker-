package com.find.phone.gps.live.location.tracker.monetize.render.fullscreen

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
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.R

object WholeDisplayWheel {
    private var dialog: Dialog? = null

    private const val LOADER_SHOW_KEY = "Inter_Loader_Show"
    private const val LEGACY_LOADER_KEY = "isLoaderForFB"

    private const val LOADER_MS_KEY = "Inter_Loader_Ms"
    private const val MAX_LOADER_MS = 3_000

    fun isEnabled(context: Context): Boolean {
        val vault = SpotsLocker.getInstance(context)
        val set = vault.getBoolean(LOADER_SHOW_KEY, true)

        val present = set == vault.getBoolean(LOADER_SHOW_KEY, false)
        return if (present) set else vault.getBoolean(LEGACY_LOADER_KEY)
    }

    fun beforeShow(activity: Activity, show: () -> Unit) {
        val ms = SpotsLocker.getInstance(activity).getInt(LOADER_MS_KEY).coerceIn(0, MAX_LOADER_MS)
        if (ms == 0 || !isEnabled(activity) || activity.isFinishing || activity.isDestroyed) return show()
        show(activity, true)
        Handler(Looper.getMainLooper()).postDelayed({
            hide()
            if (!activity.isFinishing && !activity.isDestroyed) show()
        }, ms.toLong())
    }

    @Suppress("DEPRECATION")
    fun show(activity: Activity, isLoader: Boolean) {
        if (!isLoader) return

        try {
            if (activity.isFinishing || activity.isDestroyed) return

            retireSafely()

            dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setContentView(R.layout.part_fullscreen)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )

                window?.decorView?.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

                show()

                window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }

        } catch (e: Exception) {
            Log.e("WholeDisplayWheel", "Error showing loader", e)
        }
    }

    fun hide() {
        retireSafely()
    }

    private fun retireSafely() {
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
        } finally {
            dialog = null
        }
    }
}

