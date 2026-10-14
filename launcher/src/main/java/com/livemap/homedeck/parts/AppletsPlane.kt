package com.livemap.homedeck.parts

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.max
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.ensureBackgroundThread
import com.livemap.homedeck.R
import com.livemap.homedeck.screens.HomeDeck
import com.livemap.homedeck.binders.AppletsFeed
import com.livemap.homedeck.databinding.HdWidgetsFragmentBinding
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.ext.getInitialCellSize
import com.livemap.homedeck.ext.setupDrawerBackground
import com.livemap.homedeck.support.ITEM_TYPE_SHORTCUT
import com.livemap.homedeck.support.ITEM_TYPE_WIDGET
import com.livemap.homedeck.support.PSEUDO_WIDGET_CLOCK
import com.livemap.homedeck.support.PSEUDO_WIDGET_PREFIX
import com.livemap.homedeck.support.PSEUDO_WIDGET_SEARCH
import com.livemap.homedeck.ports.AppletsFragmentObserver
import com.livemap.homedeck.entities.CoreApplet
import com.livemap.homedeck.entities.MainDisplayLatticeElement
import com.livemap.homedeck.entities.AppletsListElement
import com.livemap.homedeck.entities.AppletsListElementsKeeper
import com.livemap.homedeck.entities.AppletsListSegment

