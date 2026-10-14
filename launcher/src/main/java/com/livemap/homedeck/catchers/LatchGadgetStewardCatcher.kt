package com.livemap.homedeck.catchers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import com.livemap.homedeck.R

class LatchGadgetStewardCatcher : DeviceAdminReceiver() {

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return context.getString(R.string.launcher_lock_device_admin_warning)
    }
}
