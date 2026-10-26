package com.livemap.homedeck.screens

import timber.log.Timber
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.app.WallpaperManager.OnColorsChangedListener
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.util.Log
import android.util.Property
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.GestureDetector
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.viewbinding.ViewBinding
import kotlinx.collections.immutable.toImmutableList
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.insetsController
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.extensions.showKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.DARK_GREY
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_READ_SMS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isOreoMr1Plus
import com.livemap.homedeck.R
import com.livemap.homedeck.databinding.HdActivityMainBinding
import com.livemap.homedeck.databinding.HdAllAppsFragmentBinding
import com.livemap.homedeck.databinding.HdWidgetsFragmentBinding
import com.livemap.homedeck.contract.HomeBook
import com.livemap.homedeck.anim.HomeRibbonMovement
import com.livemap.homedeck.modals.RetitleElementBasket
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.ext.getLabel
import com.livemap.homedeck.ext.processGridItemPopupMenu
import com.livemap.homedeck.modals.ElementMovesBasket
import com.livemap.homedeck.ext.hiddenIconsDB
import com.livemap.homedeck.ext.homeScreenGridItemsDB
import com.livemap.homedeck.ext.isDefaultLauncher
import com.livemap.homedeck.ext.fireApp
import com.livemap.homedeck.ext.fireAppInfo
import com.livemap.homedeck.ext.launchersDB
import com.livemap.homedeck.ext.supportsDarkText
import com.livemap.homedeck.ext.uninstallApp
import com.livemap.homedeck.parts.HomePlane
import com.livemap.homedeck.support.PreferredHomeSeeker
import com.livemap.homedeck.support.ITEM_TYPE_FOLDER
import com.livemap.homedeck.support.ITEM_TYPE_ICON
import com.livemap.homedeck.support.ITEM_TYPE_SHORTCUT
import com.livemap.homedeck.support.ITEM_TYPE_WIDGET
import com.livemap.homedeck.support.BadgeStash
import com.livemap.homedeck.support.HomeSurvey
import com.livemap.homedeck.support.PSEUDO_WIDGET_SEARCH
import com.livemap.homedeck.support.REQUEST_ALLOW_BINDING_WIDGET
import com.livemap.homedeck.support.REQUEST_CONFIGURE_WIDGET
import com.livemap.homedeck.support.REQUEST_CREATE_SHORTCUT
import com.livemap.homedeck.support.REQUEST_DEFAULT_SMS
import com.livemap.homedeck.support.PACKAGE_REFRESH_DELAY_MS
import com.livemap.homedeck.support.UNINSTALL_WATCH_MS
import com.livemap.homedeck.support.UNINSTALL_WATCH_STEP_MS
import com.livemap.homedeck.support.UNINSTALL_APP_REQUEST_CODE
import com.livemap.homedeck.support.AutoErasure
import com.livemap.homedeck.teaser.HomeSpotsProfile
import com.livemap.homedeck.teaser.HomeMentorPhase
import com.livemap.homedeck.teaser.HomeTeaserDirector
import com.livemap.homedeck.ports.TossObserver
import com.livemap.homedeck.preset.MainProgramsStuffer
import com.livemap.homedeck.preset.MainSower
import com.livemap.homedeck.preset.MainAppletSower
import com.livemap.homedeck.preset.MainPresetEnforcer
import com.livemap.homedeck.preset.HomeThumbprint
import com.livemap.homedeck.ports.ElementOptionsObserver
import com.livemap.homedeck.entities.CoreHome
import com.livemap.homedeck.entities.StashedBadge
import com.livemap.homedeck.entities.MainDisplayLatticeElement
import com.livemap.homedeck.catchers.LatchGadgetStewardCatcher
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

class HomeDeck : HomeBaseDeck(), TossObserver {
    private var mTouchDownX = -1
    private var mTouchDownY = -1
    private var mAllAppsFragmentY = 0
    private var mWidgetsFragmentY = 0
    private var mScreenHeight = 0
    private var mScreenWidth = 0
    private var mMoveGestureThreshold = 0
    private var mIgnoreUpEvent = false
    private var mIgnoreMoveEvents = false
    private var mIgnoreXMoveEvents = false
    private var mIgnoreYMoveEvents = false

    private var mGestureDownX = 0f
    private var mGestureMinX = 0f
    private var mGestureMaxX = 0f
    private val mTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    private val mQuickSwipeSpeed by lazy { 0.4f * resources.displayMetrics.density }

    private var mDrawerEnabled = true
    private var mLongPressedIcon: MainDisplayLatticeElement? = null
    private var mOpenPopupMenu: ElementMovesBasket? = null
    private var mLastTouchCoords = Pair(-1f, -1f)
    private var mActionOnCanBindWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnWidgetConfiguredWidget: ((granted: Boolean) -> Unit)? = null
    private var mActionOnAddShortcut:
            ((shortcutId: String, label: String, icon: Drawable) -> Unit)? = null
    private var mActionOnDefaultSmsResult: (() -> Unit)? = null
    private var wasJustPaused: Boolean = false

    private var mSwipeHintAnimator: ObjectAnimator? = null

    private var needsDefaultLauncherBanner = false

    private var mDefaultLauncherPromptEnabled = true

    private var mOpenSidePanel: View? = null

    private fun shutDisabledPanels() {
        if (isLeftPanelExpanded() && !HomeSpotsProfile.panelEnabled(HomeSpotsProfile.LEFT_SWIPE)) {
            concealLeftPanel()
        }
        if (isHostPanelExpanded() && !HomeSpotsProfile.panelEnabled(HomeSpotsProfile.RIGHT_SWIPE)) {
            concealHostPanel()
        }
    }

    private fun onConfigUpdated() {
        if (isFinishing || isDestroyed) {
            return
        }

        renewDefaultLauncherBanner()
        shutDisabledPanels()

        if (!isAllAppsFragmentExpanded() && BadgeStash.launchers.isNotEmpty()) {
            binding.everyAppsFragmentUi.root.gotLaunchers(BadgeStash.launchers, force = true)
        }

    }

    private val defaultLauncherRequester by lazy {
        PreferredHomeSeeker(this) { renewDefaultLauncherBanner() }
    }

    private var wallpaperColorChangeListener: OnColorsChangedListener? = null
    private var wallpaperSupportsDarkText: Boolean? = null

    private lateinit var mDetector: GestureDetectorCompat
    private val binding by viewBinding(HdActivityMainBinding::inflate)

    companion object {
        const val EXTRA_OPEN_HOST_PANEL = "open_host_panel"

        private const val DOCK_HOST_SLOT = 1
        private const val PLAY_STORE_PACKAGE = "com.android.vending"

        private var mLastUpEvent = 0L
        private const val ANIMATION_DURATION = 150L
        private const val APP_DRAWER_CLOSE_DELAY = 300L
        private const val APP_DRAWER_STATE = "app_drawer_state"
        private const val HOST_PANEL_STATE = "host_panel_state"
        private const val SWIPE_HINT_ANIMATION_DURATION = 900L

        private const val PERMISSION_SHEET_DELAY = 500L

        private const val HOST_PANEL_WARM_UP_DELAY = 250L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useDynamicTheme = false

        super.onCreate(savedInstanceState)

        if (!HomeBook.bridge.onLauncherStart(this)) {
            return
        }

        MainPresetEnforcer.enactStyle(this)
        MainPresetEnforcer.renewIconSizes(this)
        mDrawerEnabled = launcherConfig.isDrawerEnabled

        setContentView(binding.root)

        getSystemService(LauncherApps::class.java)?.registerCallback(packageCallback, Handler(Looper.getMainLooper()))
        appLaunched(packageName)
        setupEdgeToEdge(
            padTopSystem = listOf(
                binding.everyAppsFragmentUi.root,
                binding.lvWidgetsFragmentUi.root,
                binding.lvLeftPanelUi.root,

                binding.prefLauncherBannerUi.root
            ),
            padBottomImeAndSystem = listOf(
                binding.everyAppsFragmentUi.everyAppsGridUi,
                binding.everyAppsFragmentUi.everyAppsPageDotsUi,
                binding.lvWidgetsFragmentUi.lvWidgetsListUi,
                binding.lvLeftPanelUi.deckScrollUi,

                binding.lvHostPanelUi.lvHostPanelContainerUi
            ),

            padBottomSystem = listOf(
                binding.deskScreenGridUi.root,
                binding.lvLeftPanelUi.deckNativeFrameUi
            )
        )

        mDetector = GestureDetectorCompat(this, MyGestureListener(this))

        mScreenHeight = realScreenSize.y
        mScreenWidth = realScreenSize.x
        mAllAppsFragmentY = mScreenHeight
        mWidgetsFragmentY = mScreenHeight
        mMoveGestureThreshold = resources.getDimensionPixelSize(R.dimen.launcher_move_gesture_threshold)

        arrayOf(
            binding.everyAppsFragmentUi.root as HomePlane<*>,
            binding.lvWidgetsFragmentUi.root as HomePlane<*>
        ).forEach { fragment ->
            fragment.setupFragment(this)
            fragment.y = mScreenHeight.toFloat()
            fragment.beVisible()
        }

        binding.lvLeftPanelUi.root.apply {
            setupFragment(this@HomeDeck)
            x = mScreenWidth.toFloat()
            beVisible()
        }

        binding.lvHostPanelUi.root.apply {
            setupFragment(this@HomeDeck)
            x = -mScreenWidth.toFloat()
            beVisible()
        }

        processIntentAction(intent)

        binding.deskScreenGridUi.root.itemClickListener = {
            executeItemClick(it)
        }

        binding.deskScreenGridUi.root.itemLongClickListener = {
            executeItemLongClick(
                x = binding.deskScreenGridUi.root.getClickableRect(it).left.toFloat(),
                clickedGridItem = it
            )
        }

        setupDefaultLauncherBanner(savedInstanceState)
        setupWallpaperColorListener()

        if (savedInstanceState == null) {
            enterHostPanelIfAsked(intent)
        } else if (savedInstanceState.getBoolean(HOST_PANEL_STATE)) {
            restoreHostPanelInstantly()
        }

        binding.coreHolderUi.post { perhapsShowGuide() }

        binding.coreHolderUi.postDelayed({
            Looper.myQueue().addIdleHandler {
                if (!isFinishing && !isDestroyed &&
                    HomeSpotsProfile.panelEnabled(HomeSpotsProfile.RIGHT_SWIPE)
                ) binding.lvHostPanelUi.root.warmUp()
                false
            }
        }, HOST_PANEL_WARM_UP_DELAY)
    }

