package com.find.phone.gps.live.location.tracker.consent

import android.app.Activity
import android.content.Context
import com.find.phone.gps.live.location.tracker.monetize.logic.recordKeyEvent
import com.find.phone.gps.live.location.tracker.monetize.logic.recordPermissionResult
import com.find.phone.gps.live.location.tracker.helpers.WardenRail
import java.lang.ref.WeakReference

object ConsentDriver {
    private const val TAG = "ConsentDriver"

    private val timer = ConsentTimer()

    private var ownerRef: WeakReference<Activity>? = null
    private var busy = false
    private var doneCallback: (() -> Unit)? = null

    fun init(context: Context) {
        try {
            ConsentFeed.renewFromRemote()
        } catch (e: Exception) {
            WardenRail.error(TAG, "init failed", e)
        }
    }

    private fun Activity.isGoneOrGoing(): Boolean = isFinishing || isDestroyed

    private fun shortPermName(spec: ConsentSchema): String = spec.androidPermission.substringAfterLast('.')

    @JvmStatic
    @JvmOverloads
    fun check(activity: Activity, onComplete: (() -> Unit)? = null) {
        try {
            if (activity.isGoneOrGoing()) {
                invokeSafely(onComplete)
                return
            }
            val screen = activity::class.java.simpleName

            if (busy) {
                val owner = ownerRef?.get()
                if (owner === activity) return
                reset(fire = false)
            }

            doneCallback = onComplete

            val rules = ConsentFeed.rules()
            val targeted = when {
                rules.isEmpty() -> emptyList()
                else -> DisplayMatcher.rulesFor(screen, rules)
            }
            if (targeted.isEmpty()) {
                WardenRail.log(TAG, "No permission rules for $screen")
                reset(fire = true)
                return
            }

            val locker = ConsentLocker(activity)
            val outstanding = targeted.filter { needsAsking(activity, it, locker) }
            if (outstanding.isEmpty()) {
                WardenRail.log(TAG, "All configured permissions already satisfied on $screen")
                reset(fire = true)
                return
            }

            WardenRail.log(TAG, "Queueing ${outstanding.size} permission(s) for $screen")
            busy = true
            ownerRef = WeakReference(activity)
            advance(ConsentBacklog(outstanding))
        } catch (e: Exception) {
            WardenRail.error(TAG, "check() failed", e)
            reset(fire = false)
        }
    }

    @JvmStatic
    @JvmOverloads
    fun request(activity: Activity, key: String, onComplete: (() -> Unit)? = null) {
        try {
            if (activity.isGoneOrGoing()) return invokeSafely(onComplete)
            val spec = ConsentToolbox.spec(key)
                ?: return run {
                    WardenRail.log(TAG, "request(): unknown permission key '$key' — skipped")
                    invokeSafely(onComplete)
                }
            val nothingToAsk = when {
                !ConsentToolbox.isApplicableOnThisSdk(spec) -> true
                !ConsentToolbox.isPrefGateOpen(activity, spec) -> true
                else -> ConsentToolbox.isGranted(activity, spec)
            }
            if (nothingToAsk) return invokeSafely(onComplete)

            val rule = ConsentFeed.rules().firstOrNull { it.key == key }
            if (rule != null && !rule.enabled) {
                WardenRail.log(TAG, "request(): '$key' disabled in config — skipped")
                return invokeSafely(onComplete)
            }
            val oneShot = rule?.showOnce == true
            val locker = ConsentLocker(activity)
            if (oneShot && locker.wasShown(key)) return invokeSafely(onComplete)

            locker.flagAsked(key)
            if (oneShot) locker.flagShown(key)

            activity.recordKeyEvent("Permission_${shortPermName(spec)}_Show")
            WardenRail.log(TAG, "request(): asking '$key' on ${activity::class.java.simpleName}")

            ConsentHome.launch(activity, spec.androidPermission) { granted ->
                activity.recordPermissionResult(spec.androidPermission, granted)
                WardenRail.log(TAG, "request(): '$key' granted=$granted")
                invokeSafely(onComplete)
            }
        } catch (e: Exception) {
            WardenRail.error(TAG, "request() failed", e)
            invokeSafely(onComplete)
        }
    }

    private fun needsAsking(activity: Activity, rule: ConsentClause, locker: ConsentLocker): Boolean {
        val spec = ConsentToolbox.spec(rule.key)
        return when {
            spec == null -> {
                WardenRail.log(TAG, "Unknown permission key '${rule.key}' — skipped")
                false
            }
            !ConsentToolbox.isApplicableOnThisSdk(spec) -> false
            !ConsentToolbox.isPrefGateOpen(activity, spec) -> {
                WardenRail.log(TAG, "Pref gate '${spec.enabledPrefGate}' closed — '${rule.key}' skipped")
                false
            }
            ConsentToolbox.isGranted(activity, spec) -> false
            else -> !(rule.showOnce && locker.wasShown(rule.key))
        }
    }

    private fun advance(queue: ConsentBacklog) {
        val rule = queue.poll()
        if (rule == null) {
            reset(fire = true)
            return
        }
        val spec = ConsentToolbox.spec(rule.key)
        if (spec == null) {
            advance(queue)
            return
        }

        val host = ownerRef?.get()
        if (host == null || host.isGoneOrGoing()) {
            reset(fire = false)
            return
        }
        if (ConsentToolbox.isGranted(host, spec)) {
            advance(queue)
            return
        }

        timer.schedule(rule.delayMs) { askNow(queue, rule, spec) }
    }

    private fun askNow(queue: ConsentBacklog, rule: ConsentClause, spec: ConsentSchema) {
        val act = ownerRef?.get()
        if (act == null || act.isGoneOrGoing()) {
            reset(fire = false)
            return
        }
        if (ConsentToolbox.isGranted(act, spec)) {
            advance(queue)
            return
        }

        val locker = ConsentLocker(act)
        locker.flagAsked(rule.key)
        if (rule.showOnce) locker.flagShown(rule.key)

        act.recordKeyEvent("Permission_${shortPermName(spec)}_Show")

        WardenRail.log(TAG, "Requesting '${rule.key}' on ${act::class.java.simpleName}")
        ConsentHome.launch(act, spec.androidPermission) { granted ->
            (ownerRef?.get() ?: act).recordPermissionResult(spec.androidPermission, granted)
            WardenRail.log(TAG, "Result '${rule.key}' granted=$granted")
            advance(queue)
        }
    }

    private fun reset(fire: Boolean) {
        timer.clear()
        busy = false
        ownerRef = null
        val cb = doneCallback
        doneCallback = null
        if (fire) invokeSafely(cb)
    }

    private fun invokeSafely(cb: (() -> Unit)?) {
        cb ?: return
        try {
            cb()
        } catch (e: Exception) {
            WardenRail.error(TAG, "onComplete callback threw", e)
        }
    }
}
