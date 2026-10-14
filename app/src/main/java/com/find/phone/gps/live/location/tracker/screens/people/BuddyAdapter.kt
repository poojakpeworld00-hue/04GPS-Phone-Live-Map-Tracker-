package com.find.phone.gps.live.location.tracker.screens.people

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.CellContactHeaderBinding
import com.find.phone.gps.live.location.tracker.databinding.CellContactRowBinding

/** The A–Z list (design 8a): letter headings, then that letter's contact cards with Locate and Call. */
class BuddyAdapter(
    private val onLocate: (BuddyNum) -> Unit,
    private val onCall: (BuddyNum) -> Unit,
) : ListAdapter<BuddyAdapter.Item, RecyclerView.ViewHolder>(Diff) {

    sealed class Item {
        data class Header(val letter: String) : Item()
        data class Row(val contact: BuddyNum) : Item()
    }

    /** Adapter position of each letter heading, for the rail. */
    val letterPositions = LinkedHashMap<String, Int>()

    /** Groups [contacts] (already sorted) under their letters. */
    fun submitContacts(contacts: List<BuddyNum>, onDone: () -> Unit = {}) {
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
        return if (viewType == TYPE_HEADER) HeaderVH(CellContactHeaderBinding.inflate(inflater, parent, false))
        else RowVH(CellContactRowBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is Item.Header -> (holder as HeaderVH).binding.txtLetter.text = item.letter
            is Item.Row -> (holder as RowVH).bind(item.contact)
        }
    }

    class HeaderVH(val binding: CellContactHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    inner class RowVH(private val binding: CellContactRowBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(contact: BuddyNum) {
            binding.txtInitial.text = contact.initials
            binding.txtInitial.setBackgroundResource(AVATARS[contact.avatar])
            binding.txtName.text = contact.name
            // A contact saved without a name already shows its number as the name.
            val number = contact.number.takeIf { it != contact.name }
            binding.txtNumber.text = listOfNotNull(contact.label.takeIf { it.isNotBlank() }, number).joinToString(" · ")
            binding.actLocate.setOnClickListener { onLocate(contact) }
            binding.actCallVw.setOnClickListener { onCall(contact) }
            binding.buddyRow.setOnClickListener { onLocate(contact) }
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

        /** The six avatar gradients of the design, indexed by [BuddyNum.avatar]. */
        val AVATARS = intArrayOf(
            R.drawable.lv_avatar_g0, R.drawable.lv_avatar_g1, R.drawable.lv_avatar_g2,
            R.drawable.lv_avatar_g3, R.drawable.lv_avatar_g4, R.drawable.lv_avatar_g5,
        )
    }
}
