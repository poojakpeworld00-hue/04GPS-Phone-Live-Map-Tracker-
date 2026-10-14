package com.find.my.phone.gps.location.tracker.permission.sheet

import android.animation.Animator
import android.view.View
import android.widget.TextView
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.dp
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.loop
import com.find.my.phone.gps.location.tracker.ui.design.DesignMotion.pvh

/**
 * The permission sheet's looping illustrations, ported from the `@keyframes` in
 * "Permission Sheet.dc.html" (`ping`, `bell`, `pop`, `cardA`, `cardB`, `rowIn`, `drop`, and the
 * SIM page's `rev`/`hd`/`wOff`/`wOn`, which [RevealCompareLayout] owns).
 *
 * Only the page on screen animates ("paused off-screen" in the spec): the sheet calls [start] for
 * the selected page and [stop] for the rest. With system animations off nothing runs and every
 * view keeps its resting frame from the layout.
 */
object SheetIllustrations {

    /** One-time setup after the illustration is inflated into its page. */
    fun bind(page: SheetPage, root: View) {
        when (page) {
            SheetPage.CONTACTS -> {
                // Last four digits per row (2104 / 2114 / 2124).
                listOf(R.id.sub1 to "2104", R.id.sub2 to "2114", R.id.sub3 to "2124").forEach { (id, digits) ->
                    root.findViewById<TextView>(id).text = root.context.getString(R.string.ps_contacts_mobile, digits)
                }
            }
            SheetPage.SIM -> {
                val without = root.findViewById<View>(R.id.tvWithout)
                val with = root.findViewById<View>(R.id.tvWith)
                // wOff / wOn: the active side reads at full strength, the other at 35%.
                root.findViewById<RevealCompareLayout>(R.id.reveal).onFractionChanged = { f ->
                    without.alpha = 1f - 0.65f * f
                    with.alpha = 0.35f + 0.65f * f
                }
            }
            else -> Unit
        }
    }

    /** Starts [page]'s loops; returns them so the caller can [stop] them. */
    fun start(page: SheetPage, root: View): List<Animator> {
        if (page == SheetPage.SIM) {
            root.findViewById<RevealCompareLayout>(R.id.reveal).start()
            return emptyList()
        }
        if (DesignMotion.animationsDisabled(root.context)) return emptyList()
        return when (page) {
            SheetPage.NOTIFICATIONS -> notifications(root)
            SheetPage.CONTACTS -> contacts(root)
            SheetPage.LOCATION -> location(root)
            SheetPage.SIM -> emptyList()
        }
    }

    fun stop(page: SheetPage, root: View, running: MutableList<Animator>) {
        if (page == SheetPage.SIM) root.findViewById<RevealCompareLayout>(R.id.reveal).stop()
        running.forEach { it.cancel() }
        running.clear()
        resetToRest(root)
    }

    /** Puts every animated view back on its resting frame (what the layout draws). */
    private fun resetToRest(root: View) {
        listOf(R.id.ring, R.id.bell, R.id.badge, R.id.cardA, R.id.cardB, R.id.row1, R.id.row2, R.id.row3,
            R.id.rowCheck, R.id.areaPing, R.id.pin).forEach { id ->
            root.findViewById<View>(id)?.apply {
                alpha = 1f; scaleX = 1f; scaleY = 1f; rotation = 0f; translationX = 0f; translationY = 0f
            }
        }
    }

    private fun ping(view: View, durationMs: Long): Animator = loop(
        view, durationMs, 0L,
        // ping: scale .4 → 1.5, opacity .9 → 0, ease-out
        pvh(View.SCALE_X, DesignMotion.EASE_OUT, 0f to 0.4f, 1f to 1.5f),
        pvh(View.SCALE_Y, DesignMotion.EASE_OUT, 0f to 0.4f, 1f to 1.5f),
        pvh(View.ALPHA, DesignMotion.EASE_OUT, 0f to 0.9f, 1f to 0f),
    )

