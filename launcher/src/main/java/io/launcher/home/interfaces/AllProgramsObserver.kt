package io.launcher.home.interfaces

import io.launcher.home.models.CoreHome

interface AllProgramsObserver {
    fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: CoreHome)
}
