package com.find.my.phone.gps.location.tracker.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.adcast.domain.logKeyEvent
import com.find.my.phone.gps.location.tracker.adcast.domain.logPermissionResult
import com.find.my.phone.gps.location.tracker.adcast.presentation.AppOpenAdRegistry
import com.find.my.phone.gps.location.tracker.data.VaultRegistry
import com.find.my.phone.gps.location.tracker.data.sim.MySimNumbers
import com.find.my.phone.gps.location.tracker.databinding.PieceMyNumberCardBinding
import com.find.my.phone.gps.location.tracker.databinding.TileMyNumberSimBinding
import com.find.my.phone.gps.location.tracker.ui.sim.MyNumberActivity
import com.find.my.phone.gps.location.tracker.util.SimNumberLog

/**
 * The "My number" card in the drawer header (design 1a, "SIMs visible"): the phone's own SIMs with
 * their number, carrier and region (no name).
 *
 * Phone access is asked here, when the user taps the card's button, so the system dialog appears
 * next to the feature that needs it. Denied twice (Android stops showing its dialog), the button
 * opens this app's settings instead. The card is hidden on a phone with no ready SIM.
 *
 * The host activity owns the permission launcher (it must be registered before the view exists)
 * and forwards its result to [onPermissionResult].
 */
class MyNumberCard(
    private val activity: AppCompatActivity,
    private val views: PieceMyNumberCardBinding,
    private val prefs: VaultRegistry,
    private val requestPermissions: (Array<String>) -> Unit,
) {

    init {
        views.btnMyNumber.setOnClickListener { onButton() }
        views.btnManage.setOnClickListener {
            activity.logKeyEvent("MyNumber_Manage_Click")
            activity.startActivity(MyNumberActivity.intent(activity))
        }
    }

    /** Redraws from the current SIM / permission state; call on view creation and on resume. */
    fun render() {
        val context: Context = activity
        if (!MySimNumbers.hasReadySim(context)) {
            views.root.isVisible = false
            return
        }
        views.root.isVisible = true

        val lines = MySimNumbers.read(context)
        val showing = lines.isNotEmpty()
        views.btnMyNumber.isVisible = !showing
        // SIMs visible → offer "Manage" (the My number page); otherwise the "Show" button.
        views.btnManage.isVisible = showing
        views.btnMyNumber.setText(if (isPermanentlyDenied()) R.string.my_number_settings else R.string.my_number_show)
        // Only the "ask for access" state needs the subtitle; once the SIMs show, the rows say it all.
        views.tvMyNumberSub.isVisible = !showing
        views.tvMyNumberSub.setText(R.string.my_number_sub)

        views.simRows.removeAllViews()
        views.simRows.isVisible = showing
        val inflater = LayoutInflater.from(views.root.context)
        lines.forEach { line -> views.simRows.addView(bindRow(inflater, context, line)) }
    }

    fun onPermissionResult(result: Map<String, Boolean>) {
        val context: Context = activity
        result.forEach { (permission, granted) -> context.logPermissionResult(permission, granted) }
        if (MySimNumbers.isGranted(context)) SimNumberLog.log(context)
        render()
    }

    private fun onButton() {
        val context: Context = activity
        context.logKeyEvent("MyNumber_Show_Click")
        val permission = MySimNumbers.PERMISSIONS.first()
        when {
            MySimNumbers.isGranted(context) -> render()
            !prefs.hasRequestedPermission(permission) -> {
                prefs.markPermissionRequested(permission)
                requestPermissions(MySimNumbers.PERMISSIONS)
            }
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) -> requestPermissions(MySimNumbers.PERMISSIONS)
            else -> openAppSettings(context)
        }
    }

    /** Asked before and Android no longer shows its dialog: only Settings can grant it now. */
    private fun isPermanentlyDenied(): Boolean {
        val permission = MySimNumbers.PERMISSIONS.first()
        return !MySimNumbers.isGranted(activity) &&
            prefs.hasRequestedPermission(permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    private fun openAppSettings(context: Context) {
        Toast.makeText(context, R.string.my_number_denied, Toast.LENGTH_LONG).show()
        AppOpenAdRegistry.skipNextAppOpenAd = true
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        }
    }

    private fun bindRow(inflater: LayoutInflater, context: Context, line: MySimNumbers.SimLine) =
        TileMyNumberSimBinding.inflate(inflater, views.simRows, false).apply {
            // Badge "SIM1" / "SIM2": slot 1 filled primary, the rest a light tint.
            val primarySlot = line.slot <= 1
            tvSimBadge.text = context.getString(R.string.my_number_sim_badge, line.slot)
            tvSimBadge.setBackgroundResource(
                if (primarySlot) R.drawable.ds_sim_badge_primary else R.drawable.ds_sim_badge_tint
            )
            tvSimBadge.setTextColor(
                ContextCompat.getColor(context, if (primarySlot) R.color.white else R.color.ds_splash_blue)
            )

            val number = line.number
            if (number != null) {
                tvSimNumber.text = number
                tvSimNumber.setTextColor(ContextCompat.getColor(context, R.color.ds_ink_fixed))
                btnCopySim.setOnClickListener { copy(context, number) }
            } else {
                tvSimNumber.setText(R.string.my_number_not_stored)
                tvSimNumber.setTextColor(ContextCompat.getColor(context, R.color.ds_muted_fixed))
            }
            btnCopySim.isVisible = number != null

            // Secondary line: "carrier · region", e.g. "Jio · Gujarat, India".
            val region = line.region
            tvSimRegion.text = if (!region.isNullOrBlank()) "${line.carrier} · $region" else line.carrier
            tvSimRegion.isVisible = tvSimRegion.text.isNotBlank()
        }.root

    private fun copy(context: Context, number: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.my_number_title), number))
        Toast.makeText(context, R.string.my_number_copied, Toast.LENGTH_SHORT).show()
        context.logKeyEvent("MyNumber_Copy")
    }
}
