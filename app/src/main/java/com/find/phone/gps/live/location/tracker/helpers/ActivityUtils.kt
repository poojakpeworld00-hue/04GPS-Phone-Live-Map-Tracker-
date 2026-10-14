package com.find.phone.gps.live.location.tracker.helpers

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.find.phone.gps.live.location.tracker.monetize.logic.SpotsLocker
import com.find.phone.gps.live.location.tracker.R
import java.io.InputStream

fun isNightMode(context: Context): Boolean {
    val mode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK
    return mode == Configuration.UI_MODE_NIGHT_YES
}

fun Activity.setStatusBar(
    color: Int,
    isLightIcons: Boolean
) {
    window.statusBarColor = color

    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.isAppearanceLightStatusBars = isLightIcons
}

fun Activity.setTransparentStatusBarWhiteText() {
    window.statusBarColor = Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val controller = window.insetsController
        controller?.setSystemBarsAppearance(
            0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

    }
}

fun Activity.setTransparentStatusBarDarkText() {
    window.statusBarColor = Color.TRANSPARENT

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        )
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

}

fun Fragment.getStatusBarHeight(): Int {
    return requireActivity().getStatusBarHeight()
}

fun Context.getStatusBarHeight(): Int {
    val resourceId =
        resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0)
        resources.getDimensionPixelSize(resourceId)
    else 0
}

fun Context.rateApp() {
    val appPackageName = packageName
    val playStoreUri = Uri.parse("market://details?id=$appPackageName")
    val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")

    try {
        val intent = Intent(Intent.ACTION_VIEW, playStoreUri)
        startActivity(intent)
    } catch (e: Exception) {
        startActivity(Intent(Intent.ACTION_VIEW, webUri))
    }
}

fun Context.enterPolicyLink() {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val bundle = Bundle()
            bundle.putBinder("android.support.customtabs.extra.SESSION", null)
            putExtras(bundle)

            putExtra(
                "android.support.customtabs.extra.TOOLBAR_COLOR",
                ContextCompat.getColor(this@enterPolicyLink, R.color.black)
            )
            putExtra("android.support.customtabs.extra.EXTRA_ENABLE_INSTANT_APPS", true)

            setPackage("com.android.chrome")
            data =
                Uri.parse(SpotsLocker.getInstance(this@enterPolicyLink).getString("PrivacyPolicy"))
        }
        startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun Context.enterTermLink() {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val bundle = Bundle()
            bundle.putBinder("android.support.customtabs.extra.SESSION", null)
            putExtras(bundle)

            putExtra(
                "android.support.customtabs.extra.TOOLBAR_COLOR",
                ContextCompat.getColor(this@enterTermLink, R.color.black)
            )
            putExtra("android.support.customtabs.extra.EXTRA_ENABLE_INSTANT_APPS", true)

            setPackage("com.android.chrome")
            data =
                Uri.parse(SpotsLocker.getInstance(this@enterTermLink).getString("TermLink"))
        }
        startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun Context.spreadApp() {
    val appPackageName = packageName
    val shareText = getString(
        R.string.share_app_text,
        "https://play.google.com/store/apps/details?id=$appPackageName"
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, shareText)
    }
    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_app_via)))
}

fun enterThemeAsset(am: AssetManager, basePath: String): InputStream? {
    return try {
        am.open("$basePath.webp")
    } catch (_: Exception) {
        try {
            am.open("$basePath.png")
        } catch (_: Exception) {
            null
        }
    }
}
