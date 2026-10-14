package com.find.phone.gps.live.location.tracker.screens.people

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.find.phone.gps.live.location.tracker.databinding.CellContactFavBinding

class PinnedAdapter(
    private val onLocate: (BuddyNum) -> Unit,
) : ListAdapter<BuddyNum, PinnedAdapter.VH>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(CellContactFavBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: CellContactFavBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: BuddyNum) {
            binding.txtInitials.text = contact.initials
            binding.txtInitials.setBackgroundResource(BuddyAdapter.AVATARS[contact.avatar])
            binding.txtFirstName.text = contact.firstName
            binding.root.setOnClickListener { onLocate(contact) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<BuddyNum>() {
        override fun areItemsTheSame(oldItem: BuddyNum, newItem: BuddyNum): Boolean =
            oldItem.contactId == newItem.contactId && oldItem.number == newItem.number

        override fun areContentsTheSame(oldItem: BuddyNum, newItem: BuddyNum): Boolean = oldItem == newItem
    }
}
