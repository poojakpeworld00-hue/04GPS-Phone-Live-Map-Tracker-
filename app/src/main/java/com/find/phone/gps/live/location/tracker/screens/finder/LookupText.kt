package com.find.phone.gps.live.location.tracker.screens.finder

import android.content.Context
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.store.inquiry.RowGenre

/** "Mobile", "Landline"… or null when libphonenumber can't tell. */
fun RowGenre.label(context: Context): String? = when (this) {
    RowGenre.MOBILE -> context.getString(R.string.ds_line_mobile)
    RowGenre.FIXED_LINE -> context.getString(R.string.ds_line_fixed)
    RowGenre.FIXED_OR_MOBILE -> context.getString(R.string.ds_line_fixed_mobile)
    RowGenre.TOLL_FREE -> context.getString(R.string.ds_line_toll_free)
    RowGenre.PREMIUM -> context.getString(R.string.ds_line_premium)
    RowGenre.VOIP -> context.getString(R.string.ds_line_voip)
    RowGenre.OTHER -> null
}
