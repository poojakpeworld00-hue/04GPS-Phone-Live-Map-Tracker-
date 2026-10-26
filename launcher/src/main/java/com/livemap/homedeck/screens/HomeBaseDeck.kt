package com.livemap.homedeck.screens

import android.content.Context
import android.os.Bundle
import org.fossify.commons.activities.BaseSimpleActivity
import com.livemap.homedeck.R
import com.livemap.homedeck.contract.HomeBook
import com.livemap.homedeck.support.REPOSITORY_NAME

open class HomeBaseDeck : BaseSimpleActivity() {
    private var spoofFossifyPackageName = false

    private companion object {
        val FOSSIFY_SPOOF: String = listOf("org", "fossify", "home").joinToString(".")
    }

    protected fun <T> withFossifyPackageNameSpoofed(block: () -> T): T {
        spoofFossifyPackageName = true
        try {
            return block()
        } finally {
            spoofFossifyPackageName = false
        }
    }

    private var builtLocale: String? = null

    private var recreateScheduled = false

    override fun attachBaseContext(newBase: Context) {
        HomeBook.ensure(newBase)
        val localized = HomeBook.bridge.localizeContext(newBase)
        builtLocale = localized.resources.configuration.locales[0]?.toString()
        super.attachBaseContext(localized)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        withFossifyPackageNameSpoofed { super.onCreate(savedInstanceState) }
    }

    override fun onResume() {
        super.onResume()

        val now = HomeBook.bridge.localizeContext(applicationContext)
            .resources.configuration.locales[0]?.toString()
        if (now != builtLocale && !recreateScheduled) {
            recreateScheduled = true
            window.decorView.post {
                if (!isFinishing && !isDestroyed) recreate()
            }
        }
    }

    override fun getPackageName(): String {
        return if (spoofFossifyPackageName) FOSSIFY_SPOOF else super.getPackageName()
    }

    override fun getAppIconIDs() = ArrayList<Int>()

    override fun getAppLauncherName() = getString(R.string.launcher_app_launcher_name)

    override fun getRepositoryName() = REPOSITORY_NAME
}
