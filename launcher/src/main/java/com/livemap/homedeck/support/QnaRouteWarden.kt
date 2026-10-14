package com.livemap.homedeck.support

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.activities.FAQActivity
import org.fossify.commons.helpers.APP_FAQ
import org.fossify.commons.helpers.APP_ICON_IDS
import org.fossify.commons.helpers.APP_LAUNCHER_NAME
import org.fossify.commons.models.FAQItem
import timber.log.Timber

object QnaRouteWarden : Application.ActivityLifecycleCallbacks {
    private var registered = false

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext as? Application ?: return
        app.registerActivityLifecycleCallbacks(this)
        registered = true
    }

    fun launcherFaqItems(context: Context): ArrayList<FAQItem> {
        val items = ArrayList<FAQItem>()
        if (!context.resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)) {
            items += FAQItem(
                title = org.fossify.commons.R.string.faq_2_title_commons,
                text = org.fossify.commons.R.string.faq_2_text_commons
            )
            items += FAQItem(
                title = org.fossify.commons.R.string.faq_6_title_commons,
                text = org.fossify.commons.R.string.faq_6_text_commons
            )
        }
        return items
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is FAQActivity) return

        val present = runCatching { activity.intent?.getSerializableExtra(APP_FAQ) is ArrayList<*> }
            .getOrDefault(false)
        if (present) return
        Timber.w("QnaRouteWarden: FAQActivity started without $APP_FAQ, supplying the launcher's list")
        val old = activity.intent
        activity.intent = Intent(activity, FAQActivity::class.java).apply {
            putExtra(APP_FAQ, launcherFaqItems(activity))
            runCatching { old?.getStringExtra(APP_LAUNCHER_NAME) }.getOrNull()?.let { putExtra(APP_LAUNCHER_NAME, it) }
            runCatching { old?.getIntegerArrayListExtra(APP_ICON_IDS) }.getOrNull()?.let { putExtra(APP_ICON_IDS, it) }
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
