package com.find.phone.gps.live.location.tracker.screens.policy

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.render.ClueDrawerActivity
import com.livemap.homedeck.ext.isDefaultLauncher

object DriftToolbox {
    fun isGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun queryForOverlay(context: Context): Boolean =
        SpotsLocker.getInstance(context).getBoolean(OVERLAY_SWITCH_KEY, true)

    const val OVERLAY_SWITCH_KEY = "Overlay_Permission_Show"

    fun isOfferable(context: Context): Boolean {
        if (!queryForOverlay(context)) return false
        if (runCatching { context.isDefaultLauncher() }.getOrDefault(false)) return false
        return !SpotsLocker.getInstance(context).isNShowLocation
    }

    fun composeOverlayIntent(packageName: String): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).addFlags(
            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        )

    fun presentGuide(context: Context) =
        ClueDrawerActivity.show(context, ClueDrawerActivity.MODE_OVERLAY)
}
