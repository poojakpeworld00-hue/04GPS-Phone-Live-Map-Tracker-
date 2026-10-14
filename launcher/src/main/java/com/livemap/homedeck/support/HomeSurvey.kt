package com.livemap.homedeck.support

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import com.livemap.homedeck.R
import com.livemap.homedeck.ext.getDrawableForPackageName
import com.livemap.homedeck.ext.hiddenIconsDB
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.ext.launchersDB
import com.livemap.homedeck.entities.CoreHome
import timber.log.Timber
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Produces the full launcher list (apps plus rendered icons) and stores it in [BadgeStash].
 * Can run ahead of the launcher via [prewarm]; an unchanged installed set short-circuits.
 */
object HomeSurvey {

    private const val GMS_PACKAGE = "com.google.android.gms"

    /** Minimum gap between two partial hand-offs during a cold scan. */
    private const val HANDOFF_GAP_MS = 150L

    private const val TINT_GRID = 16

    /** Serialises concurrent scans so a second caller can reuse the first one's result. */
    private val scanGate = Any()

    /** Fire and forget; safe to call from any thread and any number of times. */
    fun prewarm(context: Context) {
        val appContext = context.applicationContext
        val worker = Thread { runCatching { scan(appContext) } }
        worker.priority = Thread.MIN_PRIORITY + 2
        worker.start()
    }

    /** Label-only list of launchable apps: a single PackageManager query, no icon work. */
    @SuppressLint("WrongConstant")
    fun quick(context: Context): List<CoreHome> = with(context) {
        val hiddenKeys = hiddenIconsDB.getHiddenIcons().map { it.getIconIdentifier() }
        keepVisible(this, queryLaunchers(this), hiddenKeys)
            .map { entry -> labelOnly(entry, entry.loadLabel(packageManager).toString()) }
    }

