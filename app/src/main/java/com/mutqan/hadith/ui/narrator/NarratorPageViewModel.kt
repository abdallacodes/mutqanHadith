package com.mutqan.hadith.ui.narrator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.usecase.GetNarratorPageUseCase
import com.mutqan.hadith.domain.usecase.NarratorPage
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The page of the narrator row [narratorId]; an id that is not in the catalog is an error state, not an empty page. */
class NarratorPageViewModel(
    private val narratorId: Long,
    private val getPage: GetNarratorPageUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<NarratorPage>>(UiState.Loading)
    val state: StateFlow<UiState<NarratorPage>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        if (_state.value !is UiState.Success) _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val page = getPage(narratorId)
                _state.value = if (page != null) UiState.Success(page) else UiState.Error("")
            } catch (e: Exception) {
                if (_state.value !is UiState.Success) _state.value = UiState.Error(e.message.orEmpty())
            }
        }
    }
}
