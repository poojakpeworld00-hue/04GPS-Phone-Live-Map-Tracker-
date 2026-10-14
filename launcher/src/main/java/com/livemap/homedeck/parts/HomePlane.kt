package com.livemap.homedeck.parts

import android.content.Context
import android.util.AttributeSet
import android.widget.RelativeLayout
import androidx.viewbinding.ViewBinding
import com.livemap.homedeck.screens.HomeDeck

abstract class HomePlane<BINDING : ViewBinding>(
    context: Context,
    attributeSet: AttributeSet
) : RelativeLayout(context, attributeSet) {
    protected var activity: HomeDeck? = null
    protected lateinit var binding: BINDING

    abstract fun setupFragment(activity: HomeDeck)
}
