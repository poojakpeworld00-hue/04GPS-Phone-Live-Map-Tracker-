package com.find.phone.gps.live.location.tracker.monetize.render

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import androidx.viewbinding.ViewBinding
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.monetize.render.fullscreen.TakeoverStandard
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.PopupAppRedirectBinding
import kotlin.apply
import kotlin.let
import kotlin.text.isNullOrEmpty

fun isNetworkConnected(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetworkInfo
    return network != null && network.isConnected
}

fun View.visible() {
    this.visibility = View.VISIBLE
}

fun View.gone() {
    this.visibility = View.GONE
}

fun View.invisible() {
    this.visibility = View.GONE
}

fun Directlink(context: Context?) {
    Log.e("===>","bhbjhb")
    val activity = context as? Activity ?: return

    if (activity.isFinishing || activity.isDestroyed) return

    val adsPref = SpotsLocker.getInstance(activity)
    if (!adsPref.getBoolean("IsCustomADS")) return

    val url = adsPref.getString("DirectLink")
    if (url.isNullOrBlank()) return

    val uri = try {
        Uri.parse(url).takeIf { it.scheme != null } ?: return
    } catch (e: Exception) {
        return
    }

    try {
        val customTabsIntent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setToolbarColor(ContextCompat.getColor(activity, R.color.black))
            .build()

        customTabsIntent.launchUrl(activity, uri)

    } catch (e: Exception) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri)
            activity.startActivity(browserIntent)
        } catch (_: Exception) {}
    }
}

private fun isPackageInstalled(context: Context, packageName: String): Boolean {
    return try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }
}

fun Activity.presentAppRedirectPopup(onDismiss: (() -> Unit)? = null) {
    val appUrl = SpotsLocker(this).getString("In_App_Update_Link")
    if (appUrl.isNullOrEmpty()) return

    val dialogBinding = PopupAppRedirectBinding.inflate(layoutInflater)

    val dialog = presentDialog(
        activity = this,
        viewBinding = dialogBinding,
        cancelable = false
    )

    dialogBinding.apply {
        actContinue.setOnClickListener {
            try {
                appUrl.toUri().let { uri ->
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        actCancel.setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }
    }
}

fun presentDialog(
    activity: Activity,
    viewBinding: ViewBinding,
    cancelable: Boolean = false,
    onDismiss: (() -> Unit)? = null
): Dialog {
    val dialog = Dialog(activity)
    dialog.setContentView(viewBinding.root)

    dialog.window?.apply {
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        setDimAmount(0.6f)
    }

    dialog.setCancelable(cancelable)
    dialog.setCanceledOnTouchOutside(cancelable)

    dialog.setOnDismissListener {
        onDismiss?.invoke()
    }

    if (!activity.isFinishing && !activity.isDestroyed) {
        dialog.show()
    }

    return dialog
}

inline fun <reified T : Activity> Context.enterActivity(
    isNeedToClearTop: Boolean = false,
    isAdd: Boolean = true,
    extras: Bundle? = null,
    launcher: ActivityResultLauncher<Intent>? = null
) {
    val intent = Intent(this, T::class.java)
    extras?.let { intent.putExtras(it) }

    if (isNeedToClearTop) {
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }

    val activity = this as? Activity

    if (launcher != null) {
        launcher.launch(intent)
        return
    }

    if (!isAdd || activity == null) {
        startActivity(intent)
        return
    }

    TakeoverStandard().presentInterAds(activity) {
        activity.startActivity(intent)
    }
}