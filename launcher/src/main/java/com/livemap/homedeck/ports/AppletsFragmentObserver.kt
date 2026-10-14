package com.livemap.homedeck.ports

import com.livemap.homedeck.entities.CoreApplet

interface AppletsFragmentObserver {
    fun onWidgetLongPressed(appWidget: CoreApplet)
}
