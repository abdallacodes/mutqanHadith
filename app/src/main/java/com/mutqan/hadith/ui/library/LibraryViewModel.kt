package com.mutqan.hadith.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.model.HadithLevel
import com.mutqan.hadith.domain.usecase.GetLibraryByLevelUseCase
import com.mutqan.hadith.domain.usecase.LibraryLevel
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

/**
 * What the library shows: every level, narrowed to the hadiths that match [searched] when it is not blank.
 * [searched] is the text these [levels] were found for, which trails the text in the search field by a moment.
 */
data class LibraryUi(
    val searched: String,
    val levels: List<LibraryLevel>,
) {
    fun level(level: HadithLevel): LibraryLevel? = levels.firstOrNull { it.level == level }
}

class LibraryViewModel(getLibrary: GetLibraryByLevelUseCase) : ViewModel() {

    private val _query = MutableStateFlow("")

    /** The text in the search field; it changes with every keystroke. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selected = MutableStateFlow(HadithLevel.FOUNDATION)

    /** The level whose hadiths are listed. It survives the search: typing never leaves the level. */
    val selected: StateFlow<HadithLevel> = _selected.asStateFlow()

    private val _section = MutableStateFlow(LibrarySection.HADITHS)

    /** Whether the library shows the hadiths of the levels or the user's collections. */
    val section: StateFlow<LibrarySection> = _section.asStateFlow()

    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<LibraryUi>> = combine(_query, retryTick) { query, _ -> query }
        .flatMapLatest { query ->
            getLibrary(query)
                .map<List<LibraryLevel>, UiState<LibraryUi>> { UiState.Success(LibraryUi(query, it)) }
                .catch { emit(UiState.Error(it.message.orEmpty())) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun onQueryChange(query: String) {
        _query.value = query
    }

    fun onSelectLevel(level: HadithLevel) {
        _selected.value = level
    }

    fun onSelectSection(section: LibrarySection) {
        _section.value = section
    }

    fun retry() = retryTick.update { it + 1 }
}
