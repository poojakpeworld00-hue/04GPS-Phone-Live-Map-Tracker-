package com.livemap.homedeck.applets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.RemoteViews
import com.livemap.homedeck.R

class NumericTimeAppletSource : AppWidgetProvider() {
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
        val textColor = context.getWidgetTextColor(appWidgetId)
        val is24Hours = DateFormat.is24HourFormat(context)

        val views = RemoteViews(context.packageName, R.layout.hd_widget_digital_clock).apply {
            setInt(
                R.id.gadget_holderUi,
                "setBackgroundColor",
                context.getWidgetBackgroundColor(appWidgetId)
            )

            setViewVisibility(R.id.gadget_text_clock_24Ui, if (is24Hours) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.gadget_text_clock_12Ui, if (is24Hours) View.GONE else View.VISIBLE)

            setTextColor(R.id.gadget_text_clock_24Ui, textColor)
            setTextColor(R.id.gadget_text_clock_12Ui, textColor)
            setTextColor(R.id.gadget_dateUi, textColor)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