    /** pop: 0–10% hidden, 20% overshoot to 1.2, 26% settled. */
    private fun pop(view: View, durationMs: Long): Animator = loop(
        view, durationMs, 0L,
        pvh(View.SCALE_X, DesignMotion.EASE_OUT, 0f to 0f, 0.1f to 0f, 0.2f to 1.2f, 0.26f to 1f, 1f to 1f),
        pvh(View.SCALE_Y, DesignMotion.EASE_OUT, 0f to 0f, 0.1f to 0f, 0.2f to 1.2f, 0.26f to 1f, 1f to 1f),
    )

    private fun notifications(root: View): List<Animator> {
        val ctx = root.context
        val bell = root.findViewById<View>(R.id.bell)
        // transform-origin: 50% 10% — the bell swings from its hanger.
        bell.post { bell.pivotX = bell.width / 2f; bell.pivotY = bell.height * 0.1f }
        val e = DesignMotion.EASE_IN_OUT
        val up12 = ctx.dp(12f)
        val down6 = ctx.dp(6f)
        return listOf(
            ping(root.findViewById(R.id.ring), 2400L),
            loop(
                bell, 2400L, 0L,
                pvh(View.ROTATION, e, 0f to 0f, 0.1f to 14f, 0.2f to -12f, 0.3f to 8f, 0.4f to -4f, 0.5f to 0f, 1f to 0f),
            ),
            pop(root.findViewById(R.id.badge), 2400L),
            loop(
                root.findViewById(R.id.cardA), 4800L, 0L,
                pvh(View.ALPHA, e, 0f to 0f, 0.08f to 0f, 0.18f to 1f, 0.8f to 1f, 0.92f to 0f, 1f to 0f),
                pvh(View.TRANSLATION_Y, e, 0f to up12, 0.08f to up12, 0.18f to 0f, 0.8f to 0f, 0.92f to -down6, 1f to -down6),
            ),
            loop(
                root.findViewById(R.id.cardB), 4800L, 0L,
                pvh(View.ALPHA, e, 0f to 0f, 0.3f to 0f, 0.4f to 1f, 0.84f to 1f, 0.94f to 0f, 1f to 0f),
                pvh(View.TRANSLATION_Y, e, 0f to -up12, 0.3f to -up12, 0.4f to 0f, 1f to 0f),
            ),
        )
    }

    private fun contacts(root: View): List<Animator> {
        val shift = root.context.dp(10f)
        val rows = listOf(R.id.row1, R.id.row2, R.id.row3).mapIndexed { i, id ->
            val row = root.findViewById<View>(id)
            // rowIn, staggered 0 / .2 / .4s; hidden until its delay runs out.
            row.alpha = 0f
            loop(
                row, 3600L, i * 200L,
                pvh(View.ALPHA, DesignMotion.EASE_OUT, 0f to 0f, 0.04f to 0f, 0.16f to 1f, 1f to 1f),
                pvh(View.TRANSLATION_X, DesignMotion.EASE_OUT, 0f to -shift, 0.04f to -shift, 0.16f to 0f, 1f to 0f),
            )
        }
        return rows + pop(root.findViewById(R.id.rowCheck), 3600L)
    }

    private fun location(root: View): List<Animator> {
        val ctx = root.context
        return listOf(
            ping(root.findViewById(R.id.areaPing), 2600L),
            // drop: falls 50dp, bounces 4 / -3, settles by 40%.
            loop(
                root.findViewById(R.id.pin), 2600L, 0L,
                pvh(
                    View.TRANSLATION_Y, DesignMotion.EASE_OUT,
                    0f to -ctx.dp(50f), 0.25f to ctx.dp(4f), 0.33f to -ctx.dp(3f), 0.4f to 0f, 1f to 0f,
                ),
                pvh(View.ALPHA, DesignMotion.EASE_OUT, 0f to 0f, 0.25f to 1f, 1f to 1f),
            ),
        )
    }
}
