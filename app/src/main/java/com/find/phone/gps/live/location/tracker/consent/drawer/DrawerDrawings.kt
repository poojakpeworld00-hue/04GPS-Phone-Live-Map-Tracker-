package com.find.phone.gps.live.location.tracker.consent.drawer

import android.animation.Animator
import android.view.View
import android.widget.TextView
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.dp
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.loop
import com.find.phone.gps.live.location.tracker.screens.craft.CraftMovement.pvh

object DrawerDrawings {
    fun bind(page: DrawerFolio, root: View) {
        when (page) {
            DrawerFolio.CONTACTS -> {
                listOf(R.id.lv_sub1 to "2104", R.id.lv_sub2 to "2114", R.id.lv_sub3 to "2124").forEach { (id, digits) ->
                    root.findViewById<TextView>(id).text = root.context.getString(R.string.ps_contacts_mobile, digits)
                }
            }
            DrawerFolio.SIM -> {
                val without = root.findViewById<View>(R.id.txtWithout)
                val with = root.findViewById<View>(R.id.txtWith)

                root.findViewById<UnveilVersusArrange>(R.id.lv_reveal).onFractionChanged = { f ->
                    without.alpha = 1f - 0.65f * f
                    with.alpha = 0.35f + 0.65f * f
                }
            }
            else -> Unit
        }
    }

    fun start(page: DrawerFolio, root: View): List<Animator> {
        if (page == DrawerFolio.SIM) {
            root.findViewById<UnveilVersusArrange>(R.id.lv_reveal).start()
            return emptyList()
        }
        if (CraftMovement.animationsDisabled(root.context)) return emptyList()
        return when (page) {
            DrawerFolio.NOTIFICATIONS -> notifications(root)
            DrawerFolio.CONTACTS -> contacts(root)
            DrawerFolio.LOCATION -> location(root)
            DrawerFolio.SIM -> emptyList()
        }
    }

    fun stop(page: DrawerFolio, root: View, running: MutableList<Animator>) {
        if (page == DrawerFolio.SIM) root.findViewById<UnveilVersusArrange>(R.id.lv_reveal).stop()
        running.forEach { it.cancel() }
        running.clear()
        rewindToRest(root)
    }

    private fun rewindToRest(root: View) {
        listOf(R.id.lv_ring, R.id.lv_bell, R.id.lv_badge, R.id.plateA, R.id.plateB, R.id.line1, R.id.line2, R.id.line3,
            R.id.lineCheck, R.id.lv_areaPing, R.id.lv_pin).forEach { id ->
            root.findViewById<View>(id)?.apply {
                alpha = 1f; scaleX = 1f; scaleY = 1f; rotation = 0f; translationX = 0f; translationY = 0f
            }
        }
    }

    private fun ping(view: View, durationMs: Long): Animator = loop(
        view, durationMs, 0L,

        pvh(View.SCALE_X, CraftMovement.EASE_OUT, 0f to 0.4f, 1f to 1.5f),
        pvh(View.SCALE_Y, CraftMovement.EASE_OUT, 0f to 0.4f, 1f to 1.5f),
        pvh(View.ALPHA, CraftMovement.EASE_OUT, 0f to 0.9f, 1f to 0f),
    )

    private fun pop(view: View, durationMs: Long): Animator = loop(
        view, durationMs, 0L,
        pvh(View.SCALE_X, CraftMovement.EASE_OUT, 0f to 0f, 0.1f to 0f, 0.2f to 1.2f, 0.26f to 1f, 1f to 1f),
        pvh(View.SCALE_Y, CraftMovement.EASE_OUT, 0f to 0f, 0.1f to 0f, 0.2f to 1.2f, 0.26f to 1f, 1f to 1f),
    )

    private fun notifications(root: View): List<Animator> {
        val ctx = root.context
        val bell = root.findViewById<View>(R.id.lv_bell)

        bell.post { bell.pivotX = bell.width / 2f; bell.pivotY = bell.height * 0.1f }
        val e = CraftMovement.EASE_IN_OUT
        val up12 = ctx.dp(12f)
        val down6 = ctx.dp(6f)
        return listOf(
            ping(root.findViewById(R.id.lv_ring), 2400L),
            loop(
                bell, 2400L, 0L,
                pvh(View.ROTATION, e, 0f to 0f, 0.1f to 14f, 0.2f to -12f, 0.3f to 8f, 0.4f to -4f, 0.5f to 0f, 1f to 0f),
            ),
            pop(root.findViewById(R.id.lv_badge), 2400L),
            loop(
                root.findViewById(R.id.plateA), 4800L, 0L,
                pvh(View.ALPHA, e, 0f to 0f, 0.08f to 0f, 0.18f to 1f, 0.8f to 1f, 0.92f to 0f, 1f to 0f),
                pvh(View.TRANSLATION_Y, e, 0f to up12, 0.08f to up12, 0.18f to 0f, 0.8f to 0f, 0.92f to -down6, 1f to -down6),
            ),
            loop(
                root.findViewById(R.id.plateB), 4800L, 0L,
                pvh(View.ALPHA, e, 0f to 0f, 0.3f to 0f, 0.4f to 1f, 0.84f to 1f, 0.94f to 0f, 1f to 0f),
                pvh(View.TRANSLATION_Y, e, 0f to -up12, 0.3f to -up12, 0.4f to 0f, 1f to 0f),
            ),
        )
    }

    private fun contacts(root: View): List<Animator> {
        val shift = root.context.dp(10f)
        val rows = listOf(R.id.line1, R.id.line2, R.id.line3).mapIndexed { i, id ->
            val row = root.findViewById<View>(id)

            row.alpha = 0f
            loop(
                row, 3600L, i * 200L,
                pvh(View.ALPHA, CraftMovement.EASE_OUT, 0f to 0f, 0.04f to 0f, 0.16f to 1f, 1f to 1f),
                pvh(View.TRANSLATION_X, CraftMovement.EASE_OUT, 0f to -shift, 0.04f to -shift, 0.16f to 0f, 1f to 0f),
            )
        }
        return rows + pop(root.findViewById(R.id.lineCheck), 3600L)
    }

    private fun location(root: View): List<Animator> {
        val ctx = root.context
        return listOf(
            ping(root.findViewById(R.id.lv_areaPing), 2600L),

            loop(
                root.findViewById(R.id.lv_pin), 2600L, 0L,
                pvh(
                    View.TRANSLATION_Y, CraftMovement.EASE_OUT,
                    0f to -ctx.dp(50f), 0.25f to ctx.dp(4f), 0.33f to -ctx.dp(3f), 0.4f to 0f, 1f to 0f,
                ),
                pvh(View.ALPHA, CraftMovement.EASE_OUT, 0f to 0f, 0.25f to 1f, 1f to 1f),
            ),
        )
    }
}
