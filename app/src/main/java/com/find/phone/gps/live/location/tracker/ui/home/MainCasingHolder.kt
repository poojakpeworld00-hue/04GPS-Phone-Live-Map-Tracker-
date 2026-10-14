package com.find.phone.gps.live.location.tracker.ui.home

import android.app.Activity
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.find.phone.gps.live.location.tracker.launcher.HomeCasingHolder

/**
 * Implemented by whichever Activity is hosting [MainHubFragment].
 *
 * There are two: [com.find.phone.gps.live.location.tracker.ui.CoreHubActivity], where the shell *is* the
 * screen, and the launcher's home screen, where the same shell rides in the swipe-right side
 * panel. Everything that differs between those two lives behind this interface, so the shell
 * itself never asks which one it is in.
 */
interface MainCasingHolder {

    /** The hosting Activity — result launchers, dialogs and system round-trips need it. */
    val hostActivity: AppCompatActivity

    /**
     * The Activity-bound half of the shell.
     *
     * Must be a **field initializer** in the implementing Activity, not `by lazy`:
     * constructing it registers `ActivityResultLauncher`s, which has to happen before the
     * Activity is STARTED. A panel-hosted fragment is committed later than that, which is
     * the whole reason this controller exists separately from the fragment.
     */
    val homeShellController: MainCasingDirector

    /**
     * Whether the shell is actually the thing the user is looking at right now.
     *
     * In [com.find.phone.gps.live.location.tracker.ui.CoreHubActivity] the shell *is* the screen, so this
     * is always true. In the launcher it is true only while the swipe-right caller panel is
     * open — the same Activity also draws the home grid, and anything the shell puts on
     * screen while the panel is shut lands over that grid instead of over its own content.
     */
    val isShellOnScreen: Boolean

    /**
     * Back was pressed on Home with the visited-tab history already empty.
     *
     * CoreHubActivity leaves for the launcher home screen; the launcher panel just closes.
     */
    fun onShellBackExhausted()

    /**
     * Offer the "a new version is downloaded — restart to install" affordance.
     *
     * Owned by the host rather than by [MainHubFragment] because the launcher commits that
     * fragment at `onCreate` and parks it off screen: a Snackbar anchored inside it while the
     * caller panel is shut is drawn on a view the user cannot see, so the update sits pending
     * with nothing on screen to act on. Each host puts it where its user is actually looking.
     */
    fun showUpdateReadyPrompt()

    /**
     * Pull the host Activity back to the front of its task.
     *
     * Called when a grant is detected while the user is sitting on a system Settings page,
     * so the (NO_HISTORY) Settings page drops away without them pressing Back.
     */
    fun bringHostToFront()
}

/** The shell host, for any fragment or dialog attached inside the home shell. */
/** CoreHubActivity itself, or the launcher's stand-in when the shell rides in the launcher panel. */
val Activity.homeShellHost: MainCasingHolder?
    get() = this as? MainCasingHolder ?: HomeCasingHolder.of(this)

val Fragment.homeShellHost: MainCasingHolder? get() = activity?.homeShellHost

/** The Activity-bound shell controller, or null when not hosted by a shell. */
val Fragment.homeShellController: MainCasingDirector? get() = homeShellHost?.homeShellController

