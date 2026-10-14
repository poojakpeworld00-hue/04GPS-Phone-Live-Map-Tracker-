package com.livemap.homedeck.binders

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.commons.extensions.getProperTextColor
import com.livemap.homedeck.R
import com.livemap.homedeck.screens.HomeBaseDeck
import com.livemap.homedeck.databinding.HdItemWidgetListItemsHolderBinding
import com.livemap.homedeck.databinding.HdItemWidgetListSectionBinding
import com.livemap.homedeck.databinding.HdItemWidgetPreviewBinding
import com.livemap.homedeck.support.WIDGET_LIST_ITEMS_HOLDER
import com.livemap.homedeck.support.WIDGET_LIST_SECTION
import com.livemap.homedeck.ports.AppletsFragmentObserver
import com.livemap.homedeck.entities.AppletsListElement
import com.livemap.homedeck.entities.AppletsListElementsKeeper
import com.livemap.homedeck.entities.AppletsListSegment

class AppletsFeed(
    val activity: HomeBaseDeck,
    var widgetListItems: ArrayList<AppletsListElement>,
    val widgetsFragmentListener: AppletsFragmentObserver,
    val itemClick: () -> Unit
) : RecyclerView.Adapter<AppletsFeed.ViewHolder>() {

    private var textColor = activity.getProperTextColor()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = when (viewType) {
            WIDGET_LIST_SECTION -> HdItemWidgetListSectionBinding.inflate(inflater, parent, false)
            else -> HdItemWidgetListItemsHolderBinding.inflate(inflater, parent, false)
        }

        return ViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val widgetListItem = widgetListItems[position]
        holder.bindView(widgetListItems[position]) { itemView, layoutPosition ->
            when (widgetListItem) {
                is AppletsListSegment -> setupListSection(itemView, widgetListItem)
                is AppletsListElementsKeeper -> setupListItemsHolder(itemView, widgetListItem)
            }
        }
    }

    override fun getItemCount() = widgetListItems.size

    override fun getItemViewType(position: Int) = when {
        widgetListItems[position] is AppletsListSegment -> WIDGET_LIST_SECTION
        else -> WIDGET_LIST_ITEMS_HOLDER
    }

    private fun setupListSection(view: View, section: AppletsListSegment) {
        HdItemWidgetListSectionBinding.bind(view).apply {
            gadgetAppTitleUi.text = section.appTitle
            gadgetAppTitleUi.setTextColor(textColor)
            gadgetAppIconUi.setImageDrawable(section.appIcon)
        }
    }

    private fun setupListItemsHolder(view: View, listItem: AppletsListElementsKeeper) {
        val binding = HdItemWidgetListItemsHolderBinding.bind(view)
        binding.gadgetListItemsHolderUi.removeAllViews()
        binding.gadgetListItemsScrollViewUi.scrollX = 0
        listItem.widgets.forEachIndexed { index, widget ->
            val imageSize = activity.resources.getDimension(R.dimen.launcher_widget_preview_size).toInt()
            val widgetPreview = HdItemWidgetPreviewBinding.inflate(LayoutInflater.from(activity))
            binding.gadgetListItemsHolderUi.addView(widgetPreview.root)

            val endMargin = if (index == listItem.widgets.size - 1) {
                activity.resources.getDimension(org.fossify.commons.R.dimen.medium_margin).toInt()
            } else {
                0
            }

            widgetPreview.gadgetTitleUi.apply {
                text = widget.widgetTitleUi
                setTextColor(textColor)
            }

            widgetPreview.gadgetSizeUi.apply {
                text = if (widget.isShortcut) {
                    activity.getString(org.fossify.commons.R.string.shortcut)
                } else {
                    "${widget.widthCells} x ${widget.heightCells}"
                }
                setTextColor(textColor)
            }

            (widgetPreview.gadgetImageUi.layoutParams as RelativeLayout.LayoutParams).apply {
                marginStart = activity.resources.getDimension(org.fossify.commons.R.dimen.activity_margin).toInt()
                marginEnd = endMargin
                width = imageSize
                height = imageSize
            }

            Glide.with(activity)
                .load(widget.widgetPreviewImage)
                .into(widgetPreview.gadgetImageUi)

            widgetPreview.root.setOnClickListener { itemClick() }

            widgetPreview.root.setOnLongClickListener { view ->
                widgetsFragmentListener.onWidgetLongPressed(widget)
                true
            }
        }
    }

    fun reviseItems(newItems: ArrayList<AppletsListElement>) {
        val oldSum = widgetListItems.sumOf { it.getHashToCompare() }
        val newSum = newItems.sumOf { it.getHashToCompare() }
        if (oldSum != newSum) {
            widgetListItems = newItems
            notifyDataSetChanged()
        }
    }

    fun reviseTextColor(newTextColor: Int) {
        if (newTextColor != textColor) {
            textColor = newTextColor
            notifyDataSetChanged()
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bindView(widgetListItem: AppletsListElement, callback: (itemView: View, adapterPosition: Int) -> Unit) {
            itemView.apply {
                callback(this, adapterPosition)
            }
        }
    }
}
