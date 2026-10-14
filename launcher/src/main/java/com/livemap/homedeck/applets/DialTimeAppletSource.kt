package com.livemap.homedeck.applets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.widget.RemoteViews
import com.livemap.homedeck.R

class DialTimeAppletSource : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { reviseWidget(context, appWidgetManager, it) }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        reviseWidget(context, appWidgetManager, appWidgetId)
    }

    private fun reviseWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
    ) {
        val views = RemoteViews(context.packageName, R.layout.hd_widget_analog_clock).apply {
            setInt(
                R.id.gadget_holderUi,
                "setBackgroundColor",
                context.getWidgetBackgroundColor(appWidgetId)
            )
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
