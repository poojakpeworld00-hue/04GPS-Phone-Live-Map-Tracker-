package io.launcher.home.models

open class AppletsListElementsKeeper(val widgets: ArrayList<CoreApplet>) : AppletsListElement() {
    override fun getHashToCompare() = widgets.sumOf { it.getHashToCompare() }
}
