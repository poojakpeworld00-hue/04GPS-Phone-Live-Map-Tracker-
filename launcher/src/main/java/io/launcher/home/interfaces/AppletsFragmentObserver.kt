package io.launcher.home.interfaces

import io.launcher.home.models.CoreApplet

interface AppletsFragmentObserver {
    fun onWidgetLongPressed(appWidget: CoreApplet)
}
