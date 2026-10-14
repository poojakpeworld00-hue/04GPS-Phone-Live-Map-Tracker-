package com.find.my.phone.gps.location.tracker.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.find.my.phone.gps.location.tracker.databinding.TileContactFavBinding

/** The Favorites row (design 8a): 56dp gradient avatars with a locate badge and the first name. */
class FavoriteAdapter(
    private val onLocate: (ContactNumber) -> Unit,
) : ListAdapter<ContactNumber, FavoriteAdapter.VH>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(TileContactFavBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: TileContactFavBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: ContactNumber) {
            binding.tvInitials.text = contact.initials
            binding.tvInitials.setBackgroundResource(ContactAdapter.AVATARS[contact.avatar])
            binding.tvFirstName.text = contact.firstName
            binding.root.setOnClickListener { onLocate(contact) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<ContactNumber>() {
        override fun areItemsTheSame(oldItem: ContactNumber, newItem: ContactNumber): Boolean =
            oldItem.contactId == newItem.contactId && oldItem.number == newItem.number

        override fun areContentsTheSame(oldItem: ContactNumber, newItem: ContactNumber): Boolean = oldItem == newItem
    }
}
