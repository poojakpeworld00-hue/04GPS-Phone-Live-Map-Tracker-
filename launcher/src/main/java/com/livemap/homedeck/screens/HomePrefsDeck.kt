package com.livemap.homedeck.screens

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.launchMoreAppsFromUsIntent
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.isTiramisuPlus
import org.fossify.commons.models.RadioItem
import com.livemap.homedeck.R
import com.livemap.homedeck.support.QnaRouteWarden
import com.livemap.homedeck.databinding.HdActivitySettingsBinding
import com.livemap.homedeck.preset.SliderWay
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.support.MAX_COLUMN_COUNT
import com.livemap.homedeck.support.MAX_ROW_COUNT
import com.livemap.homedeck.support.MIN_COLUMN_COUNT
import com.livemap.homedeck.support.MIN_ROW_COUNT
import com.livemap.homedeck.catchers.LatchGadgetStewardCatcher
import java.util.Locale
import kotlin.system.exitProcess

private const val DRAWER_STYLE_VERTICAL = 0
private const val DRAWER_STYLE_PAGED = 1

class HomePrefsDeck : HomeBaseDeck() {

    private val binding by viewBinding(HdActivitySettingsBinding::inflate)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupEdgeToEdge(padBottomSystem = listOf(binding.prefsNestedScrollviewUi))
        setupMaterialScrollListener(binding.prefsNestedScrollviewUi, binding.prefsAppbarUi)
        setupOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.prefsAppbarUi, NavigationIcon.Arrow)
        renewMenuItems()

        setupCustomizeColors()
        setupUseEnglish()
        setupDoubleTapToLock()
        setupAppDrawerEnabled()
        setupDrawerStyle()
        setupCloseAppDrawerOnOtherAppOpen()
        setupOpenKeyboardOnAppDrawer()
        setupDrawerColumnCount()
        setupDrawerSearchBar()
        setupShowDrawerAppLabels()
        setupHomeRowCount()
        setupHomeColumnCount()
        setupShowHomeAppLabels()
        setupLanguage()
        setupManageHiddenIcons()
        updateTextColors(binding.prefsHolderUi)

        arrayOf(
            binding.prefsColorCustomizationSectionLabelUi,
            binding.prefsGeneralSettingsLabelUi,
            binding.prefsDrawerSettingsLabelUi,
            binding.prefsHomeScreenLabelUi
        ).forEach {
            it.setTextColor(getProperPrimaryColor())
        }
    }

    private fun setupOptionsMenu() {
        binding.prefsToolbarUi.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.lv_aboutUi -> fireAbout()
                R.id.lv_more_apps_from_usUi -> launchMoreAppsFromUsIntent()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun renewMenuItems() {
        binding.prefsToolbarUi.menu.apply {
            findItem(R.id.lv_more_apps_from_usUi).isVisible =
                !resources.getBoolean(org.fossify.commons.R.bool.hide_google_relations)
        }
    }

    private fun setupCustomizeColors() {
        binding.prefsColorCustomizationHolderUi.setOnClickListener {
            // see HomeBaseDeck.withFossifyPackageNameSpoofed — startCustomizationActivity()
            // carries its own copy of the anti-rebrand check, separate from the one in onCreate()
            withFossifyPackageNameSpoofed { startCustomizationActivity() }
        }
    }

    private fun setupUseEnglish() {
        binding.prefsUseEnglishHolderUi.beVisibleIf(
            beVisible = (launcherConfig.wasUseEnglishToggled || Locale.getDefault().language != "en")
                    && !isTiramisuPlus()
        )

        binding.prefsUseEnglishUi.isChecked = launcherConfig.useEnglish
        binding.prefsUseEnglishHolderUi.setOnClickListener {
            binding.prefsUseEnglishUi.toggle()
            launcherConfig.useEnglish = binding.prefsUseEnglishUi.isChecked
            exitProcess(0)
        }
    }

    private fun setupDoubleTapToLock() {
        val devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        binding.prefsDoubleTapToLockUi.isChecked = devicePolicyManager.isAdminActive(
            ComponentName(this, LatchGadgetStewardCatcher::class.java)
        )

        binding.prefsDoubleTapToLockHolderUi.setOnClickListener {
            val isLockDeviceAdminActive = devicePolicyManager.isAdminActive(
                ComponentName(this, LatchGadgetStewardCatcher::class.java)
            )
            if (isLockDeviceAdminActive) {
                devicePolicyManager.removeActiveAdmin(
                    ComponentName(this, LatchGadgetStewardCatcher::class.java)
                )
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, LatchGadgetStewardCatcher::class.java)
                )
                intent.putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    getString(R.string.launcher_lock_device_admin_hint)
                )
                startActivity(intent)
            }
        }
    }

    /**
     * The drawer on/off switch. Off is the classic no-drawer home: HomeDeck puts every app on
     * a page the next time it resumes and stops answering the swipe-up; the drawer's own rows are
     * hidden here because nothing reads them. Icons already on the pages are left where they are
     * when the drawer is turned back on.
     */
    private fun setupAppDrawerEnabled() {
        fun refresh() {
            val enabled = launcherConfig.isDrawerEnabled
            binding.prefsAppDrawerEnabledUi.isChecked = enabled
            binding.prefsAppDrawerDisabledHintUi.beVisibleIf(!enabled)
            binding.prefsDrawerStyleHolderUi.beVisibleIf(enabled)
            binding.prefsDrawerColumnCountHolderUi.beVisibleIf(enabled)
            binding.prefsDrawerSearchHolderUi.beVisibleIf(enabled)
            binding.prefsOpenKeyboardOnAppDrawerHolderUi.beVisibleIf(enabled && launcherConfig.showSearchBar)
            binding.prefsCloseAppDrawerOnOtherAppHolderUi.beVisibleIf(enabled)
            binding.prefsShowDrawerAppLabelsHolderUi.beVisibleIf(enabled)
        }
        refresh()
        binding.prefsAppDrawerEnabledHolderUi.setOnClickListener {
            // Turning the drawer back on restores the style it had before it went off.
            if (launcherConfig.isDrawerEnabled) {
                launcherConfig.drawerStyleWhenOn = launcherConfig.drawerMode
                launcherConfig.drawerMode = SliderWay.NONE
            } else {
                launcherConfig.drawerMode = launcherConfig.drawerStyleWhenOn
            }
            refresh()
            setupDrawerStyle()
        }
    }

    private fun setupDrawerStyle() {
        val paged = launcherConfig.drawerMode == SliderWay.PAGED
        binding.prefsDrawerStyleUi.text = getString(if (paged) R.string.launcher_drawer_style_paged else R.string.launcher_drawer_style_vertical)
        binding.prefsDrawerStyleHolderUi.setOnClickListener {
            val items = arrayListOf(
                RadioItem(DRAWER_STYLE_VERTICAL, getString(R.string.launcher_drawer_style_vertical)),
                RadioItem(DRAWER_STYLE_PAGED, getString(R.string.launcher_drawer_style_paged)),
            )
            val current = if (launcherConfig.drawerMode == SliderWay.PAGED) DRAWER_STYLE_PAGED else DRAWER_STYLE_VERTICAL
            RadioGroupDialog(this, items, current) {
                launcherConfig.drawerMode = if (it as Int == DRAWER_STYLE_PAGED) SliderWay.PAGED else SliderWay.VERTICAL
                setupDrawerStyle()
            }
        }
    }

    private fun setupOpenKeyboardOnAppDrawer() {
        binding.prefsOpenKeyboardOnAppDrawerHolderUi.beVisibleIf(launcherConfig.showSearchBar && launcherConfig.isDrawerEnabled)
        binding.prefsOpenKeyboardOnAppDrawerUi.isChecked = launcherConfig.autoShowKeyboardInAppDrawer
        binding.prefsOpenKeyboardOnAppDrawerHolderUi.setOnClickListener {
            binding.prefsOpenKeyboardOnAppDrawerUi.toggle()
            launcherConfig.autoShowKeyboardInAppDrawer = binding.prefsOpenKeyboardOnAppDrawerUi.isChecked
        }
    }

    private fun setupCloseAppDrawerOnOtherAppOpen() {
        binding.prefsCloseAppDrawerOnOtherAppUi.isChecked = launcherConfig.shutAppDrawer
        binding.prefsCloseAppDrawerOnOtherAppHolderUi.setOnClickListener {
            binding.prefsCloseAppDrawerOnOtherAppUi.toggle()
            launcherConfig.shutAppDrawer = binding.prefsCloseAppDrawerOnOtherAppUi.isChecked
        }
    }

    private fun setupDrawerColumnCount() {
        val currentColumnCount = launcherConfig.drawerColumnCount
        binding.prefsDrawerColumnCountUi.text = currentColumnCount.toString()
        binding.prefsDrawerColumnCountHolderUi.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in 1..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentColumnCount) {
                val newColumnCount = it as Int
                if (currentColumnCount != newColumnCount) {
                    launcherConfig.drawerColumnCount = newColumnCount
                    setupDrawerColumnCount()
                }
            }
        }
    }

    private fun setupDrawerSearchBar() {
        val showSearchBar = launcherConfig.showSearchBar
        binding.prefsShowSearchBarUi.isChecked = showSearchBar
        binding.prefsDrawerSearchHolderUi.setOnClickListener {
            binding.prefsShowSearchBarUi.toggle()
            launcherConfig.showSearchBar = binding.prefsShowSearchBarUi.isChecked
            binding.prefsOpenKeyboardOnAppDrawerHolderUi.beVisibleIf(launcherConfig.showSearchBar && launcherConfig.isDrawerEnabled)
        }
    }

    private fun setupShowDrawerAppLabels() {
        binding.prefsShowDrawerAppLabelsUi.isChecked = launcherConfig.showDrawerAppLabels
        binding.prefsShowDrawerAppLabelsHolderUi.setOnClickListener {
            binding.prefsShowDrawerAppLabelsUi.toggle()
            launcherConfig.showDrawerAppLabels = binding.prefsShowDrawerAppLabelsUi.isChecked
        }
    }

    private fun setupHomeRowCount() {
        val currentRowCount = launcherConfig.homeRowCount
        binding.prefsHomeScreenRowCountUi.text = currentRowCount.toString()
        binding.prefsHomeScreenRowCountHolderUi.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_ROW_COUNT..MAX_ROW_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.row_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentRowCount) {
                val newRowCount = it as Int
                if (currentRowCount != newRowCount) {
                    launcherConfig.homeRowCount = newRowCount
                    setupHomeRowCount()
                }
            }
        }
    }

    private fun setupHomeColumnCount() {
        val currentColumnCount = launcherConfig.homeColumnCount
        binding.prefsHomeScreenColumnCountUi.text = currentColumnCount.toString()
        binding.prefsHomeScreenColumnCountHolderUi.setOnClickListener {
            val items = ArrayList<RadioItem>()
            for (i in MIN_COLUMN_COUNT..MAX_COLUMN_COUNT) {
                items.add(
                    RadioItem(
                        id = i,
                        title = resources.getQuantityString(
                            org.fossify.commons.R.plurals.column_counts, i, i
                        )
                    )
                )
            }

            RadioGroupDialog(this, items, currentColumnCount) {
                val newColumnCount = it as Int
                if (currentColumnCount != newColumnCount) {
                    launcherConfig.homeColumnCount = newColumnCount
                    setupHomeColumnCount()
                }
            }
        }
    }

    private fun setupShowHomeAppLabels() {
        binding.prefsShowHomeAppLabelsUi.isChecked = launcherConfig.showHomeAppLabels
        binding.prefsShowHomeAppLabelsHolderUi.setOnClickListener {
            binding.prefsShowHomeAppLabelsUi.toggle()
            launcherConfig.showHomeAppLabels = binding.prefsShowHomeAppLabelsUi.isChecked
        }
    }

    @SuppressLint("NewApi")
    private fun setupLanguage() {
        binding.prefsLanguageUi.text = Locale.getDefault().displayLanguage
        binding.prefsLanguageHolderUi.beVisibleIf(isTiramisuPlus())
        binding.prefsLanguageHolderUi.setOnClickListener {
            launchChangeAppLanguageIntent()
        }
    }

    private fun setupManageHiddenIcons() {
        binding.prefsManageHiddenIconsHolderUi.setOnClickListener {
            startActivity(Intent(this, StashedBadgesDeck::class.java))
        }
    }

    private fun fireAbout() {
        val licenses = 0L
        val faqItems = QnaRouteWarden.launcherFaqItems(this)

        startAboutActivity(
            appNameId = R.string.launcher_app_launcher_name,
            licenseMask = licenses,
            versionName = hostVersionName(),
            faqItems = faqItems,
            showFAQBeforeMail = true
        )
    }

    /**
     * The host app version, shown on the About screen.
     *
     * Read off the package manager rather than a BuildConfig constant: the module has its own
     * BuildConfig, and its version is the launcher library version, which is not what a user
     * looking at "About" is asking for.
     */
    private fun hostVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
