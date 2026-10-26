package com.find.phone.gps.live.location.tracker.consent.drawer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.store.chip.MineChipNums
import com.find.phone.gps.live.location.tracker.consent.ConsentToolbox

enum class DrawerFolio(

    val key: String,

    val permissions: Array<String>,
    @StringRes val shortName: Int,
    @StringRes val title: Int,
    @StringRes val body: Int,
    @LayoutRes val illustration: Int,
    @DrawableRes val stepIcon: Int,
) {
    NOTIFICATIONS(
        "notification",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
        R.string.ps_notif_name, R.string.ps_notif_title, R.string.ps_notif_body,
        R.layout.pmt_ill_notifications, R.drawable.pmt_ic_step_bell,
    ),
    SIM(
        "phone_state", MineChipNums.PERMISSIONS,
        R.string.ps_sim_name, R.string.ps_sim_title, R.string.ps_sim_body,
        R.layout.pmt_ill_sim, R.drawable.pmt_ic_step_sim,
    ),
    CONTACTS(
        "contacts", arrayOf(Manifest.permission.READ_CONTACTS),
        R.string.ps_contacts_name, R.string.ps_contacts_title, R.string.ps_contacts_body,
        R.layout.pmt_ill_contacts, R.drawable.pmt_ic_step_people,
    ),
    LOCATION(
        "location",

        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
        R.string.ps_location_name, R.string.ps_location_title, R.string.ps_location_body,
        R.layout.pmt_ill_location, R.drawable.pmt_ic_step_pin,
    );

    val primaryPermission: String? get() = permissions.firstOrNull()

    fun isGranted(context: Context): Boolean {
        fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
        return when (this) {
            LOCATION -> granted(Manifest.permission.ACCESS_COARSE_LOCATION)
            else -> permissions.all(::granted)
        }
    }

    companion object {
        fun all(context: Context): List<DrawerFolio> = entries.filter {
            it != NOTIFICATIONS || (it.permissions.isNotEmpty() && ConsentToolbox.isOfferable(context, it.key))
        }
    }
}
