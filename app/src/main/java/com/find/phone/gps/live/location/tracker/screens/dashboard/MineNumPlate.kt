package com.find.phone.gps.live.location.tracker.screens.dashboard

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
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.monetize.render.CoreOpenSpotBook
import com.find.phone.gps.live.location.tracker.store.LockerBook
import com.find.phone.gps.live.location.tracker.store.chip.MineChipNums
import com.find.phone.gps.live.location.tracker.databinding.PartMyNumberCardBinding
import com.find.phone.gps.live.location.tracker.databinding.CellMyNumberSimBinding
import com.find.phone.gps.live.location.tracker.screens.chip.MineNumActivity
import com.find.phone.gps.live.location.tracker.helpers.ChipNumDiary

class MineNumPlate(
    private val activity: AppCompatActivity,
    private val views: PartMyNumberCardBinding,
    private val prefs: LockerBook,
    private val requestPermissions: (Array<String>) -> Unit,
) {
    init {
        views.actMyNumber.setOnClickListener { onButton() }
        views.actManage.setOnClickListener {
            activity.recordKeyEvent("MyNumber_Manage_Click")
            activity.startActivity(MineNumActivity.intent(activity))
        }
    }

    fun render() {
        val context: Context = activity
        if (!MineChipNums.hasReadySim(context)) {
            views.root.isVisible = false
            return
        }
        views.root.isVisible = true

        val lines = MineChipNums.read(context)
        val showing = lines.isNotEmpty()
        views.actMyNumber.isVisible = !showing

        views.actManage.isVisible = showing
        views.actMyNumber.setText(if (isPermanentlyDenied()) R.string.my_number_settings else R.string.my_number_show)

        views.txtMyNumberSub.isVisible = !showing
        views.txtMyNumberSub.setText(R.string.my_number_sub)

        views.chipcardRows.removeAllViews()
        views.chipcardRows.isVisible = showing
        val inflater = LayoutInflater.from(views.root.context)
        lines.forEach { line -> views.chipcardRows.addView(wireRow(inflater, context, line)) }
    }

    fun onPermissionResult(result: Map<String, Boolean>) {
        val context: Context = activity
        result.forEach { (permission, granted) -> context.recordPermissionResult(permission, granted) }
        if (MineChipNums.isGranted(context)) ChipNumDiary.log(context)
        render()
    }

    private fun onButton() {
        val context: Context = activity
        context.recordKeyEvent("MyNumber_Show_Click")
        val permission = MineChipNums.PERMISSIONS.first()
        when {
            MineChipNums.isGranted(context) -> render()
            !prefs.hasRequestedPermission(permission) -> {
                prefs.flagPermissionRequested(permission)
                requestPermissions(MineChipNums.PERMISSIONS)
            }
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) -> requestPermissions(MineChipNums.PERMISSIONS)
            else -> enterAppSettings(context)
        }
    }

    private fun isPermanentlyDenied(): Boolean {
        val permission = MineChipNums.PERMISSIONS.first()
        return !MineChipNums.isGranted(activity) &&
            prefs.hasRequestedPermission(permission) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    private fun enterAppSettings(context: Context) {
        Toast.makeText(context, R.string.my_number_denied, Toast.LENGTH_LONG).show()
        CoreOpenSpotBook.skipNextAppOpenAd = true
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            )
        }
    }

    private fun wireRow(inflater: LayoutInflater, context: Context, line: MineChipNums.SimLine) =
        CellMyNumberSimBinding.inflate(inflater, views.chipcardRows, false).apply {
            val primarySlot = line.slot <= 1
            txtSimBadge.text = context.getString(R.string.my_number_sim_badge, line.slot)
            txtSimBadge.setBackgroundResource(
                if (primarySlot) R.drawable.lv_sim_badge_primary else R.drawable.lv_sim_badge_tint
            )
            txtSimBadge.setTextColor(
                ContextCompat.getColor(context, if (primarySlot) R.color.white else R.color.ds_splash_blue)
            )

            val number = line.number
            if (number != null) {
                txtSimNumber.text = number
                txtSimNumber.setTextColor(ContextCompat.getColor(context, R.color.ds_ink_fixed))
                actCopySim.setOnClickListener { copy(context, number) }
            } else {
                txtSimNumber.setText(R.string.my_number_not_stored)
                txtSimNumber.setTextColor(ContextCompat.getColor(context, R.color.ds_muted_fixed))
            }
            actCopySim.isVisible = number != null

            val region = line.region
            txtSimRegion.text = if (!region.isNullOrBlank()) "${line.carrier} · $region" else line.carrier
            txtSimRegion.isVisible = txtSimRegion.text.isNotBlank()
        }.root

    private fun copy(context: Context, number: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.my_number_title), number))
        Toast.makeText(context, R.string.my_number_copied, Toast.LENGTH_SHORT).show()
        context.recordKeyEvent("MyNumber_Copy")
    }
}