    /**
     * Full list with icons. [onQuick] receives the label-only list when nothing is cached,
     * [onProgress] receives partial lists during a cold scan; both on the calling thread.
     */
    @SuppressLint("WrongConstant")
    fun scan(
        context: Context,
        onQuick: (List<CoreHome>) -> Unit = {},
        onProgress: (List<CoreHome>) -> Unit = {},
    ): ArrayList<CoreHome> = synchronized(scanGate) {
        with(context) {
            val hiddenKeys = hiddenIconsDB.getHiddenIcons().map { it.getIconIdentifier() }
            val resolved = queryLaunchers(this)

            val fingerprint = fingerprintOf(resolved, hiddenKeys)
            val previous = BadgeStash.launchers
            if (previous.isNotEmpty() && fingerprint == BadgeStash.installedSignature) {
                return@synchronized ArrayList(previous)
            }

            val startedAt = SystemClock.elapsedRealtime()
            val visible = keepVisible(this, resolved, hiddenKeys)

            val labelOf = HashMap<ResolveInfo, String>(visible.size * 2)
            for (entry in visible) labelOf[entry] = entry.loadLabel(packageManager).toString()
            val alphabetical = visible.sortedBy { labelOf[it]!!.lowercase() }

            if (previous.isEmpty()) {
                onQuick(ArrayList(alphabetical.map { labelOnly(it, labelOf[it]!!) }))
            }

            val apkDirs = visible.associate { componentKey(it) to it.activityInfo.applicationInfo.sourceDir.orEmpty() }
            val priorDirs = BadgeStash.sourceDirs
            val keepable = previous
                .filter { it.drawable != null }
                .associateBy { "${it.packageName}/${it.activityName}" }
                .filterKeys { key -> priorDirs[key].let { it != null && it == apkDirs[key] } }

            val trayMode = launcherConfig.legacyIconTray
            BadgePuller.alignTray(trayMode)
            BadgePuller.invalidate(apkDirs.keys.filter { key -> priorDirs[key].let { it != null && it != apkDirs[key] } })

            val edgePx = resources.getDimensionPixelSize(R.dimen.launcher_icon_cache_size)
            val collected = ArrayList<CoreHome>()
            val workers = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(2, 6))
            try {
                val pending = alphabetical.map { entry ->
                    val label = labelOf[entry]!!
                    val kept = keepable[componentKey(entry)]
                    if (kept != null) {
                        val title = label.takeIf { it.isNotBlank() && it != kept.packageName } ?: kept.title
                        CompletableFuture.completedFuture<CoreHome?>(kept.copy(title = title))
                    } else {
                        workers.submit<CoreHome?> { render(this, entry, label, edgePx, trayMode) }
                    }
                }

                val streaming = previous.all { it.drawable == null }
                val restoredRows = previous.associateBy { "${it.packageName}/${it.activityName}" }
                var lastHandoff = SystemClock.elapsedRealtime()
                val lastIndex = pending.size - 1
                for ((index, task) in pending.withIndex()) {
                    runCatching { task.get() }.getOrNull()?.let { collected.add(it) }
                    val tick = SystemClock.elapsedRealtime()
                    if (!streaming || index >= lastIndex || tick - lastHandoff < HANDOFF_GAP_MS) continue
                    lastHandoff = tick
                    val remainder = alphabetical.subList(index + 1, alphabetical.size)
                        .map { entry -> restoredRows[componentKey(entry)] ?: labelOnly(entry, labelOf[entry]!!) }
                    onProgress(ArrayList(collected) + remainder)
                }
            } finally {
                workers.shutdown()
            }

            Timber.d("HomeSurvey: ${collected.size} launchers, ${collected.size - keepable.size} icons decoded in ${SystemClock.elapsedRealtime() - startedAt} ms")
            launchersDB.insertAll(collected)
            // Signature goes last so a failure above forces a retry on the next resume.
            BadgeStash.installedSignature = fingerprint
            BadgeStash.sourceDirs = apkDirs
            collected
        }
    }

    private fun componentKey(entry: ResolveInfo) =
        "${entry.activityInfo.applicationInfo.packageName}/${entry.activityInfo.name}"

    @SuppressLint("WrongConstant")
    private fun queryLaunchers(context: Context): List<ResolveInfo> =
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.PERMISSION_GRANTED
        )

    private fun keepVisible(context: Context, entries: List<ResolveInfo>, hiddenKeys: List<String>): List<ResolveInfo> {
        val selfPackage = context.applicationContext.packageName
        val hideSelf = context.launcherConfig.selfIconHidden
        return entries.filterNot { entry ->
            val pkg = entry.activityInfo.applicationInfo.packageName
            when {
                pkg == GMS_PACKAGE -> true
                pkg == selfPackage && hideSelf -> true
                else -> hiddenKeys.contains("$pkg/${entry.activityInfo.name}")
            }
        }
    }

    private fun labelOnly(entry: ResolveInfo, label: String) = CoreHome(
        id = null,
        title = label,
        packageName = entry.activityInfo.applicationInfo.packageName,
        activityName = entry.activityInfo.name,
        order = 0,
        thumbnailColor = 0,
        drawable = null
    )

    private fun render(context: Context, entry: ResolveInfo, label: String, edgePx: Int, tray: BadgeMolder.Tray): CoreHome? {
        val pkg = entry.activityInfo.applicationInfo.packageName
        val activity = entry.activityInfo.name

        val memo = BadgePuller.cached(pkg, activity)
        if (memo != null) {
            val tint = (memo as? BitmapDrawable)?.bitmap?.let { sampledTint(it) } ?: 0
            return CoreHome(
                id = null, title = label, packageName = pkg, activityName = activity, order = 0,
                thumbnailColor = tint, drawable = memo,
            )
        }

        val source = entry.loadIcon(context.packageManager)?.let { BadgeMolder.shape(it, tray) }
            ?: context.getDrawableForPackageName(pkg)
            ?: return null
        val raster = source.toBitmap(
            width = source.intrinsicWidth.coerceIn(1, edgePx),
            height = source.intrinsicHeight.coerceIn(1, edgePx),
            config = Bitmap.Config.ARGB_8888
        )
        val icon = raster.toDrawable(context.resources)
        BadgePuller.put(pkg, activity, icon)
        return CoreHome(
            id = null,
            title = label,
            packageName = pkg,
            activityName = activity,
            order = 0,
            thumbnailColor = sampledTint(raster),
            drawable = icon
        )
    }

    /** Sorted component/APK-path lines plus the hidden set, compared on every resume. */
    private fun fingerprintOf(entries: List<ResolveInfo>, hiddenKeys: List<String>): String {
        val installedPart = entries
            .map { "${it.activityInfo.packageName}/${it.activityInfo.name}/${it.activityInfo.applicationInfo.sourceDir}" }
            .sorted()
            .joinToString("\n")
        val hiddenPart = hiddenKeys.sorted().joinToString("\n")
        return listOf(installedPart, hiddenPart).joinToString("\u0000")
    }

    /** Placeholder tint: mean colour over a coarse grid of at most [TINT_GRID]² samples. */
    private fun sampledTint(bitmap: Bitmap): Int {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return Color.TRANSPARENT

        val dx = max(1, w / TINT_GRID)
        val dy = max(1, h / TINT_GRID)
        val line = IntArray(w)
        val sums = LongArray(3)
        var samples = 0
        for (y in 0 until h step dy) {
            bitmap.getPixels(line, 0, w, 0, y, w, 1)
            for (x in 0 until w step dx) {
                val px = line[x]
                sums[0] += Color.red(px).toLong()
                sums[1] += Color.green(px).toLong()
                sums[2] += Color.blue(px).toLong()
                samples++
            }
        }
        if (samples == 0) return Color.TRANSPARENT
        return Color.rgb((sums[0] / samples).toInt(), (sums[1] / samples).toInt(), (sums[2] / samples).toInt())
    }
}
