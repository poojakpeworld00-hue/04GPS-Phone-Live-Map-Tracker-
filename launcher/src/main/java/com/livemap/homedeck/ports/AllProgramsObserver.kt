package com.livemap.homedeck.ports

import com.livemap.homedeck.entities.CoreHome

interface AllProgramsObserver {
    fun onAppLauncherLongPressed(x: Float, y: Float, appLauncher: CoreHome)
}
