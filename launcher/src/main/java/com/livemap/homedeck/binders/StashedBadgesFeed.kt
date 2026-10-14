package com.livemap.homedeck.binders

import android.view.Menu
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.adapters.MyRecyclerViewAdapter
import org.fossify.commons.extensions.portrait
import org.fossify.commons.extensions.realScreenSize
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.interfaces.RefreshRecyclerViewListener
import org.fossify.commons.views.MyRecyclerView
import com.livemap.homedeck.R
import com.livemap.homedeck.databinding.LnchItemHiddenIconBinding
import com.livemap.homedeck.ext.hiddenIconsDB
import com.livemap.homedeck.entities.StashedBadge

class StashedBadgesFeed(
    activity: BaseSimpleActivity,
    var hiddenIcons: ArrayList<StashedBadge>,
    val listener: RefreshRecyclerViewListener,
    recyclerView: MyRecyclerView,
    itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private var iconPadding = 0

    init {
        calculateIconWidth()
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.lnch_cab_hidden_icons

    override fun prepareActionMode(menu: Menu) {}

    override fun actionItemPressed(id: Int) {
        when (id) {
            R.id.cab_unhide_iconUi -> unHideSelection()
        }
    }

    override fun getSelectableItemCount() = hiddenIcons.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = hiddenIcons.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = hiddenIcons.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(LnchItemHiddenIconBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = hiddenIcons[position]
        holder.bindView(folder, true, true) { itemView, adapterPosition ->
            setupView(itemView, folder)
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = hiddenIcons.size

    private fun getSelectedItems() = hiddenIcons.filter { selectedKeys.contains(it.hashCode()) } as ArrayList<StashedBadge>

    private fun unHideSelection() {
        val positions = getSelectedItemPositions()

        ensureBackgroundThread {
            val selectedItems = getSelectedItems()
            activity.hiddenIconsDB.removeHiddenIcons(selectedItems)
            hiddenIcons.removeAll(selectedItems)

            activity.runOnUiThread {
                removeSelectedItems(positions)
                if (hiddenIcons.isEmpty()) {
                    listener.refreshItems()
                }
            }
        }
    }

    private fun calculateIconWidth() {
        val currentColumnCount = activity.resources.getInteger(
            if (activity.portrait) {
                R.integer.launcher_portrait_column_count
            } else {
                R.integer.launcher_landscape_column_count
            }
        )

        val iconWidth = activity.realScreenSize.x / currentColumnCount
        iconPadding = (iconWidth * 0.1f).toInt()
    }

    private fun setupView(view: View, icon: StashedBadge) {
        LnchItemHiddenIconBinding.bind(view).apply {
            hiddenIconHolderUi.isSelected = selectedKeys.contains(icon.hashCode())
            hiddenIconLabelUi.text = icon.title
            hiddenIconLabelUi.setTextColor(textColor)
            hiddenIconUi.setPadding(iconPadding, iconPadding, iconPadding, 0)

            val factory = DrawableCrossFadeFactory.Builder(150).setCrossFadeEnabled(true).build()

            Glide.with(activity)
                .load(icon.drawable)
                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                .transition(DrawableTransitionOptions.withCrossFade(factory))
                .into(hiddenIconUi)
        }
    }
}
