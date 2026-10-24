package com.mutqan.hadith.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.usecase.GetMasteryAnalyticsUseCase
import com.mutqan.hadith.domain.usecase.MasteryAnalytics
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The figures behind the summary cards of the Insights tab; the full screens compute their own. */
class InsightsViewModel(private val getAnalytics: GetMasteryAnalyticsUseCase) : ViewModel() {

    private val _state = MutableStateFlow<UiState<MasteryAnalytics>>(UiState.Loading)
    val state: StateFlow<UiState<MasteryAnalytics>> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Recomputes the figures; what is on screen stays until the new ones arrive. */
    fun refresh() {
        viewModelScope.launch {
            try {
                _state.value = UiState.Success(getAnalytics())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value !is UiState.Success) _state.value = UiState.Error(e.message.orEmpty())
            }
        }
    }

    /** Back to the loading state before a retry, so the error gives way to a spinner. */
    fun retry() {
        _state.value = UiState.Loading
        refresh()
    }
}
