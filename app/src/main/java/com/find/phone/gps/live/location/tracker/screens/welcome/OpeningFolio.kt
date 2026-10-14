package com.find.phone.gps.live.location.tracker.screens.welcome

import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.find.phone.gps.live.location.tracker.R

data class OpeningFolio(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descRes: Int,
    @param:LayoutRes val sceneRes: Int,

    val showsAd: Boolean = false,
)

object OpeningLeaves {
    val all: List<OpeningFolio> = listOf(
        OpeningFolio(R.string.ds_intro_title_1, R.string.ds_intro_body_1, R.layout.stg_intro_find),
        OpeningFolio(R.string.ds_intro_title_2, R.string.ds_intro_body_2, R.layout.stg_intro_map),
        OpeningFolio(R.string.ds_intro_title_3, R.string.ds_intro_body_3, R.layout.stg_intro_search, showsAd = true),
        OpeningFolio(R.string.ds_intro_title_4, R.string.ds_intro_body_4, R.layout.stg_intro_connected),
    )
}
