package com.mutqan.hadith.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.HadithStudy
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * [textScale] sizes the matn and [showTashkeel] says whether it is shown with its tashkeel (it is, by default);
 * [isStarting] and [startFailed] describe the "start memorizing" request, and [pacingPrompt] says the request waits
 * for the user's word because the day's new hadiths are already enough.
 */
data class HadithStudyUi(
    val study: HadithStudy,
    val textScale: Float,
    val isStarting: Boolean = false,
    val startFailed: Boolean = false,
    val showTashkeel: Boolean = true,
    val pacingPrompt: Boolean = false,
)

class HadithStudyViewModel(
    private val hadithId: Long,
    getStudy: GetHadithStudyUseCase,
    private val startMemorizingUseCase: StartMemorizingUseCase,
    private val settings: ReaderSettings,
    private val pacing: DailyPacing = DailyPacing(InMemoryStudyPreferences()),
) : ViewModel() {

    private val textScale = MutableStateFlow(settings.matnScale)

    // Not a stored preference: every visit opens on the full text, and the toggle is for a quick look without it.
    private val showTashkeel = MutableStateFlow(true)
    private val start = PacedStart(hadithId, startMemorizingUseCase, pacing, viewModelScope)
    private val retryTick = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<HadithStudyUi>> = retryTick
        .flatMapLatest {
            combine(getStudy(hadithId), textScale, start.status, showTashkeel, start.asking) { study, scale, status, tashkeel, prompt ->
                if (study == null) {
                    UiState.Error("Hadith $hadithId not found")
                } else {
                    UiState.Success(
                        HadithStudyUi(study, scale, status == PacedStart.Status.STARTING, status == PacedStart.Status.FAILED, tashkeel, prompt),
                    )
                }
            }.catch { emit(UiState.Error(it.message.orEmpty())) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun retry() = retryTick.update { it + 1 }

    fun increaseText() = setScale(MatnScale.larger(textScale.value))

    fun decreaseText() = setScale(MatnScale.smaller(textScale.value))

    fun toggleTashkeel() = showTashkeel.update { !it }

    private fun setScale(scale: Float) {
        textScale.value = scale
        settings.matnScale = scale
    }

    /**
     * Asks to take the hadith into the user's memorization. When enough new hadiths were started today the user is
     * asked first ([HadithStudyUi.pacingPrompt]); otherwise it is started right away, see [PacedStart].
     */
    fun startMemorizing(onStarted: (Long) -> Unit) = start.request(onStarted)

    /** The user chose to go on in spite of the day's limit. */
    fun confirmPacing(onStarted: (Long) -> Unit) = start.confirm(onStarted)

    /** The user left the hadith for another day. */
    fun dismissPacing() = start.dismiss()
}
