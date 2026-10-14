package com.livemap.homedeck.ports

import com.livemap.homedeck.entities.MainDisplayLatticeElement

interface ElementOptionsObserver {
    fun onAnyClick()
    fun hide(gridItem: MainDisplayLatticeElement)
    fun rename(gridItem: MainDisplayLatticeElement)
    fun resize(gridItem: MainDisplayLatticeElement)
    fun appInfoUi(gridItem: MainDisplayLatticeElement)
    fun remove(gridItem: MainDisplayLatticeElement)
    fun uninstall(gridItem: MainDisplayLatticeElement)
    fun onDismiss()
}

abstract class ElementOptionsObserverAdapter : ElementOptionsObserver {
    override fun onAnyClick() = Unit
    override fun hide(gridItem: MainDisplayLatticeElement) = Unit
    override fun rename(gridItem: MainDisplayLatticeElement) = Unit
    override fun resize(gridItem: MainDisplayLatticeElement) = Unit
    override fun appInfoUi(gridItem: MainDisplayLatticeElement) = Unit
    override fun remove(gridItem: MainDisplayLatticeElement) = Unit
    override fun uninstall(gridItem: MainDisplayLatticeElement) = Unit
    override fun onDismiss() = Unit
}
