package com.livemap.homedeck.entities

open class AppletsListElementsKeeper(val widgets: ArrayList<CoreApplet>) : AppletsListElement() {
    override fun getHashToCompare() = widgets.sumOf { it.getHashToCompare() }
}