    private fun setupDefaultLauncherBanner(savedInstanceState: Bundle?) {
        defaultLauncherRequester.recoverState(savedInstanceState)
        binding.prefLauncherBannerUi.root.setOnClickListener {
            HomeBook.bridge.onEvent("default_home_card_tap")
            defaultLauncherRequester.start()
        }

        binding.deskScreenGridUi.root.onFirstPageScrolled = { x ->
            binding.prefLauncherBannerUi.root.translationX = x
        }
        HomeRibbonMovement.bind(binding.prefLauncherBannerUi)

        binding.prefLauncherBannerUi.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            alignGridTopReserve()
        }
        renewDefaultLauncherBanner()
    }

    private fun alignGridTopReserve() {
        val banner = binding.prefLauncherBannerUi.root
        val wanted = needsDefaultLauncherBanner && mDefaultLauncherPromptEnabled
        val grid = binding.deskScreenGridUi.root
        grid.topReserve = when {
            !wanted -> 0

            banner.height > 0 -> (banner.height - banner.paddingTop).coerceAtLeast(0)
            else -> grid.topReserve
        }
    }

    private fun renewDefaultLauncherBanner() {
        val needs = !isDefaultLauncher()

        if (needsDefaultLauncherBanner && !needs) HomeBook.bridge.onEvent("default_home_granted_later")
        needsDefaultLauncherBanner = needs

        mDefaultLauncherPromptEnabled = HomeSpotsProfile.defaultLauncherPromptEnabled()
        reviseDefaultLauncherBannerVisibility()
        alignGridTopReserve()
    }

    private fun reviseDefaultLauncherBannerVisibility() {
        binding.prefLauncherBannerUi.root.beVisibleIf(
            needsDefaultLauncherBanner &&
                mDefaultLauncherPromptEnabled &&
                !isAllAppsFragmentExpanded() &&
                !isWidgetsFragmentExpanded() &&
                mOpenSidePanel == null &&
                !binding.swpHintUi.isVisible
        )
    }

    private var mGuideStep: HomeMentorPhase? = null

    private fun nextGuideStep(): HomeMentorPhase? = HomeMentorPhase.entries.firstOrNull {
        HomeSpotsProfile.guideStepEnabled(it) && !launcherConfig.isGuideStepDone(it) &&

                (it != HomeMentorPhase.SWIPE_UP_DRAWER || mDrawerEnabled)
    }

    private fun perhapsShowGuide() {
        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) return
        if (isLeftPanelExpanded() || isHostPanelExpanded()) return

        val step = nextGuideStep()
        if (step == null) {
            concealSwipeHint()
        } else if (mGuideStep != step || !binding.swpHintUi.isVisible) {
            presentGuideStep(step)
        }

        reviseDefaultLauncherBannerVisibility()
    }

    private fun presentGuideStep(step: HomeMentorPhase) {
        abortGuideAnimation()
        mGuideStep = step

        val chevrons = listOf(
            binding.swpHintChevron1Ui,
            binding.swpHintChevron2Ui,
            binding.swpHintChevron3Ui,
        )
        val travel = resources.getDimension(R.dimen.launcher_swipe_hint_travel)

        val icon: Int
        val label: Int
        val property: Property<View, Float>
        val distance: Float
        val vertical: Boolean
        when (step) {
            HomeMentorPhase.SWIPE_RIGHT_HOST -> {
                icon = org.fossify.commons.R.drawable.ic_chevron_right_vector
                label = R.string.launcher_swipe_right_hint
                property = View.TRANSLATION_X
                distance = travel
                vertical = false
            }

            HomeMentorPhase.SWIPE_LEFT_APPS -> {
                icon = org.fossify.commons.R.drawable.ic_chevron_left_vector
                label = R.string.launcher_swipe_left_hint
                property = View.TRANSLATION_X
                distance = -travel
                vertical = false
            }

            HomeMentorPhase.SWIPE_UP_DRAWER -> {
                icon = org.fossify.commons.R.drawable.ic_chevron_up_vector
                label = R.string.launcher_swipe_up_hint
                property = View.TRANSLATION_Y
                distance = -travel
                vertical = true
            }
        }

        binding.swpHintChevronsUi.orientation =
            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        val alphas = if (distance < 0f) listOf(1f, 0.6f, 0.3f) else listOf(0.3f, 0.6f, 1f)
        chevrons.forEachIndexed { index, view ->
            view.setImageResource(icon)
            view.alpha = alphas[index]

            view.translationX = 0f
            view.translationY = 0f
        }
        binding.swpHintChevronsUi.translationX = 0f
        binding.swpHintChevronsUi.translationY = 0f
        binding.swpHintLabelUi.setText(label)
        binding.swpHintUi.beVisible()

        mSwipeHintAnimator = ObjectAnimator.ofFloat(
            binding.swpHintChevronsUi,
            property,
            0f,
            distance,
        ).apply {
            duration = SWIPE_HINT_ANIMATION_DURATION
            repeatCount = ValueAnimator.INFINITE

            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun completeGuideStep(step: HomeMentorPhase) {
        if (launcherConfig.isGuideStepDone(step)) return
        launcherConfig.flagGuideStepDone(step)
        if (mGuideStep == step) {
            concealSwipeHint()
        }
    }

    private fun executeGuideStep(): Boolean {
        val step = mGuideStep ?: return false
        if (!binding.swpHintUi.isVisible) return false

        concealSwipeHint()

        when (step) {
            HomeMentorPhase.SWIPE_RIGHT_HOST -> presentHostPanel()
            HomeMentorPhase.SWIPE_LEFT_APPS -> presentLeftPanel()
            HomeMentorPhase.SWIPE_UP_DRAWER -> presentFragment(binding.everyAppsFragmentUi)
        }
        return true
    }

    private fun abortGuideAnimation() {
        mSwipeHintAnimator?.cancel()
        mSwipeHintAnimator = null
    }

    private fun concealSwipeHint() {
        if (!binding.swpHintUi.isVisible) {
            return
        }

        abortGuideAnimation()
        binding.swpHintUi.beGone()
    }

    private fun setupWallpaperColorListener() {
        if (isOreoMr1Plus()) {
            val wallpaperManager = WallpaperManager.getInstance(this)
            wallpaperColorChangeListener = OnColorsChangedListener { colors, which ->
                if (which and WallpaperManager.FLAG_SYSTEM != 0) {
                    wallpaperSupportsDarkText = colors?.wantsDarkText() ?: run {
                        renewWallpaperSupportsDarkText()
                        wallpaperSupportsDarkText
                    }

                    if (currentSurfaceColor() == null) {
                        runOnUiThread {
                            reviseStatusBarIcons()
                        }
                    }
                }
            }
            wallpaperManager.addOnColorsChangedListener(
                wallpaperColorChangeListener!!,
                Handler(Looper.getMainLooper())
            )

            renewWallpaperSupportsDarkText()
        }
    }

    private fun renewWallpaperSupportsDarkText() {
        if (!isOreoMr1Plus()) return
        wallpaperSupportsDarkText = WallpaperManager.getInstance(this)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.wantsDarkText()
    }

    private fun isNightTheme(): Boolean =
        resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun android.app.WallpaperColors.wantsDarkText(): Boolean = supportsDarkText()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        retireItemActions()

        val wasAnyFragmentOpen = isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()
        val wasAnyPanelOpen = isLeftPanelExpanded() || isHostPanelExpanded()
        if (wasJustPaused) {
            if (isAllAppsFragmentExpanded()) {
                concealFragment(binding.everyAppsFragmentUi)
            }
            if (isWidgetsFragmentExpanded()) {
                concealFragment(binding.lvWidgetsFragmentUi)
            }
        } else {
            shutAppDrawer()
            shutWidgetsFragment()
        }

        if (isLeftPanelExpanded()) {
            concealLeftPanel()
        }

        if (isHostPanelExpanded()) {
            concealHostPanel()
        }

        binding.everyAppsFragmentUi.huntBarUi.closeSearch()

        val alreadyOnHome = intent.flags and FLAG_ACTIVITY_BROUGHT_TO_FRONT == 0
        if (alreadyOnHome && !wasAnyFragmentOpen) {
            binding.deskScreenGridUi.root.skipToPage(0)
        }

        if (alreadyOnHome && !wasAnyFragmentOpen && !wasAnyPanelOpen && intent.hasCategory(Intent.CATEGORY_HOME)) {
            HomeTeaserDirector.run(this, HomeSpotsProfile.HOME_PRESS) {}
        }

        processIntentAction(intent)
        enterHostPanelIfAsked(intent)
    }

    private fun enterHostPanelIfAsked(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_OPEN_HOST_PANEL, false)) return
        intent.removeExtra(EXTRA_OPEN_HOST_PANEL)
        if (!HomeSpotsProfile.panelEnabled(HomeSpotsProfile.RIGHT_SWIPE)) return
        binding.coreHolderUi.post {
            if (isFinishing || isDestroyed || isHostPanelExpanded()) return@post
            unveilHostPanel()
        }
    }

    override fun onStart() {
        super.onStart()
        binding.deskScreenGridUi.root.appWidgetHost.startListening()
    }

    override fun onResume() {
        super.onResume()

        if (isWorkspaceCovered()) {
            binding.deskScreenGridUi.root.animate().cancel()
            binding.deskScreenGridUi.root.alpha = 0f
        }

        if (reapplyLauncherStyle()) return
        wasJustPaused = false
        mDrawerEnabled = launcherConfig.isDrawerEnabled

        HomeBook.bridge.onLauncherResume(this)

        defaultLauncherRequester.onResume()
        renewWallpaperSupportsDarkText()

        Handler(Looper.getMainLooper()).postDelayed({
            reviseStatusBarIcons(currentSurfaceColor())

            perhapsShowGuide()
        }, ANIMATION_DURATION)

        with(binding.coreHolderUi) {
            onGlobalLayout {
                binding.everyAppsFragmentUi.root.setupViews()
                binding.lvWidgetsFragmentUi.root.setupViews()
            }
        }

        ensureBackgroundThread {
            if (BadgeStash.launchers.isEmpty()) {
                val hiddenIcons = hiddenIconsDB.getHiddenIcons().map {
                    it.getIconIdentifier()
                }

                BadgeStash.launchers = launchersDB.getAppLaunchers().filter {
                    val showIcon = !hiddenIcons.contains(it.getLauncherIdentifier())
                    if (!showIcon) {
                        try {
                            launchersDB.eraseById(it.id!!)
                        } catch (_: Exception) {
                        }
                    }
                    showIcon
                }.toMutableList() as ArrayList<CoreHome>

                BadgeStash.installedSignature = null
            }

            binding.everyAppsFragmentUi.root.gotLaunchers(BadgeStash.launchers)
            binding.lvLeftPanelUi.root.gotLaunchers(BadgeStash.launchers)
            renewLaunchers()
        }

        renewDefaultLauncherBanner()
        alignHostDockItem()

        binding.deskScreenGridUi.root.resizeGrid(
            newRowCount = launcherConfig.homeRowCount,
            newColumnCount = launcherConfig.homeColumnCount
        )
        binding.deskScreenGridUi.root.updateColors()
        binding.everyAppsFragmentUi.root.onResume()
    }

    override fun onStop() {
        super.onStop()
        try {
            binding.deskScreenGridUi.root.appWidgetHost.stopListening()
        } catch (_: Exception) {
        }

        wasJustPaused = false
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(LauncherApps::class.java)?.unregisterCallback(packageCallback)
        packageRefreshHandler.removeCallbacksAndMessages(null)
        if (isOreoMr1Plus() && wallpaperColorChangeListener != null) {
            WallpaperManager.getInstance(this)
                .removeOnColorsChangedListener(wallpaperColorChangeListener!!)
        }
    }

    override fun onPause() {
        super.onPause()
        wasJustPaused = true

        retireItemActions()
    }

    private fun retireItemActions() {
        mOpenPopupMenu?.dismiss()
        mOpenPopupMenu = null
    }

    override fun onBackPressedCompat(): Boolean {
        return if (isLeftPanelExpanded()) {
            concealLeftPanel()
            true
        } else if (isHostPanelExpanded()) {
            if (!binding.lvHostPanelUi.root.handleBack()) concealHostPanel()
            true
        } else if (isAllAppsFragmentExpanded()) {
            binding.everyAppsFragmentUi.root.rewindSearch()
            concealFragment(binding.everyAppsFragmentUi)
            true
        } else if (isWidgetsFragmentExpanded()) {
            if (binding.lvWidgetsFragmentUi.huntBarUi.isSearchOpen) {
                wipeWidgetsSearch()
            } else {
                concealFragment(binding.lvWidgetsFragmentUi)
            }
            true
        } else if (binding.deskScreenGridUi.lvResizeFrameUi.isVisible) {
            binding.deskScreenGridUi.root.concealResizeLines()
            true
        } else {
            HomeTeaserDirector.run(this, HomeSpotsProfile.HOME_BACK) {}
            true
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)

        when (requestCode) {
            UNINSTALL_APP_REQUEST_CODE -> Unit

            REQUEST_DEFAULT_SMS -> {
                mActionOnDefaultSmsResult?.invoke()
                mActionOnDefaultSmsResult = null
            }

            REQUEST_ALLOW_BINDING_WIDGET -> mActionOnCanBindWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CONFIGURE_WIDGET -> mActionOnWidgetConfiguredWidget?.invoke(resultCode == RESULT_OK)
            REQUEST_CREATE_SHORTCUT -> {
                if (resultCode == RESULT_OK && resultData != null) {
                    val launcherApps =
                        applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                    if (launcherApps.hasShortcutHostPermission()) {
                        val item = launcherApps.getPinItemRequest(resultData)
                        val shortcutInfo = item?.shortcutInfo ?: return
                        if (item.accept()) {
                            val shortcutId = shortcutInfo.id
                            val label = shortcutInfo.getLabel()
                            val icon = launcherApps.getShortcutBadgedIconDrawable(
                                shortcutInfo,
                                resources.displayMetrics.densityDpi
                            )
                            mActionOnAddShortcut?.invoke(shortcutId, label, icon)
                        }
                    }
                }
            }
        }
    }

    private fun quickSwipeDirection(up: MotionEvent): Boolean? {
        val travel = up.x - mGestureDownX
        val duration = (up.eventTime - up.downTime).coerceAtLeast(1L)
        if (abs(travel) <= 2 * mTouchSlop || abs(travel) / duration < mQuickSwipeSpeed) return null
        val retreat = if (travel < 0) up.x - mGestureMinX else mGestureMaxX - up.x
        if (retreat > mTouchSlop) return null
        return travel < 0
    }

    private fun rewindGestureRange(x: Float) {
        mGestureDownX = x
        mGestureMinX = x
        mGestureMaxX = x
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.everyAppsFragmentUi.root.onConfigurationChanged()
        binding.lvWidgetsFragmentUi.root.onConfigurationChanged()

        reviseStatusBarIcons(currentSurfaceColor())
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) {
            return false
        }

        if (mLongPressedIcon != null && event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            mLastUpEvent = System.currentTimeMillis()
        }

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            rewindGestureRange(event.x)
        } else {
            for (i in 0 until event.historySize) {
                mGestureMinX = min(mGestureMinX, event.getHistoricalX(i))
                mGestureMaxX = max(mGestureMaxX, event.getHistoricalX(i))
            }
            mGestureMinX = min(mGestureMinX, event.x)
            mGestureMaxX = max(mGestureMaxX, event.x)
        }

        try {
            mDetector.onTouchEvent(event)
        } catch (e: Exception) {
            Timber.e(e, "HomeDeck: gesture handler failed")
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mTouchDownX = event.x.toInt()
                mTouchDownY = event.y.toInt()
                mAllAppsFragmentY = binding.everyAppsFragmentUi.root.y.toInt()
                mWidgetsFragmentY = binding.lvWidgetsFragmentUi.root.y.toInt()
                mIgnoreUpEvent = false
            }

            MotionEvent.ACTION_MOVE -> {
                val hasFingerMoved = if (mTouchDownX == -1 || mTouchDownY == -1) {
                    mTouchDownX = event.x.toInt()
                    mTouchDownY = event.y.toInt()
                    rewindGestureRange(event.x)
                    false
                } else {
                    hasFingerMoved(event)
                }

                if (mLongPressedIcon != null && (mOpenPopupMenu != null) && hasFingerMoved) {
                    mOpenPopupMenu?.dismiss()
                    mOpenPopupMenu = null
                    binding.deskScreenGridUi.root.itemDraggingStarted(mLongPressedIcon!!)
                    concealFragment(binding.everyAppsFragmentUi)
                }

                if (mLongPressedIcon != null && hasFingerMoved) {
                    binding.deskScreenGridUi.root.draggedItemMoved(event.x.toInt(), event.y.toInt())
                }

                if (hasFingerMoved && !mIgnoreMoveEvents) {
                    concealSwipeHint()

                    val diffY = mTouchDownY - event.y
                    val diffX = mTouchDownX - event.x

                    if (abs(diffY) > abs(diffX) && !mIgnoreYMoveEvents) {
                        mIgnoreXMoveEvents = true
                        if (isWidgetsFragmentExpanded()) {
                            val newY = mWidgetsFragmentY - diffY
                            binding.lvWidgetsFragmentUi.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        } else if (mLongPressedIcon == null && mDrawerEnabled) {
                            val newY = mAllAppsFragmentY - diffY
                            binding.everyAppsFragmentUi.root.y = min(
                                a = max(0f, newY), b = mScreenHeight.toFloat()
                            )
                        }

                        reviseDefaultLauncherBannerVisibility()
                    } else if (abs(diffX) > abs(diffY) && !mIgnoreXMoveEvents) {
                        mIgnoreYMoveEvents = true
                        binding.deskScreenGridUi.root.setSwipeMovement(diffX)
                    }
                }

                mLastTouchCoords = Pair(event.x, event.y)
            }

            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                mTouchDownX = -1
                mTouchDownY = -1
                mIgnoreMoveEvents = false
                mLongPressedIcon = null
                mLastTouchCoords = Pair(-1f, -1f)
                rewindFragmentTouches()
                binding.deskScreenGridUi.root.itemDraggingStopped()

                if (!mIgnoreUpEvent) {
                    if (!mIgnoreYMoveEvents) {
                        if (binding.everyAppsFragmentUi.root.y < mScreenHeight * 0.5) {
                            presentFragment(
                                fragment = binding.everyAppsFragmentUi,
                                renewAds = mAllAppsFragmentY == mScreenHeight
                            )
                        } else if (isAllAppsFragmentExpanded()) {
                            concealFragment(binding.everyAppsFragmentUi)
                        }

                        if (binding.lvWidgetsFragmentUi.root.y < mScreenHeight * 0.5) {
                            presentFragment(binding.lvWidgetsFragmentUi)
                        } else if (isWidgetsFragmentExpanded()) {
                            concealFragment(binding.lvWidgetsFragmentUi)
                        }
                    }

                }

                if (!mIgnoreXMoveEvents) {
                    val grid = binding.deskScreenGridUi.root

                    if (!mIgnoreUpEvent && mIgnoreYMoveEvents && event.actionMasked == MotionEvent.ACTION_UP) {
                        quickSwipeDirection(event)?.let { grid.flingSwipe(towardsNext = it) }
                    }
                    grid.finalizeSwipe()
                }

                mIgnoreXMoveEvents = false
                mIgnoreYMoveEvents = false
            }
        }

        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(APP_DRAWER_STATE, isAllAppsFragmentExpanded())
        outState.putBoolean(HOST_PANEL_STATE, isHostPanelExpanded())
        defaultLauncherRequester.persistState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (savedInstanceState.getBoolean(APP_DRAWER_STATE)) {
            presentFragment(binding.everyAppsFragmentUi, 0L)
        }
    }

    private fun processIntentAction(intent: Intent) {
        if (intent.action == LauncherApps.ACTION_CONFIRM_PIN_SHORTCUT) {
            val launcherApps =
                applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val item = launcherApps.getPinItemRequest(intent)
            val shortcutInfo = item?.shortcutInfo ?: return

            ensureBackgroundThread {
                val shortcutId = shortcutInfo.id
                val label = shortcutInfo.getLabel()
                val icon = launcherApps.getShortcutBadgedIconDrawable(
                    shortcutInfo,
                    resources.displayMetrics.densityDpi
                )
                val (page, rect) = locateFirstEmptyCell()
                val gridItem = MainDisplayLatticeElement(
                    id = null,
                    left = rect.left,
                    top = rect.top,
                    right = rect.right,
                    bottom = rect.bottom,
                    page = page,
                    packageName = shortcutInfo.`package`,
                    activityName = "",
                    title = label,
                    type = ITEM_TYPE_SHORTCUT,
                    className = "",
                    widgetId = -1,
                    shortcutId = shortcutId,
                    icon = icon.toBitmap(),
                    docked = false,
                    parentId = null,
                    drawable = icon
                )

                runOnUiThread {
                    binding.deskScreenGridUi.root.skipToPage(page)
                }

                Thread.sleep(2000)

                try {
                    item.accept()
                    binding.deskScreenGridUi.root.stashAndShowGridItem(gridItem)
                } catch (_: IllegalStateException) {
                }
            }
        }
    }

    private fun locateFirstEmptyCell(): Pair<Int, Rect> {
        val gridItems = homeScreenGridItemsDB.getAllItems() as ArrayList<MainDisplayLatticeElement>
        val maxPage = gridItems.maxOf { it.page }
        val occupiedCells = ArrayList<Triple<Int, Int, Int>>()
        gridItems.toImmutableList().filter { it.parentId == null }.forEach { item ->
            for (xCell in item.left..item.right) {
                for (yCell in item.top..item.bottom) {
                    occupiedCells.add(Triple(item.page, xCell, yCell))
                }
            }
        }

        for (page in 0 until maxPage) {
            for (checkedYCell in 0 until launcherConfig.homeColumnCount) {
                for (checkedXCell in 0 until launcherConfig.homeRowCount - 1) {
                    val wantedCell = Triple(page, checkedXCell, checkedYCell)
                    if (!occupiedCells.contains(wantedCell)) {
                        return Pair(
                            first = page,
                            second = Rect(
                                wantedCell.second,
                                wantedCell.third,
                                wantedCell.second,
                                wantedCell.third
                            )
                        )
                    }
                }
            }
        }

        return Pair(maxPage + 1, Rect(0, 0, 0, 0))
    }

    private fun hasFingerMoved(event: MotionEvent): Boolean {
        return mTouchDownX != -1 && mTouchDownY != -1 &&
                (abs(mTouchDownX - event.x) > mMoveGestureThreshold || abs(mTouchDownY - event.y) > mMoveGestureThreshold)
    }

    private val packageRefreshHandler = Handler(Looper.getMainLooper())

    fun dropSelfFromHome() {
        HomeBook.bridge.onEvent("uninstall_flow_self_hidden", mapOf("trigger" to "shortcut_in_place"))

        launcherConfig.selfIconHidden = true
        ensureBackgroundThread {
            AutoErasure.hide(this)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) binding.deskScreenGridUi.root.obtainGridItems()
            }
        }
    }

    fun watchUninstall(packageName: String) {
        val deadline = android.os.SystemClock.elapsedRealtime() + UNINSTALL_WATCH_MS
        val check = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val gone = runCatching { packageManager.getPackageInfo(packageName, 0) }.isFailure
                when {
                    gone -> {
                        Timber.d("HomeDeck: $packageName uninstalled - refreshing")
                        packageRefreshHandler.removeCallbacks(packageRefresh)
                        packageRefresh.run()
                    }
                    android.os.SystemClock.elapsedRealtime() < deadline ->
                        packageRefreshHandler.postDelayed(this, UNINSTALL_WATCH_STEP_MS)
                }
            }
        }
        packageRefreshHandler.postDelayed(check, UNINSTALL_WATCH_STEP_MS)
    }

    private val packageRefresh = Runnable {
        if (!isFinishing && !isDestroyed) ensureBackgroundThread { renewLaunchers() }
    }

    private val packageCallback = object : LauncherApps.Callback() {
        private fun changed(packageName: String?) {
            Timber.d("HomeDeck: package changed - $packageName")
            packageRefreshHandler.removeCallbacks(packageRefresh)
            packageRefreshHandler.postDelayed(packageRefresh, PACKAGE_REFRESH_DELAY_MS)
        }

        override fun onPackageRemoved(packageName: String?, user: android.os.UserHandle?) = changed(packageName)
        override fun onPackageAdded(packageName: String?, user: android.os.UserHandle?) = changed(packageName)
        override fun onPackageChanged(packageName: String?, user: android.os.UserHandle?) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) =
            changed(packageNames?.joinToString())
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: android.os.UserHandle?, replacing: Boolean) =
            changed(packageNames?.joinToString())
    }

    private val refreshLock = Any()

    private fun isInstalledAndEnabled(packageName: String): Boolean =
        runCatching { packageManager.getApplicationInfo(packageName, 0).enabled }.getOrDefault(false)

    private fun renewLaunchers(): Unit = synchronized(refreshLock) {
        val dockEmpty = homeScreenGridItemsDB.getAllItems().none { it.docked }
        if (!launcherConfig.wasHomeScreenInit || dockEmpty) {
            getDefaultAppPackages()
            launcherConfig.wasHomeScreenInit = true
            launcherConfig.wasSearchBarPurged = true
            binding.deskScreenGridUi.root.obtainGridItems()
        }

        if (!launcherConfig.homeSeeded && !launcherConfig.homeRebuildPending) {
            val names = HomeSurvey.quick(this)
            MainSower.plantFirstPage(this, names)
            if (!launcherConfig.isDrawerEnabled || launcherConfig.homeAndDrawer) {
                MainProgramsStuffer.siteMissing(this, names)
            }
            binding.deskScreenGridUi.root.obtainGridItems()
        }

        val knownSignature = BadgeStash.installedSignature
        val launchers = getAllAppLaunchers()

        val appsChanged = knownSignature == null || knownSignature != BadgeStash.installedSignature
        binding.everyAppsFragmentUi.root.gotLaunchers(launchers)
        binding.lvLeftPanelUi.root.gotLaunchers(launchers)
        if (appsChanged || !binding.lvWidgetsFragmentUi.root.hasWidgets()) {
            binding.lvWidgetsFragmentUi.root.getAppWidgets()
        }

        BadgeStash.launchers.map { it.packageName }.forEach { packageName ->
            if (!launchers.map { it.packageName }.contains(packageName) && !isInstalledAndEnabled(packageName)) {
                launchersDB.eraseApp(packageName)
                homeScreenGridItemsDB.eraseByPackageName(packageName)
            }
        }

        BadgeStash.launchers = launchers

        if (launcherConfig.homeRebuildPending) {
            MainSower.rewindForRebuild(this)
            launcherConfig.homeRebuildPending = false
        }
        MainSower.plantFirstPage(this, launchers)

        if (MainAppletSower.alignFirstPage(this)) {
            runOnUiThread { if (!isFinishing && !isDestroyed) recreate() }
            return
        }
        if (!launcherConfig.isDrawerEnabled || launcherConfig.homeAndDrawer) {
            MainProgramsStuffer.siteMissing(this, launchers)
        }
        MainSower.siteNewInstalls(this, launchers)

        if (!launcherConfig.wasSearchBarPurged) {
            ensureBackgroundThread {
                purgeSeededSearchBarIfNeeded()

                binding.deskScreenGridUi.root.dropPlacedSearchBars()
                binding.deskScreenGridUi.root.obtainGridItems()
            }
        } else {
            binding.deskScreenGridUi.root.obtainGridItems()
        }
    }

    fun reapplyLauncherStyle(): Boolean {
        if (isFinishing || isDestroyed) return false
        if (!MainPresetEnforcer.enactStyle(this)) return false
        recreate()
        return true
    }

    fun isAllAppsFragmentExpanded() = binding.everyAppsFragmentUi.root.y != mScreenHeight.toFloat()

    private fun isWidgetsFragmentExpanded() =
        binding.lvWidgetsFragmentUi.root.y != mScreenHeight.toFloat()

    fun isLeftPanelExpanded() = binding.lvLeftPanelUi.root.x != mScreenWidth.toFloat()

    fun isHostPanelExpanded() = binding.lvHostPanelUi.root.x != -mScreenWidth.toFloat()

    private fun presentLeftPanel() {
        if (!HomeSpotsProfile.panelEnabled(HomeSpotsProfile.LEFT_SWIPE)) {
            Timber.d("HomeDeck: apps panel disabled by launcher_config")
            return
        }
        HomeTeaserDirector.run(this, HomeSpotsProfile.LEFT_SWIPE) {
            binding.lvLeftPanelUi.root.renewAds()
            binding.lvLeftPanelUi.root.onOpened()
            presentSidePanel(binding.lvLeftPanelUi.root)

            completeGuideStep(HomeMentorPhase.SWIPE_LEFT_APPS)
        }
    }

    fun concealLeftPanel() {
        concealSidePanel(binding.lvLeftPanelUi.root, mScreenWidth.toFloat())

        Handler(Looper.getMainLooper()).postDelayed({
            binding.lvLeftPanelUi.root.rewindSearch()
        }, ANIMATION_DURATION)
    }

    fun enterHostApp(fallbackActivity: String = "") {
        if (isAllAppsFragmentExpanded()) shutAppDrawer()
        if (isLeftPanelExpanded()) concealLeftPanel()
        if (HomeSpotsProfile.panelEnabled(HomeSpotsProfile.RIGHT_SWIPE)) {
            presentHostPanel()
        } else {
            fireApp(packageName, hostActivityName().ifEmpty { fallbackActivity })
        }
    }

    private fun presentHostPanel() {
        if (!HomeSpotsProfile.panelEnabled(HomeSpotsProfile.RIGHT_SWIPE)) {
            Timber.d("HomeDeck: messages panel disabled by launcher_config")
            return
        }
        HomeTeaserDirector.run(this, HomeSpotsProfile.RIGHT_SWIPE) {
            unveilHostPanel()
            completeGuideStep(HomeMentorPhase.SWIPE_RIGHT_HOST)
        }
    }

    // After a recreate (e.g. a day/night toggle from the host drawer) put the host panel back
    // before the first frame, so the launcher home never flashes underneath it.
    private fun restoreHostPanelInstantly() {
        if (!HomeSpotsProfile.panelEnabled(HomeSpotsProfile.RIGHT_SWIPE)) return
        val panel = binding.lvHostPanelUi.root
        panel.x = 0f
        mOpenSidePanel = panel
        binding.deskScreenGridUi.root.fragmentExpanded()
        binding.deskScreenGridUi.root.animate().cancel()
        binding.deskScreenGridUi.root.alpha = 0f
        window.navigationBarColor = resources.getColor(R.color.launcher_semitransparent_navigation)
        reviseDefaultLauncherBannerVisibility()
        binding.coreHolderUi.post {
            if (isFinishing || isDestroyed) return@post
            panel.refresh()
            panel.setPanelVisible(true)
            reviseStatusBarIcons(sidePanelBackgroundColor(panel))
        }
    }

    private fun unveilHostPanel() {
        binding.lvHostPanelUi.root.refresh()
        presentSidePanel(binding.lvHostPanelUi.root)

        binding.lvHostPanelUi.root.setPanelVisible(true)
    }

    fun concealHostPanel() {
        binding.lvHostPanelUi.root.setPanelVisible(false)
        concealSidePanel(binding.lvHostPanelUi.root, -mScreenWidth.toFloat())
    }

    fun shutHostPanel() {
        if (isHostPanelExpanded()) {
            concealHostPanel()
        }
    }

    private fun presentSidePanel(panel: View) {
        concealSwipeHint()
        mOpenSidePanel = panel
        tweenSidePanelTo(panel, 0f)

        reviseDefaultLauncherBannerVisibility()
        window.navigationBarColor = resources.getColor(R.color.launcher_semitransparent_navigation)
        binding.deskScreenGridUi.root.fragmentExpanded()
        binding.deskScreenGridUi.root.concealResizeLines()
        binding.deskScreenGridUi.root.animate()
            .alpha(0f)
            .setDuration(ANIMATION_DURATION)
            .start()

        @SuppressLint("AccessibilityFocus")
        panel.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        reviseStatusBarIcons(sidePanelBackgroundColor(panel))
    }

    private fun concealSidePanel(panel: View, parkedX: Float) {
        tweenSidePanelTo(panel, parkedX) {
            mOpenSidePanel = null
            perhapsShowGuide()

            reviseDefaultLauncherBannerVisibility()
        }
        window.navigationBarColor = Color.TRANSPARENT
        binding.deskScreenGridUi.root.fragmentCollapsed()
        reviseStatusBarIcons()
        hideKeyboard()
    }

    private fun tweenSidePanelTo(panel: View, x: Float, onEnd: (() -> Unit)? = null) {
        ObjectAnimator.ofFloat(panel, "x", x).apply {
            duration = ANIMATION_DURATION
            interpolator = DecelerateInterpolator()
            if (onEnd != null) {
                addListener(endListener(onEnd))
            }
            start()
        }
    }

    private fun endListener(action: () -> Unit) = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) = action()
    }

    fun beginHandlingTouches(touchDownY: Int) {
        mLongPressedIcon = null
        mTouchDownY = touchDownY
        mAllAppsFragmentY = binding.everyAppsFragmentUi.root.y.toInt()
        mWidgetsFragmentY = binding.lvWidgetsFragmentUi.root.y.toInt()
        mIgnoreUpEvent = false
    }

    private fun presentFragment(
        fragment: ViewBinding,
        animationDuration: Long = ANIMATION_DURATION,
        renewAds: Boolean = true,
    ) {
        ObjectAnimator.ofFloat(fragment.root, "y", 0f).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()

            addListener(endListener { reviseDefaultLauncherBannerVisibility() })
            start()
        }

        window.navigationBarColor = resources.getColor(R.color.launcher_semitransparent_navigation)
        binding.deskScreenGridUi.root.fragmentExpanded()
        binding.deskScreenGridUi.root.concealResizeLines()

        concealSwipeHint()

        reviseDefaultLauncherBannerVisibility()
        if (fragment is HdAllAppsFragmentBinding) {
            completeGuideStep(HomeMentorPhase.SWIPE_UP_DRAWER)

            if (renewAds) {
                fragment.root.renewAds()
            }
        }

        @SuppressLint("AccessibilityFocus")
        fragment.root.performAccessibilityAction(
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
            null
        )

        if (
            fragment is HdAllAppsFragmentBinding
            && launcherConfig.showSearchBar
            && launcherConfig.autoShowKeyboardInAppDrawer
        ) {
            fragment.root.post {
                showKeyboard(fragment.huntBarUi.binding.topToolbarSearch)
            }
        }

        if (animationDuration == 0L) {
            binding.deskScreenGridUi.root.animate().cancel()
            binding.deskScreenGridUi.root.alpha = 0f
        } else {
            binding.deskScreenGridUi.root.animate()
                .alpha(0f)
                .setDuration(animationDuration)
                .start()
        }

        reviseStatusBarIcons(sheetBackgroundColor(fragment))
    }

    private fun concealFragment(fragment: ViewBinding, animationDuration: Long = ANIMATION_DURATION) {
        ObjectAnimator.ofFloat(fragment.root, "y", mScreenHeight.toFloat()).apply {
            duration = animationDuration
            interpolator = DecelerateInterpolator()

            addListener(
                endListener {
                    perhapsShowGuide()
                    reviseDefaultLauncherBannerVisibility()
                }
            )
            start()
        }

        window.navigationBarColor = Color.TRANSPARENT
        binding.deskScreenGridUi.root.fragmentCollapsed()
        reviseStatusBarIcons()

        hideKeyboard()
        if (fragment is HdWidgetsFragmentBinding) {
            wipeWidgetsSearch()
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (fragment is HdAllAppsFragmentBinding) {
                fragment.everyAppsGridUi.scrollToPosition(0)
                fragment.root.rewindToFirstPage()
                fragment.root.touchDownY = -1
            } else if (fragment is HdWidgetsFragmentBinding) {
                fragment.lvWidgetsListUi.scrollToPosition(0)
                fragment.root.touchDownY = -1
            }
        }, animationDuration)
    }

    fun homeScreenLongPressed(eventX: Float, eventY: Float) {
        if (isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded()) {
            return
        }

        concealSwipeHint()

        val (x, y) = binding.deskScreenGridUi.root.intoViewSpaceCoords(eventX, eventY)
        mIgnoreMoveEvents = true
        val clickedGridItem = binding.deskScreenGridUi.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            executeItemLongClick(x, clickedGridItem)
        }

    }

    private fun isWorkspaceCovered() =
        isAllAppsFragmentExpanded() || isWidgetsFragmentExpanded() || isLeftPanelExpanded() || isHostPanelExpanded()

    fun homeScreenClicked(eventX: Float, eventY: Float) {
        if (isWorkspaceCovered()) {
            return
        }

        if (executeGuideStep()) {
            return
        }

        binding.deskScreenGridUi.root.concealResizeLines()
        val (x, y) = binding.deskScreenGridUi.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.deskScreenGridUi.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            executeItemClick(clickedGridItem)
        }
        if (clickedGridItem?.type != ITEM_TYPE_FOLDER) {
            binding.deskScreenGridUi.root.shutFolder(redraw = true)
        }
    }

    fun homeScreenDoubleTapped(eventX: Float, eventY: Float) {
        if (isWorkspaceCovered()) {
            return
        }

        val (x, y) = binding.deskScreenGridUi.root.intoViewSpaceCoords(eventX, eventY)
        val clickedGridItem = binding.deskScreenGridUi.root.isClickingGridItem(x.toInt(), y.toInt())
        if (clickedGridItem != null) {
            return
        }

        val devicePolicyManager =
            getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isLockDeviceAdminActive = devicePolicyManager.isAdminActive(
            ComponentName(this, LatchGadgetStewardCatcher::class.java)
        )
        if (isLockDeviceAdminActive) {
            devicePolicyManager.lockNow()
        }
    }

    fun shutAppDrawer(delayed: Boolean = false) {
        if (isAllAppsFragmentExpanded()) {
            hideKeyboard()
            val close = {
                binding.everyAppsFragmentUi.root.y = mScreenHeight.toFloat()
                binding.everyAppsFragmentUi.everyAppsGridUi.scrollToPosition(0)
                binding.everyAppsFragmentUi.root.rewindToFirstPage()
                binding.everyAppsFragmentUi.root.touchDownY = -1
                binding.deskScreenGridUi.root.fragmentCollapsed()
                reviseStatusBarIcons()

                perhapsShowGuide()
                reviseDefaultLauncherBannerVisibility()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun shutWidgetsFragment(delayed: Boolean = false) {
        if (isWidgetsFragmentExpanded()) {
            val close = {
                binding.lvWidgetsFragmentUi.root.y = mScreenHeight.toFloat()
                binding.lvWidgetsFragmentUi.lvWidgetsListUi.scrollToPosition(0)
                wipeWidgetsSearch()
                binding.lvWidgetsFragmentUi.root.touchDownY = -1
                binding.deskScreenGridUi.root.fragmentCollapsed()
                reviseStatusBarIcons()

                perhapsShowGuide()
                reviseDefaultLauncherBannerVisibility()
            }
            if (delayed) {
                Handler(Looper.getMainLooper()).postDelayed(close, APP_DRAWER_CLOSE_DELAY)
            } else {
                close()
            }
        }
    }

    fun wipeWidgetsSearch() {
        binding.lvWidgetsFragmentUi.huntBarUi.closeSearch()
    }

    private fun executeItemClick(clickedGridItem: MainDisplayLatticeElement) {
        when (clickedGridItem.type) {
            ITEM_TYPE_ICON -> if (clickedGridItem.packageName == packageName) {
                enterHostApp(clickedGridItem.activityName)
            } else {
                fireApp(clickedGridItem.packageName, clickedGridItem.activityName, HomeSpotsProfile.HOME_LAUNCH)
            }
            ITEM_TYPE_FOLDER -> enterFolder(clickedGridItem)
            ITEM_TYPE_SHORTCUT -> {
                val id = clickedGridItem.shortcutId
                val packageName = clickedGridItem.packageName
                val userHandle = android.os.Process.myUserHandle()
                val shortcutBounds = binding.deskScreenGridUi.root.getClickableRect(clickedGridItem)
                val launcherApps =
                    applicationContext.getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
                launcherApps.startShortcut(packageName, id, shortcutBounds, null, userHandle)
            }
        }
    }

    private fun enterFolder(folder: MainDisplayLatticeElement) {
        binding.deskScreenGridUi.root.enterFolder(folder)
    }

    private fun executeItemLongClick(x: Float, clickedGridItem: MainDisplayLatticeElement) {
        if (clickedGridItem.type == ITEM_TYPE_ICON || clickedGridItem.type == ITEM_TYPE_SHORTCUT || clickedGridItem.type == ITEM_TYPE_FOLDER) {
            binding.coreHolderUi.performHapticFeedback()
        }

        val anchorY = binding.deskScreenGridUi.root.sideMargins.top +
                (clickedGridItem.top * binding.deskScreenGridUi.root.cellHeight.toFloat())
        presentHomeIconMenu(x, anchorY, clickedGridItem, false)
    }

    fun presentHomeIconMenu(
        x: Float,
        y: Float,
        gridItem: MainDisplayLatticeElement,
        isOnAllAppsFragment: Boolean,
    ) {
        binding.deskScreenGridUi.root.concealResizeLines()
        mLongPressedIcon = gridItem

        val grid = binding.deskScreenGridUi.root
        val gridOnScreen = IntArray(2).also { grid.getLocationOnScreen(it) }
        val (centerX, itemTop, itemBottom) = when {
            isOnAllAppsFragment -> {
                val tile = realScreenSize.x / launcherConfig.drawerColumnCount.toFloat()
                Triple(x, y, y + tile)
            }
            gridItem.type == ITEM_TYPE_WIDGET -> {
                val top = gridOnScreen[1] + y
                Triple(gridOnScreen[0] + x, top, top + gridItem.getHeightInCells() * grid.cellHeight)
            }
            else -> {
                val rect = grid.getClickableRect(gridItem)
                Triple(gridOnScreen[0] + rect.exactCenterX(), gridOnScreen[1] + rect.top.toFloat(), gridOnScreen[1] + rect.bottom.toFloat())
            }
        }

        if (mOpenPopupMenu == null) {
            mOpenPopupMenu = processGridItemPopupMenu(
                anchorView = binding.root,
                gridItem = gridItem,
                isOnAllAppsFragment = isOnAllAppsFragment,
                listener = menuListener,
                centerX = centerX,
                itemTop = itemTop,
                itemBottom = itemBottom,
            )
        }
    }

    fun widgetLongPressedOnList(gridItem: MainDisplayLatticeElement) {
        mLongPressedIcon = gridItem
        concealFragment(binding.lvWidgetsFragmentUi)
        binding.deskScreenGridUi.root.itemDraggingStarted(mLongPressedIcon!!)
    }

    private fun rewindFragmentTouches() {
        binding.lvWidgetsFragmentUi.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }

        binding.everyAppsFragmentUi.root.apply {
            touchDownY = -1
            ignoreTouches = false
        }
    }

    private fun concealIconUi(item: MainDisplayLatticeElement) {
        ensureBackgroundThread {
            val hiddenIconUi = StashedBadge(null, item.packageName, item.activityName, item.title, null)
            hiddenIconsDB.insert(hiddenIconUi)

            runOnUiThread {
                binding.everyAppsFragmentUi.root.onIconHidden(item)
            }
        }
    }

    private fun renameItem(homeScreenGridItem: MainDisplayLatticeElement) {
        RetitleElementBasket(this, homeScreenGridItem) {
            binding.deskScreenGridUi.root.obtainGridItems()
        }
    }

    val menuListener: ElementOptionsObserver = object : ElementOptionsObserver {
        override fun onAnyClick() {
            rewindFragmentTouches()
        }

        override fun hide(gridItem: MainDisplayLatticeElement) {
            concealIconUi(gridItem)
        }

        override fun rename(gridItem: MainDisplayLatticeElement) {
            renameItem(gridItem)
        }

        override fun resize(gridItem: MainDisplayLatticeElement) {
            binding.deskScreenGridUi.root.widgetLongPressed(gridItem)
        }

        override fun appInfoUi(gridItem: MainDisplayLatticeElement) {
            fireAppInfo(gridItem.packageName)
        }

        override fun remove(gridItem: MainDisplayLatticeElement) {
            binding.deskScreenGridUi.root.dropAppIcon(gridItem)
        }

        override fun uninstall(gridItem: MainDisplayLatticeElement) {
            uninstallApp(gridItem.packageName)
        }

        override fun onDismiss() {
            mOpenPopupMenu = null
            rewindFragmentTouches()
        }
    }

    private class MyGestureListener(
        private val flingListener: TossObserver,
    ) : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            (flingListener as HomeDeck).homeScreenClicked(event.x, event.y)
            return super.onSingleTapUp(event)
        }

        override fun onDoubleTap(event: MotionEvent): Boolean {
            (flingListener as HomeDeck).homeScreenDoubleTapped(event.x, event.y)
            return super.onDoubleTap(event)
        }

        override fun onFling(
            event1: MotionEvent?,
            event2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            if (System.currentTimeMillis() - mLastUpEvent < 500L) {
                return true
            }

            if (abs(velocityY) > abs(velocityX)) {
                if (velocityY > 0) {
                    flingListener.onFlingDown()
                } else {
                    flingListener.onFlingUp()
                }
            } else if (abs(velocityX) > abs(velocityY)) {
                val travelX = if (event1 != null) event2.x - event1.x else velocityX
                val direction = if (travelX != 0f) travelX else velocityX
                if (sign(direction) != sign(velocityX)) {
                    val panel = flingListener as HomeDeck
                    val retreat = if (direction < 0) event2.x - panel.mGestureMinX else panel.mGestureMaxX - event2.x
                    if (retreat > panel.mTouchSlop) {
                        return true
                    }
                }
                if (direction > 0) {
                    flingListener.onFlingRight()
                } else {
                    flingListener.onFlingLeft()
                }
            }

            return true
        }

        override fun onLongPress(event: MotionEvent) {
            (flingListener as HomeDeck).homeScreenLongPressed(event.x, event.y)
        }
    }

    override fun onFlingUp() {
        if (mIgnoreYMoveEvents || !mDrawerEnabled) {
            return
        }

        if (!isWidgetsFragmentExpanded()) {
            mIgnoreUpEvent = true
            presentFragment(binding.everyAppsFragmentUi)
        }
    }

    @SuppressLint("WrongConstant")
    override fun onFlingDown() {
        if (mIgnoreYMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        if (isAllAppsFragmentExpanded()) {
            concealFragment(binding.everyAppsFragmentUi)
        } else if (isWidgetsFragmentExpanded()) {
            concealFragment(binding.lvWidgetsFragmentUi)
        } else {
            try {
                Class.forName("android.app.StatusBarManager")
                    .getMethod("expandNotificationsPanel")
                    .invoke(getSystemService("statusbar"))
            } catch (_: Exception) {
            }
        }
    }

    override fun onFlingRight() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        val grid = binding.deskScreenGridUi.root

        if (grid.flingSwipe(towardsNext = false)) {
            return
        }
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded() && grid.isOnFirstPage()) {
            presentHostPanel()
        } else {
            grid.prevPage(redraw = true)
        }
    }

    override fun onFlingLeft() {
        if (mIgnoreXMoveEvents) {
            return
        }

        mIgnoreUpEvent = true
        val grid = binding.deskScreenGridUi.root

        if (grid.flingSwipe(towardsNext = true)) {
            return
        }
        if (!isAllAppsFragmentExpanded() && !isWidgetsFragmentExpanded() && grid.isOnLastPage()) {
            presentLeftPanel()
        } else {
            grid.nextPage(redraw = true)
        }
    }

    fun getAllAppLaunchers(): ArrayList<CoreHome> = HomeSurvey.scan(
        this,
        onQuick = { quick ->
            binding.everyAppsFragmentUi.root.gotLaunchers(quick)
            binding.lvLeftPanelUi.root.gotLaunchers(quick)
        },
        onProgress = { partial ->
            binding.everyAppsFragmentUi.root.gotLaunchers(partial)
            binding.lvLeftPanelUi.root.gotLaunchers(partial)
        },
    )

    private fun purgeSeededSearchBarIfNeeded() {
        if (launcherConfig.wasSearchBarPurged) {
            return
        }

        launcherConfig.wasSearchBarPurged = true
        try {
            homeScreenGridItemsDB.eraseItemsByClassName(PSEUDO_WIDGET_SEARCH)
        } catch (e: Exception) {
            Log.e("HomeDeck", "Failed to remove the seeded search bar", e)
        }
    }

    private fun hostDockPackage(): String? = HomeBook.bridge.dockSlotPackage(this)

    private fun dockSlotPackage(): String? {
        val wanted = hostDockPackage()
        return if (wanted == packageName && launcherConfig.selfIconHidden) null else wanted
    }

    private fun dockableTitle(packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        return try {
            packageManager.getLaunchIntentForPackage(packageName) ?: return null
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun hostDockItem(packageName: String?): MainDisplayLatticeElement? {
        if (packageName.isNullOrEmpty()) return null
        val ours = packageName == this.packageName
        if (ours && launcherConfig.selfIconHidden) return null
        val title = if (ours) hostLabel() else dockableTitle(packageName) ?: return null
        return MainDisplayLatticeElement(
            id = null,
            left = DOCK_HOST_SLOT,
            top = launcherConfig.homeRowCount - 1,
            right = DOCK_HOST_SLOT,
            bottom = launcherConfig.homeRowCount - 1,
            page = 0,
            packageName = packageName,
            activityName = if (ours) hostActivityName() else "",
            title = title,
            type = ITEM_TYPE_ICON,
            className = "",
            widgetId = -1,
            shortcutId = "",
            icon = null,
            docked = true,
            parentId = null
        )
    }

    private fun alignHostDockItem() {
        if (!launcherConfig.wasHomeScreenInit) return
        val wanted = dockSlotPackage() ?: return

        val placed = launcherConfig.dockHostPackage.ifEmpty { packageName }
        if (wanted == placed && launcherConfig.dockHostPackage.isNotEmpty()) return
        ensureBackgroundThread {
            val items = homeScreenGridItemsDB.getAllItems()
            if (wanted == placed) {
                val slotFilled = items.any { it.docked && it.left == DOCK_HOST_SLOT && it.parentId == null }
                if (!slotFilled) {
                    hostDockItem(wanted)?.let { homeScreenGridItemsDB.insert(it) }
                    binding.deskScreenGridUi.root.obtainGridItems()
                }
                launcherConfig.dockHostPackage = placed
                return@ensureBackgroundThread
            }
            items.filter { it.docked && it.type == ITEM_TYPE_ICON && it.packageName == placed }
                .forEach { homeScreenGridItemsDB.eraseById(it.id!!) }
            val slotTaken = items.any {
                it.docked && it.left == DOCK_HOST_SLOT && it.packageName != placed && it.parentId == null
            }
            if (!slotTaken) {
                hostDockItem(wanted)?.let { homeScreenGridItemsDB.insert(it) }
            }
            launcherConfig.dockHostPackage = wanted
            Timber.d("HomeDeck: dock host slot $placed -> $wanted (slotTaken=$slotTaken)")
            binding.deskScreenGridUi.root.obtainGridItems()
        }
    }

    private fun getDefaultAppPackages() {
        val homeScreenGridItems = ArrayList<MainDisplayLatticeElement>()

        try {
            val defaultDialerPackage =
                (getSystemService(TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
            dockableTitle(defaultDialerPackage)?.let { title ->
                val dialerIcon =
                    MainDisplayLatticeElement(
                        id = null,
                        left = 0,
                        top = launcherConfig.homeRowCount - 1,
                        right = 0,
                        bottom = launcherConfig.homeRowCount - 1,
                        page = 0,
                        packageName = defaultDialerPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(dialerIcon)
            }
        } catch (_: Exception) {
        }

        hostDockItem(dockSlotPackage())?.let { item ->
            homeScreenGridItems.add(item)
            launcherConfig.dockHostPackage = item.packageName
        }

        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, "http://".toUri())
            val resolveInfo =
                packageManager.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultBrowserPackage = resolveInfo!!.activityInfo.packageName
            dockableTitle(defaultBrowserPackage)?.let { title ->
                val browserIcon =
                    MainDisplayLatticeElement(
                        id = null,
                        left = 2,
                        top = launcherConfig.homeRowCount - 1,
                        right = 2,
                        bottom = launcherConfig.homeRowCount - 1,
                        page = 0,
                        packageName = defaultBrowserPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(browserIcon)
            }
        } catch (_: Exception) {
        }

        try {
            val cameraIntent = Intent("android.media.action.IMAGE_CAPTURE")
            val resolveInfo =
                packageManager.resolveActivity(cameraIntent, PackageManager.MATCH_DEFAULT_ONLY)
            val defaultCameraPackage = resolveInfo!!.activityInfo.packageName
            dockableTitle(defaultCameraPackage)?.let { title ->
                val cameraIcon =
                    MainDisplayLatticeElement(
                        id = null,
                        left = 3,
                        top = launcherConfig.homeRowCount - 1,
                        right = 3,
                        bottom = launcherConfig.homeRowCount - 1,
                        page = 0,
                        packageName = defaultCameraPackage,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                homeScreenGridItems.add(cameraIcon)
            }
        } catch (_: Exception) {
        }

        if (launcherConfig.dockColumnCount >= 5) {
            dockableTitle(PLAY_STORE_PACKAGE)?.let { title ->
                homeScreenGridItems.add(
                    MainDisplayLatticeElement(
                        id = null,
                        left = 4,
                        top = launcherConfig.homeRowCount - 1,
                        right = 4,
                        bottom = launcherConfig.homeRowCount - 1,
                        page = 0,
                        packageName = PLAY_STORE_PACKAGE,
                        activityName = "",
                        title = title,
                        type = ITEM_TYPE_ICON,
                        className = "",
                        widgetId = -1,
                        shortcutId = "",
                        icon = null,
                        docked = true,
                        parentId = null
                    )
                )
            }
        }

        homeScreenGridItemsDB.insertAll(homeScreenGridItems)
    }

    fun processWidgetBinding(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        appWidgetInfo: AppWidgetProviderInfo,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnCanBindWidget = null
        val canCreateWidget =
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, appWidgetInfo.provider)
        if (canCreateWidget) {
            callback(true)
        } else {
            mActionOnCanBindWidget = callback
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, appWidgetInfo.provider)
                startActivityForResult(this, REQUEST_ALLOW_BINDING_WIDGET)
            }
        }
    }

    fun processWidgetConfigureScreen(
        appWidgetHost: AppWidgetHost,
        appWidgetId: Int,
        callback: (canBind: Boolean) -> Unit,
    ) {
        mActionOnWidgetConfiguredWidget = callback
        appWidgetHost.startAppWidgetConfigureActivityForResult(
            this,
            appWidgetId,
            0,
            REQUEST_CONFIGURE_WIDGET,
            null
        )
    }

    fun processShorcutCreation(
        activityInfo: ActivityInfo,
        callback: (shortcutId: String, label: String, icon: Drawable) -> Unit,
    ) {
        mActionOnAddShortcut = callback
        val componentName = ComponentName(activityInfo.packageName, activityInfo.name)
        Intent(Intent.ACTION_CREATE_SHORTCUT).apply {
            component = componentName
            startActivityForResult(this, REQUEST_CREATE_SHORTCUT)
        }
    }

    private fun sheetBackgroundColor(fragment: ViewBinding): Int =
        if (fragment is HdAllAppsFragmentBinding) {
            getColor(R.color.launcher_all_app_bg)
        } else {
            getProperBackgroundColor()
        }

    private fun sidePanelBackgroundColor(panel: View): Int =
        (panel.background as? ColorDrawable)?.color ?: getProperBackgroundColor()

    private fun currentSurfaceColor(): Int? = when {
        isAllAppsFragmentExpanded() -> sheetBackgroundColor(binding.everyAppsFragmentUi)
        isWidgetsFragmentExpanded() -> sheetBackgroundColor(binding.lvWidgetsFragmentUi)
        isLeftPanelExpanded() -> sidePanelBackgroundColor(binding.lvLeftPanelUi.root)
        isHostPanelExpanded() -> sidePanelBackgroundColor(binding.lvHostPanelUi.root)
        else -> null
    }

    private fun reviseStatusBarIcons(backgroundColor: Int? = null) {
        val isLightBackground = when {
            backgroundColor != null -> backgroundColor.getContrastColor() == DARK_GREY
            else -> false
        }
        window.insetsController().apply {
            isAppearanceLightStatusBars = isLightBackground
            isAppearanceLightNavigationBars = isLightBackground
        }
    }

    private fun hostLabel(): String = runCatching {
        applicationInfo.loadLabel(packageManager).toString()
    }.getOrDefault(packageName)

    private fun hostActivityName(): String =
        HomeBook.bridge.hostLaunchComponent(this)?.className.orEmpty()
}