class AppletsPlane(context: Context, attributeSet: AttributeSet) :
    HomePlane<HdWidgetsFragmentBinding>(context, attributeSet), AppletsFragmentObserver {
    private val dragSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var lastPointer = Pair(0f, 0f)
    private var catalog = emptyList<CoreApplet>()
    var touchDownY = -1
    var ignoreTouches = false

    @SuppressLint("ClickableViewAccessibility")
    override fun setupFragment(activity: HomeDeck) {
        this.activity = activity
        this.binding = HdWidgetsFragmentBinding.bind(this)
        getAppWidgets()

        binding.lvWidgetsListUi.setOnTouchListener { _, event ->
            val tappedWhileSearching = event.actionMasked == MotionEvent.ACTION_DOWN && binding.huntBarUi.hasFocus()
            if (tappedWhileSearching) {
                binding.huntBarUi.binding.topToolbarSearch.clearFocus()
                activity?.hideKeyboard()
            }
            false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setupDrawerBackground()
    }

    fun onConfigurationChanged() {
        binding.lvWidgetsListUi.scrollToPosition(0)
        setupViews()
        if (catalog.isEmpty()) getAppWidgets() else rebuildSections()
    }

    override fun onInterceptTouchEvent(event: MotionEvent?): Boolean {
        event ?: return super.onInterceptTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownY = event.y.toInt()
                lastPointer = Pair(event.x, event.y)
                return false
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchDownY = -1
                return false
            }
        }

        val moved = lastPointer.first != event.x || lastPointer.second != event.y
        if (ignoreTouches && moved) {
            touchDownY = -1
            return true
        }

        lastPointer = Pair(event.x, event.y)
        if (touchDownY == -1) return false

        val pullingDown = event.y.toInt() - touchDownY > dragSlop &&
            binding.lvWidgetsListUi.computeVerticalScrollOffset() == 0
        if (!pullingDown) return false
        if (binding.huntBarUi.hasFocus()) activity?.hideKeyboard()
        activity?.beginHandlingTouches(touchDownY)
        touchDownY = -1
        return true
    }

    private fun boundedPreview(info: AppWidgetProviderInfo, maxSide: Int): Drawable? {
        val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.previewImage != 0) {
            runCatching {
                val providerRes = context.packageManager.getResourcesForApplication(info.provider.packageName)
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(providerRes, info.previewImage)) { decoder, image, _ ->

                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val w = image.size.width
                    val h = image.size.height
                    val longest = max(w, h)
                    if (longest > maxSide) {
                        val factor = maxSide.toFloat() / longest
                        decoder.setTargetSize(
                            (w * factor).toInt().coerceAtLeast(1),
                            (h * factor).toInt().coerceAtLeast(1),
                        )
                    }
                }
            }.getOrNull()
        } else {
            null
        }
        return decoded ?: info.loadPreviewImage(context, resources.displayMetrics.densityDpi)
    }

    fun hasWidgets() = binding.lvWidgetsListUi.adapter != null

    @SuppressLint("WrongConstant")
    fun getAppWidgets() {
        ensureBackgroundThread {
            val pm = context.packageManager
            val collected = ArrayList<CoreApplet>()
            collected += builtInApplets()
            collected += providerApplets(pm)
            collected += shortcutApplets(pm)

            val order = compareBy<CoreApplet> { it.appTitle }
                .thenBy { it.appPackageName }
                .thenBy { it.widgetTitleUi }
            catalog = collected.sortedWith(order).toMutableList() as ArrayList<CoreApplet>
            activity?.runOnUiThread { rebuildSections() }
        }
    }

    private fun providerApplets(pm: PackageManager): List<CoreApplet> {
        val previewCap = resources.getDimensionPixelSize(R.dimen.launcher_widget_preview_size) * PREVIEW_OVERSAMPLE
        val providers = AppWidgetManager.getInstance(context).installedProviders
        return providers.mapNotNull { info ->
            val owner = info.provider.packageName
            val header = appHeaderFor(owner) ?: return@mapNotNull null
            val label = info.loadLabel(pm)
            val preview = boundedPreview(info, previewCap) ?: header.appIcon
            val span = context.getInitialCellSize(info, info.minWidth, info.minHeight)
            CoreApplet(
                appPackageName = owner,
                appTitle = header.appTitle,
                appIcon = header.appIcon,
                widgetTitleUi = label,
                widgetPreviewImage = preview,
                widthCells = span.width,
                heightCells = span.height,
                isShortcut = false,
                className = info.provider.className,
                providerInfo = info,
                activityInfo = null
            )
        }
    }

    @SuppressLint("WrongConstant")
    private fun shortcutApplets(pm: PackageManager): List<CoreApplet> {
        val creators = pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT, null), PackageManager.PERMISSION_GRANTED)
        return creators.mapNotNull { resolved ->
            val appInfo = resolved.activityInfo.applicationInfo
            val appTitle = appInfo.loadLabel(pm).toString()
            val owner = appInfo.packageName
            val header = appHeaderFor(owner) ?: return@mapNotNull null
            val label = resolved.loadLabel(pm).toString()
            val preview = pm.getDrawable(appInfo.packageName, resolved.iconResource, appInfo)
            CoreApplet(
                appPackageName = owner,
                appTitle = appTitle,
                appIcon = header.appIcon,
                widgetTitleUi = label,
                widgetPreviewImage = preview,
                widthCells = 0,
                heightCells = 0,
                isShortcut = true,
                className = "",
                providerInfo = null,
                activityInfo = resolved.activityInfo
            )
        }
    }

    private fun builtInApplets(): List<CoreApplet> {
        val self = appHeaderFor(context.packageName) ?: return emptyList()
        val specs = listOf(
            Triple(PSEUDO_WIDGET_CLOCK, R.string.launcher_pseudo_widget_clock, 4 to 2),
            Triple(PSEUDO_WIDGET_SEARCH, R.string.launcher_pseudo_widget_search_bar, 4 to 1),
        )
        return specs.map { (className, titleRes, cells) ->
            CoreApplet(
                appPackageName = context.packageName,
                appTitle = self.appTitle,
                appIcon = self.appIcon,
                widgetTitleUi = context.getString(titleRes),
                widgetPreviewImage = self.appIcon,
                widthCells = cells.first,
                heightCells = cells.second,
                isShortcut = false,
                className = className,
                providerInfo = null,
                activityInfo = null
            )
        }
    }

    private fun rebuildSections() {
        val query = binding.huntBarUi.getCurrentQuery()
        val visible = when {
            query.isEmpty() -> catalog
            else -> {
                val needle = query.normalizeString()
                catalog.filter { applet ->
                    applet.appTitle.normalizeString().contains(needle, ignoreCase = true) ||
                        applet.widgetTitleUi.normalizeString().contains(needle, ignoreCase = true)
                }
            }
        }

        val rows = ArrayList<AppletsListElement>()
        var bucket = ArrayList<CoreApplet>()
        var bucketPackage = ""
        for (applet in visible) {
            if (applet.appPackageName != bucketPackage) {
                if (rows.isNotEmpty()) {
                    rows.add(AppletsListElementsKeeper(bucket))
                    bucket = ArrayList()
                }
                rows.add(AppletsListSegment(applet.appTitle, applet.appIcon))
                bucketPackage = applet.appPackageName
            }
            bucket.add(applet)
        }
        if (rows.isNotEmpty()) rows.add(AppletsListElementsKeeper(bucket))

        publishRows(rows)
    }

    private fun publishRows(rows: ArrayList<AppletsListElement>) {
        activity?.runOnUiThread {
            val existing = binding.lvWidgetsListUi.adapter
            if (existing != null) {
                (existing as AppletsFeed).reviseItems(rows)
                return@runOnUiThread
            }
            binding.lvWidgetsListUi.adapter = AppletsFeed(activity!!, rows, this) {
                context.toast(R.string.launcher_touch_hold_widget)
                ignoreTouches = false
                touchDownY = -1
            }
        }
    }

    fun setupViews() {
        activity ?: return

        binding.lvWidgetsFastscrollerUi.updateColors(context.getProperPrimaryColor())
        (binding.lvWidgetsListUi.adapter as? AppletsFeed)?.reviseTextColor(context.getProperTextColor())
        setupDrawerBackground()

        with(binding.huntBarUi) {
            requireToolbar().beGone()
            updateColors()
            setupMenu()
            onSearchTextChangedListener = { rebuildSections() }
        }
    }

    private fun appHeaderFor(packageName: String): AppletsListSegment? = try {
        val pm = activity!!.packageManager
        val label = pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val badged = launcherApps.getActivityList(packageName, Process.myUserHandle()).firstOrNull()?.getBadgedIcon(0)
        val icon = badged ?: context.packageManager.getApplicationIcon(packageName)
        if (label.isEmpty()) null else AppletsListSegment(label, icon)
    } catch (ignored: Exception) {
        null
    } catch (error: Error) {
        null
    }

    override fun onWidgetLongPressed(appWidget: CoreApplet) {
        val cfg = context.launcherConfig
        val tooBig = appWidget.heightCells > cfg.homeRowCount - 1 || appWidget.widthCells > cfg.homeColumnCount
        if (tooBig) {
            context.showErrorToast(context.getString(R.string.launcher_widget_too_big))
            return
        }

        val title = appWidget.widgetTitleUi.takeIf { appWidget.className.startsWith(PSEUDO_WIDGET_PREFIX) } ?: ""
        val gridItem = MainDisplayLatticeElement(
            id = null,
            left = -1,
            top = -1,
            right = -1,
            bottom = -1,
            page = 0,
            packageName = appWidget.appPackageName,
            activityName = "",
            title = title,
            type = if (appWidget.isShortcut) ITEM_TYPE_SHORTCUT else ITEM_TYPE_WIDGET,
            className = appWidget.className,
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = false,
            parentId = null,
            drawable = appWidget.widgetPreviewImage,
            providerInfo = appWidget.providerInfo,
            activityInfo = appWidget.activityInfo,
            widthCells = appWidget.widthCells,
            heightCells = appWidget.heightCells
        )

        activity?.widgetLongPressedOnList(gridItem)
        ignoreTouches = true
    }

    private companion object {
        const val PREVIEW_OVERSAMPLE = 2
    }
}
