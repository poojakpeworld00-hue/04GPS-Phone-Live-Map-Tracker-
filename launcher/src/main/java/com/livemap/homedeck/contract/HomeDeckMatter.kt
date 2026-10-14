package com.livemap.homedeck.contract

interface HomeDeckMatter {
    fun setPanelVisible(visible: Boolean) {}

    fun handleBack(): Boolean = false
}
