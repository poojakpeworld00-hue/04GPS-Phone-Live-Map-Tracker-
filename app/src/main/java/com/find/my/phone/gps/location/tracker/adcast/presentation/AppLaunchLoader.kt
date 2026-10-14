package com.find.my.phone.gps.location.tracker.adcast.presentation

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.Log
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import com.find.my.phone.gps.location.tracker.adcast.presentation.oninterAds.FullScreenSpinner
import com.find.my.phone.gps.location.tracker.R

/**
 * The loader shown while an ad for an app tap is fetched on the spot (`on_demand`): the tapped
 * app's icon and name over a spinner, so the pause reads as that app opening.
 *
 * Follows the same `Inter_Loader_Show` switch as [FullScreenSpinner]; off means the wait happens
 * with no loader at all. Without a known package it falls back to [FullScreenSpinner]'s generic
 * loader.
 */
object AppLaunchLoader {

    private var dialog: Dialog? = null

    fun show(activity: Activity, packageName: String?) {
        if (!FullScreenSpinner.isEnabled(activity)) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (packageName.isNullOrBlank()) return FullScreenSpinner.show(activity, true)

        hide()
        runCatching {
            dialog = Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setContentView(R.layout.piece_app_launch_loader)
                window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

                val pm = activity.packageManager
                val info = runCatching { pm.getApplicationInfo(packageName, 0) }.getOrNull()
                findViewById<ImageView>(R.id.launchLoaderIcon).setImageDrawable(
                    info?.let { pm.getApplicationIcon(it) } ?: pm.defaultActivityIcon
                )
                findViewById<TextView>(R.id.launchLoaderName).text =
                    info?.let { pm.getApplicationLabel(it) } ?: ""

                show()
            }
        }.onFailure { Log.e("AppLaunchLoader", "could not show loader", it) }
    }

    /** Takes down whichever loader is up — this one or the generic fallback. */
    fun hide() {
        runCatching {
            dialog?.takeIf { it.isShowing }?.let { d ->
                val owner = d.ownerActivity
                if (owner == null || (!owner.isFinishing && !owner.isDestroyed)) d.dismiss()
            }
        }
        dialog = null
        FullScreenSpinner.hide()
    }
}
