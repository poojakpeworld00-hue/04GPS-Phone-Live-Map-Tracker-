package com.livemap.homedeck.parts

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import com.livemap.homedeck.R
import com.livemap.homedeck.screens.HomeDeck
import com.livemap.homedeck.contract.HomeDeckMatter
import com.livemap.homedeck.contract.HomeBook
import com.livemap.homedeck.databinding.HdHostPanelBinding
import kotlin.math.abs

class HolderDeckPlane(
    context: Context,
    attributeSet: AttributeSet,
) : HomePlane<HdHostPanelBinding>(context, attributeSet) {
    private val gestureDetector = GestureDetectorCompat(context, object : SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (velocityX < 0 && abs(velocityX) > abs(velocityY)) {
                activity?.concealHostPanel()
                return true
            }
            return false
        }
    })

    val panelContent: Fragment?
        get() = activity?.supportFragmentManager?.findFragmentById(R.id.lv_host_panel_containerUi)

    private val panelCallbacks: HomeDeckMatter?
        get() = panelContent as? HomeDeckMatter

    override fun setupFragment(activity: HomeDeck) {
        this.activity = activity
        this.binding = HdHostPanelBinding.bind(this)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    fun refresh() {
        val activity = activity ?: return
        val fm = activity.supportFragmentManager

        if (fm.isStateSaved) return
        if (fm.findFragmentById(R.id.lv_host_panel_containerUi) == null) {
            val fragment = HomeBook.bridge.panelFragment() ?: return

            fm.beginTransaction()
                .replace(R.id.lv_host_panel_containerUi, fragment)
                .commitNow()
        }
    }

    fun warmUp() = refresh()

    fun setPanelVisible(visible: Boolean) {
        panelCallbacks?.setPanelVisible(visible)
    }

    fun handleBack(): Boolean = panelCallbacks?.handleBack() == true
}
