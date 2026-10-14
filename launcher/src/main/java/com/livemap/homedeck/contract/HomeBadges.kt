package com.livemap.homedeck.contract

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.livemap.homedeck.R
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.support.BadgeMolder

object HomeBadges {
    @JvmStatic
    fun shape(context: Context, drawable: Drawable): Drawable =
        BadgeMolder.shape(drawable, context.launcherConfig.legacyIconTray)

    @JvmStatic
    fun enactTo(imageView: ImageView) {
        val current = imageView.drawable ?: return
        if (imageView.getTag(R.id.lnch_tag_icon_shaped) === current) return
        val shaped = shape(imageView.context, current)
        imageView.setImageDrawable(shaped)
        imageView.setTag(R.id.lnch_tag_icon_shaped, shaped)
    }
}
