package com.livemap.homedeck.support

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

class MarkContour(private val resources: Resources, private val tray: BadgeMolder.Tray) : BitmapTransformation() {
    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val out = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        BadgeMolder.shapeLogo(resources, toTransform, tray).apply {
            setBounds(0, 0, outWidth, outHeight)
            draw(Canvas(out))
        }
        return out
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID/${tray.name}".toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?) = other is MarkContour && other.tray == tray

    override fun hashCode() = (ID + tray.name).hashCode()

    private companion object {
        const val ID = "com.livemap.homedeck.support.MarkContour.v1"
    }
}
