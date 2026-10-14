package com.find.phone.gps.live.location.tracker.consent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker

object ConsentToolbox {
    val CATALOG: Map<String, ConsentSchema> = listOf(
        ConsentSchema(
            key = "notification",
            androidPermission = Manifest.permission.POST_NOTIFICATIONS,
            minSdk = Build.VERSION_CODES.TIRAMISU,
        ),
    ).associateBy { it.key }

    fun spec(key: String): ConsentSchema? = CATALOG[key]

    fun isApplicableOnThisSdk(spec: ConsentSchema): Boolean =
        Build.VERSION.SDK_INT >= spec.minSdk

    fun isPrefGateOpen(context: Context, spec: ConsentSchema): Boolean {
        val gate = spec.enabledPrefGate ?: return true
        return SpotsLocker.getInstance(context).getBoolean(gate)
    }

    fun isOfferable(context: Context, key: String): Boolean {
        val spec = spec(key) ?: return false
        if (!isApplicableOnThisSdk(spec)) return false
        if (!isPrefGateOpen(context, spec)) return false
        val rule = runCatching { ConsentFeed.rules().firstOrNull { it.key == key } }.getOrNull()
            ?: return true
        if (!rule.enabled) return false

        if (rule.showOnce && ConsentLocker(context).wasShown(key)) return false
        return true
    }

    fun isGranted(context: Context, spec: ConsentSchema): Boolean {
        if (Build.VERSION.SDK_INT < spec.minSdk) return true
        return ContextCompat.checkSelfPermission(context, spec.androidPermission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
