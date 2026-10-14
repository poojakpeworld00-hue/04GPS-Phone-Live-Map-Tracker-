package com.find.phone.gps.live.location.tracker.screens.dashboard

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.find.phone.gps.live.location.tracker.R
import com.find.phone.gps.live.location.tracker.databinding.BsheetCountryPickerBinding
import com.find.phone.gps.live.location.tracker.databinding.CellCountryBinding
import com.find.phone.gps.live.location.tracker.screens.finder.Realms
import com.find.phone.gps.live.location.tracker.screens.finder.Realm
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * The country selector sheet (design 3c). Opens expanded to ~81% of the screen (the design's
 * 150px top offset on 780), with the current choice under "Suggested" and every territory
 * under "All countries", filtered by name or dial code.
 */
class LandChooserDrawer : BottomSheetDialogFragment() {

    private var _binding: BsheetCountryPickerBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CountryAdapter

    override fun getTheme(): Int = R.style.Ds_BottomSheetDialog

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        (super.onCreateDialog(savedInstanceState) as BottomSheetDialog).apply {
            setOnShowListener {
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BsheetCountryPickerBinding.inflate(inflater, container, false)
        val screen = resources.displayMetrics.heightPixels
        binding.root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screen * SHEET_HEIGHT_FRACTION).toInt())
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val currentIso = requireArguments().getString(ARG_ISO).orEmpty()
        val current = Realms.byIso(currentIso)
        if (current != null) {
            binding.tvSuggestedFlag.text = Realms.flag(current.iso2)
            binding.tvSuggestedName.text = current.name
            binding.tvSuggestedDial.text = "+" + current.dial
            binding.rowSuggested.setOnClickListener { pick(current) }
        } else {
            binding.tvSuggested.visibility = View.GONE
            binding.rowSuggested.visibility = View.GONE
        }

        adapter = CountryAdapter(::pick)
        binding.rvCountries.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCountries.adapter = adapter
        adapter.submit(Realms.all.filter { it.iso2 != currentIso })

        binding.searchInput.doAfterTextChanged { text ->
            val q = text?.toString()?.trim().orEmpty()
            adapter.submit(
                Realms.all.filter { t ->
                    t.iso2 != currentIso &&
                        (q.isEmpty() || t.name.contains(q, ignoreCase = true) || t.dial.contains(q.removePrefix("+")))
                }
            )
        }
        binding.btnClose.setOnClickListener { dismiss() }
    }

    private fun pick(territory: Realm) {
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle().apply { putString(ARG_ISO, territory.iso2) })
        dismiss()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /** Flag / name / dial rows. */
    private class CountryAdapter(private val onClick: (Realm) -> Unit) : RecyclerView.Adapter<CountryAdapter.VH>() {

        private val items = mutableListOf<Realm>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<Realm>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        inner class VH(val binding: CellCountryBinding) : RecyclerView.ViewHolder(binding.root) {
            init {
                binding.row.setOnClickListener {
                    val p = bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) onClick(items[p])
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(CellCountryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val t = items[position]
            holder.binding.tvFlag.text = Realms.flag(t.iso2)
            holder.binding.tvName.text = t.name
            holder.binding.tvDial.text = "+" + t.dial
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        const val RESULT_KEY = "country_picked"
        const val ARG_ISO = "iso2"
        private const val SHEET_HEIGHT_FRACTION = 0.81f

        fun newInstance(currentIso: String) = LandChooserDrawer().apply {
            arguments = Bundle().apply { putString(ARG_ISO, currentIso) }
        }
    }
}
