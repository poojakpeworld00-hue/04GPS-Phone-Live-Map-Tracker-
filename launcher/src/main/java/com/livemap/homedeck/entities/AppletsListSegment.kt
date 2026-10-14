package com.livemap.homedeck.entities

import android.graphics.drawable.Drawable

data class AppletsListSegment(var appTitle: String, var appIcon: Drawable?) : AppletsListElement() {
    override fun getHashToCompare() = getStringToCompare().hashCode()

    private fun getStringToCompare(): String {
        return copy(appIcon = null).toString()
    }
}
