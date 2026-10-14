package com.find.my.phone.gps.location.tracker.ui.language

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.find.my.phone.gps.location.tracker.base.HostViewModel

class LocaleViewModel : HostViewModel() {

    val languages: List<LocaleItem> = Locales.all

    // The "Suggested" group is chosen by the user's region (GEO); "All languages" holds the
    // rest. Both are filtered by the search query. LiveData because the region can arrive
    // asynchronously (IP lookup), refining the guess made from the device locale.
    private val _suggested = MutableLiveData<List<LocaleItem>>()
    val suggested: LiveData<List<LocaleItem>> = _suggested

    private val _others = MutableLiveData<List<LocaleItem>>()
    val others: LiveData<List<LocaleItem>> = _others

    private val _selectedTag = MutableLiveData(Locales.all.first().tag)
    val selectedTag: LiveData<String> = _selectedTag

    private var appliedCountry: String? = "__none__"
    private var query: String = ""
    private var suggestedAll: List<LocaleItem> = emptyList()
    private var othersAll: List<LocaleItem> = emptyList()

    fun init(currentTag: String) {
        if (languages.any { it.tag == currentTag }) {
            _selectedTag.value = currentTag
        }
    }

    /** Rebuilds the Suggested / All groups for the given country (ISO-3166 alpha-2, null = unknown). */
    fun applyCountry(iso2: String?) {
        val normalized = iso2?.uppercase()
        if (normalized == appliedCountry) return
        appliedCountry = normalized

        suggestedAll = Locales.suggestedFor(normalized)
        val suggestedTags = suggestedAll.map { it.tag }.toSet()
        othersAll = languages.filterNot { it.tag in suggestedTags }
        publish()
    }

    /** The search field: matches the English name, the endonym or the code, case-insensitively. */
    fun setQuery(text: String) {
        val q = text.trim()
        if (q == query) return
        query = q
        publish()
    }

    private fun publish() {
        _suggested.value = suggestedAll.filter(::matches)
        _others.value = othersAll.filter(::matches)
    }

    private fun matches(item: LocaleItem): Boolean =
        query.isEmpty() ||
            item.name.contains(query, ignoreCase = true) ||
            item.nativeName.contains(query, ignoreCase = true) ||
            item.code.contains(query, ignoreCase = true)

    fun select(tag: String) {
        if (_selectedTag.value != tag) _selectedTag.value = tag
    }
}
