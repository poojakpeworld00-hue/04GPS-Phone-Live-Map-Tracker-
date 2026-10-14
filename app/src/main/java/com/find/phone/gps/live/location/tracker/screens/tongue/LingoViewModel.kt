package com.find.phone.gps.live.location.tracker.screens.tongue

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.find.phone.gps.live.location.tracker.foundation.HolderViewModel

class LingoViewModel : HolderViewModel() {

    val languages: List<LingoElement> = LiveLocales.all

    // The "Suggested" group is chosen by the user's region (GEO); "All languages" holds the
    // rest. Both are filtered by the search query. LiveData because the region can arrive
    // asynchronously (IP lookup), refining the guess made from the device locale.
    private val _suggested = MutableLiveData<List<LingoElement>>()
    val suggested: LiveData<List<LingoElement>> = _suggested

    private val _others = MutableLiveData<List<LingoElement>>()
    val others: LiveData<List<LingoElement>> = _others

    private val _selectedTag = MutableLiveData(LiveLocales.all.first().tag)
    val selectedTag: LiveData<String> = _selectedTag

    private var appliedCountry: String? = "__none__"
    private var query: String = ""
    private var suggestedAll: List<LingoElement> = emptyList()
    private var othersAll: List<LingoElement> = emptyList()

    fun init(currentTag: String) {
        if (languages.any { it.tag == currentTag }) {
            _selectedTag.value = currentTag
        }
    }

    /** Rebuilds the Suggested / All groups for the given country (ISO-3166 alpha-2, null = unknown). */
    fun enactCountry(iso2: String?) {
        val normalized = iso2?.uppercase()
        if (normalized == appliedCountry) return
        appliedCountry = normalized

        suggestedAll = LiveLocales.suggestedFor(normalized)
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

    private fun matches(item: LingoElement): Boolean =
        query.isEmpty() ||
            item.name.contains(query, ignoreCase = true) ||
            item.nativeName.contains(query, ignoreCase = true) ||
            item.code.contains(query, ignoreCase = true)

    fun select(tag: String) {
        if (_selectedTag.value != tag) _selectedTag.value = tag
    }
}
