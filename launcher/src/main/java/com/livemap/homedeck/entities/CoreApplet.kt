package com.livemap.homedeck.entities

import android.appwidget.AppWidgetProviderInfo
import android.content.pm.ActivityInfo
import android.graphics.drawable.Drawable

data class CoreApplet(
    var appPackageName: String,
    var appTitle: String,
    val appIcon: Drawable?,
    val widgetTitleUi: String,
    val widgetPreviewImage: Drawable?,
    var widthCells: Int,
    val heightCells: Int,
    val isShortcut: Boolean,
    val className: String,
    val providerInfo: AppWidgetProviderInfo?,
    val activityInfo: ActivityInfo?
) : AppletsListElement() {
    override fun getHashToCompare() = getStringToCompare().hashCode()

    private fun getStringToCompare(): String {
        return copy(appIcon = null, widgetPreviewImage = null).toString()
    }
}
