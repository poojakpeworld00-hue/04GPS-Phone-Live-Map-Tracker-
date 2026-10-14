package com.find.my.phone.gps.location.tracker.permission.sheet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.data.sim.MySimNumbers
import com.find.my.phone.gps.location.tracker.permission.AccessKit

/**
 * One page of the permission sheet: what it asks for and how it reads.
 *
 * The order of [all] is the order the sheet walks through: Notifications → SIM info → Contacts →
 * Location (design "Permission Sheet", Flow).
 */
enum class SheetPage(
    /** Analytics / [com.find.my.phone.gps.location.tracker.permission.AccessVault] key. */
    val key: String,
    /** Everything the main button asks for in one Android prompt. */
    val permissions: Array<String>,
    @StringRes val shortName: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @LayoutRes val illustration: Int,
) {
    NOTIFICATIONS(
        "notification",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
        R.string.ps_notif_name, R.string.ps_notif_title, R.string.ps_notif_body,
        R.layout.ps_ill_notifications,
    ),
    SIM(
        "phone_state", MySimNumbers.PERMISSIONS,
        R.string.ps_sim_name, R.string.ps_sim_title, R.string.ps_sim_body,
        R.layout.ps_ill_sim,
    ),
    CONTACTS(
        "contacts", arrayOf(Manifest.permission.READ_CONTACTS),
        R.string.ps_contacts_name, R.string.ps_contacts_title, R.string.ps_contacts_body,
        R.layout.ps_ill_contacts,
    ),
    LOCATION(
        "location",
        // Both, so Android 12+ offers the precise/approximate choice; approximate is enough.
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
        R.string.ps_location_name, R.string.ps_location_title, R.string.ps_location_body,
        R.layout.ps_ill_location,
    );

    /** The permission whose grant counts as "this page is done". */
    val primaryPermission: String? get() = permissions.firstOrNull()

    /**
     * Done when every permission it asks for is granted — except Location, where approximate
     * alone is enough.
     */
    fun isGranted(context: Context): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when (this) {
            LOCATION -> granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> permissions.all(::granted)
        }
    }

    companion object {
        /**
         * The pages this device should see. Notifications only exist on Android 13+ and only while
         * the permission engine may offer them ([AccessKit.isOfferable] covers its Remote Config
         * switch); on Android 12 and lower the counter becomes 1/3.
         */
        fun all(context: Context): List<SheetPage> = entries.filter {
            it != NOTIFICATIONS || (it.permissions.isNotEmpty() && AccessKit.isOfferable(context, it.key))
        }
    }
}
