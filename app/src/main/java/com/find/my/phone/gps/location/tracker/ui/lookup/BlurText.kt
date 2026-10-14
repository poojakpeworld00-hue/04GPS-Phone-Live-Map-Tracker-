package com.find.my.phone.gps.location.tracker.ui.lookup

import android.graphics.BlurMaskFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.TextView

/**
 * Blurs (or un-blurs) a locked value so its shape shows but it can't be read. API 31+ blurs
 * the whole view with a RenderEffect; older versions blur the glyphs with a mask filter, which
 * needs a software layer. Blurred text is also kept out of accessibility and text selection.
 */
fun TextView.setBlurred(blurred: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        setRenderEffect(if (blurred) RenderEffect.createBlurEffect(BLUR_PX, BLUR_PX, Shader.TileMode.DECAL) else null)
    } else {
        setLayerType(if (blurred) View.LAYER_TYPE_SOFTWARE else View.LAYER_TYPE_NONE, null)
        paint.maskFilter = if (blurred) BlurMaskFilter(textSize / 3f, BlurMaskFilter.Blur.NORMAL) else null
        invalidate()
    }
    setTextIsSelectable(!blurred)
    importantForAccessibility = if (blurred) View.IMPORTANT_FOR_ACCESSIBILITY_NO else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
}

private const val BLUR_PX = 22f
