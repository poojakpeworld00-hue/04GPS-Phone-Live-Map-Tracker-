package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.find.phone.gps.live.location.tracker.homeshell.HomeCasingHolder

interface MainCasingHolder {
    val hostActivity: AppCompatActivity

    val homeShellController: MainCasingDirector

    val isShellOnScreen: Boolean

    fun onShellBackExhausted()

    fun showUpdateReadyPrompt()

    fun bringHostToFront()
}

val Activity.homeShellHost: MainCasingHolder?
    get() = this as? MainCasingHolder ?: HomeCasingHolder.of(this)

val Fragment.homeShellHost: MainCasingHolder? get() = activity?.homeShellHost

val Fragment.homeShellController: MainCasingDirector? get() = homeShellHost?.homeShellController

