package com.find.my.phone.gps.location.tracker.ui.lookup

import android.content.Context
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.data.lookup.LineType

/** "Mobile", "Landline"… or null when libphonenumber can't tell. */
fun LineType.label(context: Context): String? = when (this) {
    LineType.MOBILE -> context.getString(R.string.ds_line_mobile)
    LineType.FIXED_LINE -> context.getString(R.string.ds_line_fixed)
    LineType.FIXED_OR_MOBILE -> context.getString(R.string.ds_line_fixed_mobile)
    LineType.TOLL_FREE -> context.getString(R.string.ds_line_toll_free)
    LineType.PREMIUM -> context.getString(R.string.ds_line_premium)
    LineType.VOIP -> context.getString(R.string.ds_line_voip)
    LineType.OTHER -> null
}
