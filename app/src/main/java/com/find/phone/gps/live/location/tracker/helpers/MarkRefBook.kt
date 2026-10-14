package com.find.phone.gps.live.location.tracker.helpers

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.livemap.homedeck.ext.isDefaultLauncher

object MarkRefBook {
    fun isCallerIdEnabled(context: Context): Boolean {
        if (!isRoleAvailable(context)) return true
        val rm = context.getSystemService(RoleManager::class.java) ?: return true
        return rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun holdsSystemDefaultRole(context: Context): Boolean {
        if (runCatching { context.isDefaultLauncher() }.getOrDefault(false)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return runCatching {
            listOf(RoleManager.ROLE_DIALER, RoleManager.ROLE_CALL_SCREENING).any {
                rm.isRoleAvailable(it) && rm.isRoleHeld(it)
            }
        }.getOrDefault(false)
    }

    fun isRoleAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)
    }

    fun composeEnableIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val rm = context.getSystemService(RoleManager::class.java) ?: return null
        if (!rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) return null
        if (rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) return null
        return rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }
}
