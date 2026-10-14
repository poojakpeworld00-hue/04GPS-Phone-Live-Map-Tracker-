package com.livemap.homedeck.contract

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.fragment.app.Fragment

interface HomeGateway {
    fun configString(key: String, fallback: String = ""): String = fallback

    fun isOrganicAudience(): Boolean = true

    fun panelFragment(): Fragment? = null

    fun hostLaunchComponent(context: Context): ComponentName? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.component

    fun onLauncherStart(activity: Activity): Boolean = true

    fun onLauncherResume(activity: Activity) {}

    fun dockSlotPackage(context: Context): String? = context.packageName

    fun allowUninstallingHostIcon(): Boolean = false

    fun localizeContext(base: Context): Context = base

    fun removesHostIconInPlace(activity: Activity, shortcutId: String): Boolean = false

    fun onEvent(name: String, params: Map<String, String> = emptyMap()) {}

    fun onNonFatal(error: Throwable) {}

    fun defaultHomeHintIntent(context: Context): Intent? = null

    fun onAppLaunched(packageName: String) {}

    fun isDebug(): Boolean = false

    object Default : HomeGateway
}
