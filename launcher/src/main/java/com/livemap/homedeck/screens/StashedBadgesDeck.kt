package com.livemap.homedeck.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.commons.views.MyGridLayoutManager
import com.livemap.homedeck.binders.StashedBadgesFeed
import com.livemap.homedeck.databinding.HdActivityHiddenIconsBinding
import com.livemap.homedeck.ext.launcherConfig
import com.livemap.homedeck.support.BadgeMolder
import com.livemap.homedeck.ext.getDrawableForPackageName
import com.livemap.homedeck.ext.hiddenIconsDB
import com.livemap.homedeck.entities.StashedBadge

class StashedBadgesDeck : HomeBaseDeck(), RefreshRecyclerViewListener {
    private val binding by viewBinding(HdActivityHiddenIconsBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        updateIcons()

        setupEdgeToEdge(padBottomSystem = listOf(binding.mngHiddenIconsListUi))
        setupMaterialScrollListener(binding.mngHiddenIconsListUi, binding.mngHiddenIconsAppbarUi)

        val layoutManager = binding.mngHiddenIconsListUi.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = launcherConfig.drawerColumnCount
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.mngHiddenIconsAppbarUi, NavigationIcon.Arrow)
    }

    private fun updateIcons() {
        ensureBackgroundThread {
            val hiddenIcons = hiddenIconsDB.getHiddenIcons().sortedWith(
                compareBy({
                    it.title.normalizeString().lowercase()
                }, {
                    it.packageName
                })
            ).toMutableList() as ArrayList<StashedBadge>

            val hiddenIconsEmpty = hiddenIcons.isEmpty()
            runOnUiThread {
                binding.mngHiddenIconsPlaceholderUi.beVisibleIf(hiddenIconsEmpty)
            }

            if (hiddenIcons.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_MAIN, null)
                intent.addCategory(Intent.CATEGORY_LAUNCHER)

                val list = packageManager.queryIntentActivities(intent, PackageManager.PERMISSION_GRANTED)
                for (info in list) {
                    val componentInfo = info.activityInfo.applicationInfo
                    val packageName = componentInfo.packageName
                    val activityName = info.activityInfo.name
                    hiddenIcons.firstOrNull { it.getIconIdentifier() == "$packageName/$activityName" }?.apply {
                        drawable = info.loadIcon(packageManager)?.let { BadgeMolder.shape(it, launcherConfig.legacyIconTray) }
                            ?: getDrawableForPackageName(packageName)
                    }
                }

                hiddenIcons.firstOrNull { it.packageName == applicationContext.packageName }?.apply {
                    drawable = getDrawableForPackageName(packageName)
                }
            }

            val iconsToRemove = hiddenIcons.filter { it.drawable == null }
            if (iconsToRemove.isNotEmpty()) {
                hiddenIconsDB.removeHiddenIcons(iconsToRemove)
                hiddenIcons.removeAll(iconsToRemove)
            }

            runOnUiThread {
                StashedBadgesFeed(this, hiddenIcons, this, binding.mngHiddenIconsListUi) {
                }.apply {
                    binding.mngHiddenIconsListUi.adapter = this
                }
            }
        }
    }

    override fun refreshItems() {
        updateIcons()
    }
}
