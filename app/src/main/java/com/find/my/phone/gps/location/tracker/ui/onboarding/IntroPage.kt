package com.find.my.phone.gps.location.tracker.ui.onboarding

import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.find.my.phone.gps.location.tracker.R

/** One onboarding page: title, body and the scene layout inflated into the hero card. */
data class IntroPage(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    @param:LayoutRes val sceneRes: Int,
    /** The native banner slot under the CTA is shown on this page (design page A3). */
    val showsAd: Boolean = false,
)

/** The four pages of onboarding 2a: radar, route, input, orbit. */
object IntroPages {
    val all: List<IntroPage> = listOf(
        IntroPage(R.string.ds_intro_title_1, R.string.ds_intro_body_1, R.layout.scene_intro_find),
        IntroPage(R.string.ds_intro_title_2, R.string.ds_intro_body_2, R.layout.scene_intro_map),
        IntroPage(R.string.ds_intro_title_3, R.string.ds_intro_body_3, R.layout.scene_intro_search, showsAd = true),
        IntroPage(R.string.ds_intro_title_4, R.string.ds_intro_body_4, R.layout.scene_intro_connected),
    )
}
