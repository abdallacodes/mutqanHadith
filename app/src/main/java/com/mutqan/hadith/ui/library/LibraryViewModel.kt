package com.mutqan.hadith.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.usecase.GetLibraryPageUseCase
import com.mutqan.hadith.domain.usecase.LibraryPage
import com.mutqan.hadith.ui.collections.LibrarySection
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class LibraryViewModel(getPage: GetLibraryPageUseCase) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** The text in the search field; it changes with every keystroke. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _openGroupId = MutableStateFlow<Long?>(null)

    /** The book or section being read; null is the shelf of books. The search stays inside it. */
    val openGroupId: StateFlow<Long?> = _openGroupId.asStateFlow()

    private val _section = MutableStateFlow(LibrarySection.HADITHS)

    /** Whether the library shows the books and their hadiths or the user's collections. */
    val section: StateFlow<LibrarySection> = _section.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    /** The open group's page. Its `searched` trails the text in the search field by a moment. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<LibraryPage>> = combine(_openGroupId, _query, retryTick) { group, query, _ -> group to query }
        .flatMapLatest { (group, query) ->
            getPage(group, query)
                .map<LibraryPage, UiState<LibraryPage>> { UiState.Success(it) }
                .catch { emit(UiState.Error(it.message.orEmpty())) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun onQueryChange(query: String) {
        _query.value = query
    }

    /** Opens a book or a section. A search belongs to the group it was typed in, so it does not come along. */
    fun onOpenGroup(groupId: Long) {
        _query.value = ""
        _openGroupId.value = groupId
    }

    /**
     * One step back: out of a search, else up to the group above, the books at the top. False when there is nowhere
     * further to go, which leaves the screen to whoever is holding the library.
     */
    fun onUp(): Boolean {
        if (_query.value.isNotEmpty()) {
            _query.value = ""
            return true
        }
        if (_openGroupId.value == null) return false
        val trail = (state.value as? UiState.Success)?.data?.trail.orEmpty()
        _openGroupId.value = trail.getOrNull(trail.size - 2)?.id
        return true
    }

    fun onSelectSection(section: LibrarySection) {
        _section.value = section
    }

    fun retry() = retryTick.update { it + 1 }
}
