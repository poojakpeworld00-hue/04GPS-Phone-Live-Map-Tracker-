package com.livemap.homedeck.support

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.net.toUri
import com.livemap.homedeck.ext.homeScreenGridItemsDB
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.entities.MainDisplayLatticeElement
import timber.log.Timber

object AutoErasure {
    private val MAKER_MESSAGING = mapOf(
        "samsung" to "com.samsung.android.messaging",
        "xiaomi" to "com.miui.mms",
        "redmi" to "com.miui.mms",
        "poco" to "com.miui.mms",
        "oneplus" to "com.oneplus.mms",
        "oppo" to "com.coloros.mms",
        "realme" to "com.coloros.mms",
        "motorola" to "com.motorola.messaging",
        "sony" to "com.sonyericsson.conversations",
        "htc" to "com.htc.sense.mms",
        "lge" to "com.lge.message",
        "asus" to "com.asus.message",
    )

    private val STOCK_MESSAGING = listOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "com.miui.mms",
        "com.oneplus.mms",
        "com.coloros.mms",
        "com.oplus.mms",
        "com.android.messaging",
        "com.motorola.messaging",
        "com.sonyericsson.conversations",
        "com.htc.sense.mms",
        "com.lge.message",
        "com.asus.message",
    )

    fun isOurRow(item: MainDisplayLatticeElement, ownPackage: String): Boolean =
        item.packageName == ownPackage && !item.className.startsWith(PSEUDO_WIDGET_PREFIX)

    fun hide(context: Context) {
        val app = context.applicationContext
        val own = app.packageName
        app.launcherConfig.selfIconHidden = true
        runCatching {
            val db = app.homeScreenGridItemsDB
            val items = db.getAllItems()
            val ours = items.filter { isOurRow(it, own) }
            val dockSpot = ours.firstOrNull { it.docked && it.parentId == null }

            ours.forEach { row -> row.id?.let { db.eraseById(it) } }

            val messaging = systemMessagingPackage(app)
            if (messaging != null) {
                val alreadyDocked = items.any { it.docked && it.parentId == null && it.packageName == messaging }
                if (dockSpot != null && !alreadyDocked) {
                    db.insert(dockRow(app, messaging, dockSpot))
                }

                app.launcherConfig.dockHostPackage = messaging
            }
            Timber.d("AutoErasure: hid %d rows, dock spot=%s -> %s", ours.size, dockSpot?.left, messaging)
        }.onFailure { Timber.e(it, "AutoErasure: could not clear our rows") }
    }

    fun systemMessagingPackage(context: Context): String? = runCatching {
        val pm = context.packageManager
        val own = context.packageName
        val handlers = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, "smsto:".toUri()), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != own && pm.getLaunchIntentForPackage(it) != null }
        val system = handlers.filter { isSystemApp(pm, it) }
        val maker = MAKER_MESSAGING[android.os.Build.MANUFACTURER.orEmpty().lowercase()]
        maker?.takeIf { it in system }
            ?: STOCK_MESSAGING.firstOrNull { it in system }
            ?: system.firstOrNull()
            ?: STOCK_MESSAGING.firstOrNull { it in handlers }
            ?: STOCK_MESSAGING.firstOrNull { it != own && pm.getLaunchIntentForPackage(it) != null }
    }.getOrNull()

    private fun isSystemApp(pm: PackageManager, packageName: String): Boolean = runCatching {
        (pm.getApplicationInfo(packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }.getOrDefault(false)

    private fun dockRow(context: Context, packageName: String, spot: MainDisplayLatticeElement): MainDisplayLatticeElement {
        val pm = context.packageManager
        val title = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault("")
        return MainDisplayLatticeElement(
            id = null,
            left = spot.left,
            top = spot.top,
            right = spot.right,
            bottom = spot.bottom,
            page = spot.page,
            packageName = packageName,
            activityName = "",
            title = title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = true,
            parentId = null
        )
    }
}
