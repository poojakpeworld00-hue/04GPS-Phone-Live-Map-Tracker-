package com.find.my.phone.gps.location.tracker.ui.contacts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.find.my.phone.gps.location.tracker.R
import com.find.my.phone.gps.location.tracker.databinding.TileContactHeaderBinding
import com.find.my.phone.gps.location.tracker.databinding.TileContactRowBinding

/** The A–Z list (design 8a): letter headings, then that letter's contact cards with Locate and Call. */
class ContactAdapter(
    private val onLocate: (ContactNumber) -> Unit,
    private val onCall: (ContactNumber) -> Unit,
) : ListAdapter<ContactAdapter.Item, RecyclerView.ViewHolder>(Diff) {

    sealed class Item {
        data class Header(val letter: String) : Item()
        data class Row(val contact: ContactNumber) : Item()
    }

    /** Adapter position of each letter heading, for the rail. */
    val letterPositions = LinkedHashMap<String, Int>()

    /** Groups [contacts] (already sorted) under their letters. */
    fun submitContacts(contacts: List<ContactNumber>, onDone: () -> Unit = {}) {
        val items = ArrayList<Item>(contacts.size + 27)
        val positions = LinkedHashMap<String, Int>()
        var letter: String? = null
        for (c in contacts) {
            if (c.letter != letter) {
                letter = c.letter
                positions[c.letter] = items.size
                items += Item.Header(c.letter)
            }
            items += Item.Row(c)
        }
        letterPositions.clear()
        letterPositions.putAll(positions)
        submitList(items, onDone)
    }

    override fun getItemViewType(position: Int): Int = if (getItem(position) is Item.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) HeaderVH(TileContactHeaderBinding.inflate(inflater, parent, false))
        else RowVH(TileContactRowBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Header -> (holder as HeaderVH).binding.tvLetter.text = item.letter
            is Item.Row -> (holder as RowVH).bind(item.contact)
        }
    }

    class HeaderVH(val binding: TileContactHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class RowVH(private val binding: TileContactRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: ContactNumber) {
            binding.tvInitial.text = contact.initials
            binding.tvInitial.setBackgroundResource(AVATARS[contact.avatar])
            binding.tvName.text = contact.name
            // A contact saved without a name already shows its number as the name.
            val number = contact.number.takeIf { it != contact.name }
            binding.tvNumber.text = listOfNotNull(contact.label.takeIf { it.isNotBlank() }, number).joinToString(" · ")
            binding.btnLocate.setOnClickListener { onLocate(contact) }
            binding.btnCall.setOnClickListener { onCall(contact) }
            binding.contactRow.setOnClickListener { onLocate(contact) }
        }
    }

    private object Diff : DiffUtil.ItemCallback<Item>() {
        override fun areItemsTheSame(oldItem: Item, newItem: Item): Boolean = when {
            oldItem is Item.Header && newItem is Item.Header -> oldItem.letter == newItem.letter
            oldItem is Item.Row && newItem is Item.Row ->
                oldItem.contact.contactId == newItem.contact.contactId && oldItem.contact.number == newItem.contact.number
            else -> false
        }

        override fun areContentsTheSame(oldItem: Item, newItem: Item): Boolean = oldItem == newItem
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1

        /** The six avatar gradients of the design, indexed by [ContactNumber.avatar]. */
        val AVATARS = intArrayOf(
            R.drawable.ds_avatar_g0, R.drawable.ds_avatar_g1, R.drawable.ds_avatar_g2,
            R.drawable.ds_avatar_g3, R.drawable.ds_avatar_g4, R.drawable.ds_avatar_g5,
        )
    }
}
