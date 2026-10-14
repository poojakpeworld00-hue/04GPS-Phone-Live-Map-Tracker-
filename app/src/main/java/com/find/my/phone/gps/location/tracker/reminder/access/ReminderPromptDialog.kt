package com.find.my.phone.gps.location.tracker.reminder.access

import android.animation.Animator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.loop
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.pvh
import java.lang.ref.WeakReference

/**
 * "Get your reminders on time" (design 4): the bell on the gradient disc with two pulsing rings,
 * title / description / button from Remote Config, and "Maybe later". Layout
 * `dialog_reminder_prompt`.
 */
object ReminderPromptDialog {

    private var current: WeakReference<Dialog>? = null
    private val anims = mutableListOf<Animator>()

    fun show(
        activity: Activity,
        title: CharSequence,
        description: CharSequence,
        buttonText: CharSequence,
        onDismiss: () -> Unit = {},
        onButton: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismissIfShowing()

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = activity.layoutInflater.inflate(R.layout.dialog_reminder_prompt, null)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.setCancelable(true)

        view.findViewById<TextView>(R.id.promptTitle).text = title
        view.findViewById<TextView>(R.id.promptDesc).text = description
        view.findViewById<TextView>(R.id.promptButtonLabel).text = buttonText
        view.findViewById<View>(R.id.promptButton).apply {
            DesignMotion.pressScale(this)
            setOnClickListener { onButton() }
        }
        view.findViewById<View>(R.id.promptLater).setOnClickListener { runCatching { dialog.dismiss() } }

        animate(view)
        dialog.setOnDismissListener {
            anims.forEach { it.cancel() }
            anims.clear()
            current = null
            onDismiss()
        }
        current = WeakReference(dialog)
        dialog.show()
    }

    fun dismissIfShowing() {
        current?.get()?.let { d -> if (d.isShowing) runCatching { d.dismiss() } }
        current = null
    }

    /** Rings: scale .7 → 1.55 / 1.9 while fading out; bell: a swing every 3s from its hanger. */
    private fun animate(root: View) {
        if (DesignMotion.animationsDisabled(root.context)) return
        val e = DesignMotion.EASE_OUT
        listOf(R.id.promptRing1 to 1.55f, R.id.promptRing2 to 1.9f).forEachIndexed { i, (id, to) ->
            anims += loop(
                root.findViewById(id), 2400L, i * 600L,
                pvh(View.SCALE_X, e, 0f to 0.7f, 1f to to),
                pvh(View.SCALE_Y, e, 0f to 0.7f, 1f to to),
                pvh(View.ALPHA, e, 0f to 0.6f, 0.7f to 0f, 1f to 0f),
            )
        }
        val bell = root.findViewById<View>(R.id.promptIcon)
        bell.post { bell.pivotX = bell.width / 2f; bell.pivotY = bell.height * 0.12f }
        anims += loop(
            bell, 3000L, 0L,
            pvh(View.ROTATION, DesignMotion.EASE_IN_OUT,
                0f to 0f, 0.3f to 0f, 0.4f to 14f, 0.5f to -11f, 0.6f to 8f, 0.7f to -5f, 0.8f to 2f, 0.9f to 0f, 1f to 0f),
        )
    }
}
