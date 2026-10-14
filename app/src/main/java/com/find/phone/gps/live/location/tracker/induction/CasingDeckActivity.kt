package com.find.phone.gps.live.location.tracker.induction

import android.content.Intent
import android.os.Bundle
import com.find.phone.gps.live.location.tracker.foundation.NavPressTracker
import org.fossify.commons.activities.BaseSimpleActivity
import com.find.phone.gps.live.location.tracker.R

open class CasingDeckActivity : BaseSimpleActivity() {
    private var spoofFossifyPackageName = false

    protected fun <T> withFossifyPackageNameSpoofed(block: () -> T): T {
        spoofFossifyPackageName = true
        try {
            return block()
        } finally {
            spoofFossifyPackageName = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        withFossifyPackageNameSpoofed { super.onCreate(savedInstanceState) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        NavPressTracker.onLeaveHint(this)
    }

    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        NavPressTracker.markAppLaunch()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun getPackageName(): String {
        return if (spoofFossifyPackageName) "org.fossify.home" else super.getPackageName()
    }

    override fun getAppIconIDs() = ArrayList(List(APP_ICON_COLOR_COUNT) { R.mipmap.ic_launcher })

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Launcher"

    private companion object {
        const val APP_ICON_COLOR_COUNT = 19
    }
}
