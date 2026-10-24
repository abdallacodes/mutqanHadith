package com.mutqan.hadith.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [textScale] sizes the matn; [isStarting] and [startFailed] describe the "start memorizing" request. */
data class HadithStudyUi(
    val study: HadithStudy,
    val textScale: Float,
    val isStarting: Boolean = false,
    val startFailed: Boolean = false,
)

class HadithStudyViewModel(
    private val hadithId: Long,
    getStudy: GetHadithStudyUseCase,
    private val startMemorizingUseCase: StartMemorizingUseCase,
    private val settings: ReaderSettings,
) : ViewModel() {

    private enum class Start { IDLE, STARTING, FAILED }

    private val textScale = MutableStateFlow(settings.matnScale)
    private val start = MutableStateFlow(Start.IDLE)
    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<HadithStudyUi>> = retryTick
        .flatMapLatest {
            combine(getStudy(hadithId), textScale, start) { study, scale, start ->
                if (study == null) {
                    UiState.Error("Hadith $hadithId not found")
                } else {
                    UiState.Success(HadithStudyUi(study, scale, start == Start.STARTING, start == Start.FAILED))
                }
            }.catch { emit(UiState.Error(it.message.orEmpty())) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun retry() = retryTick.update { it + 1 }

    fun increaseText() = setScale(MatnScale.larger(textScale.value))

    fun decreaseText() = setScale(MatnScale.smaller(textScale.value))

    private fun setScale(scale: Float) {
        textScale.value = scale
        settings.matnScale = scale
    }

    /**
     * Takes the hadith into the user's memorization, then calls [onStarted] so the screen can open the
     * learning flow. A request already under way is not repeated; a failure is shown and can be retried.
     */
    fun startMemorizing(onStarted: (Long) -> Unit) {
        if (start.value == Start.STARTING) return
        start.value = Start.STARTING
        viewModelScope.launch {
            try {
                startMemorizingUseCase(hadithId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                start.value = Start.FAILED
                return@launch
            }
            start.value = Start.IDLE
            onStarted(hadithId)
        }
    }
}
