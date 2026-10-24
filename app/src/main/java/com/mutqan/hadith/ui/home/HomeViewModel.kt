package com.mutqan.hadith.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class HomeViewModel(observeDashboard: ObserveStudyDashboardUseCase) : ViewModel() {

    /** Bumped by [refresh] to re-evaluate what is due now; the data itself updates live. */
    private val refreshTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<StudyDashboard>> = refreshTick
        .flatMapLatest {
            observeDashboard()
                .map<StudyDashboard, UiState<StudyDashboard>> { UiState.Success(it) }
                .catch { emit(UiState.Error(it.message.orEmpty())) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun refresh() = refreshTick.update { it + 1 }
}
