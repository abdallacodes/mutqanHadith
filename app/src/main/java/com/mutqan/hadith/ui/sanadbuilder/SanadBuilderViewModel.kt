package com.mutqan.hadith.ui.sanadbuilder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.drill.PlacementFeedback
import com.mutqan.hadith.domain.drill.SanadBuilderState
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What was recorded when the chain was submitted. */
data class BuilderResult(
    val rating: ReviewRating,
    val misplacedNames: List<String>,
)

data class SanadBuilderUi(
    val hadithId: Long,
    val hadithNumber: Int,
    val sourceBook: String,
    val matn: String,
    val builder: SanadBuilderState,
    /** The latest placement, so its slot can flash green or shake. */
    val lastFeedback: PlacementFeedback? = null,
    /** Incremented on every placement so repeating the same wrong slot re-triggers the shake. */
    val feedbackTick: Int = 0,
    val isSubmitting: Boolean = false,
    val result: BuilderResult? = null,
    val submitError: String? = null,
)

sealed interface SanadBuilderEvent {
    data class SelectSlot(val index: Int) : SanadBuilderEvent
    data class PlaceChip(val chipId: Int) : SanadBuilderEvent
    data class RemoveFromSlot(val index: Int) : SanadBuilderEvent
    data object Reveal : SanadBuilderEvent
    data object Submit : SanadBuilderEvent
    data object Retry : SanadBuilderEvent
    data object DismissError : SanadBuilderEvent
}

class SanadBuilderViewModel(
    private val hadithId: Long,
    private val hadiths: HadithRepository,
    private val submitReview: SubmitReviewUseCase,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<SanadBuilderUi>>(UiState.Loading)
    val state: StateFlow<UiState<SanadBuilderUi>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val hadith = hadiths.getWithSanad(hadithId)
                if (hadith == null) {
                    UiState.Error("Hadith $hadithId not found")
                } else {
                    UiState.Success(
                        SanadBuilderUi(
                            hadithId = hadithId,
                            hadithNumber = hadith.hadith.hadithNumber,
                            sourceBook = hadith.hadith.sourceBook,
                            matn = hadith.hadith.matnWithTashkeel,
                            builder = SanadBuilderState.start(hadith.sanad, random),
                        ),
                    )
                }
            } catch (e: Exception) {
                UiState.Error(e.message.orEmpty())
            }
        }
    }

    fun onEvent(event: SanadBuilderEvent) {
        when (event) {
            is SanadBuilderEvent.SelectSlot -> editBuilder { it.selectSlot(event.index) }
            is SanadBuilderEvent.PlaceChip -> update { ui ->
                if (ui.result != null) return@update ui
                val (next, feedback) = ui.builder.place(event.chipId)
                ui.copy(
                    builder = next,
                    lastFeedback = feedback ?: ui.lastFeedback,
                    feedbackTick = if (feedback != null) ui.feedbackTick + 1 else ui.feedbackTick,
                )
            }
            is SanadBuilderEvent.RemoveFromSlot -> editBuilder { it.removeFromSlot(event.index) }
            SanadBuilderEvent.Reveal -> editBuilder { it.reveal() }
            SanadBuilderEvent.Retry -> update { ui ->
                ui.copy(
                    builder = SanadBuilderState.start(ui.builder.slots, random),
                    lastFeedback = null,
                    result = null,
                    submitError = null,
                )
            }
            SanadBuilderEvent.DismissError -> update { it.copy(submitError = null) }
            SanadBuilderEvent.Submit -> submit()
        }
    }

    private fun submit() {
        val ui = (_state.value as? UiState.Success)?.data ?: return
        if (!ui.builder.isComplete || ui.isSubmitting || ui.result != null) return

        update { it.copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            val builder = ui.builder
            val rating = builder.rating()
            try {
                // Only the Sanad was tested: the Matn is the anchor shown, not recalled.
                submitReview(ui.hadithId, null, rating, builder.misplacedNarratorIds)
                val names = builder.slots.map { it.narrator }
                    .filter { it.id in builder.misplacedNarratorIds }
                    .map { it.primaryName }
                update { it.copy(isSubmitting = false, result = BuilderResult(rating, names)) }
            } catch (e: Exception) {
                update { it.copy(isSubmitting = false, submitError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private inline fun editBuilder(transform: (SanadBuilderState) -> SanadBuilderState) =
        update { ui -> if (ui.result != null) ui else ui.copy(builder = transform(ui.builder)) }

    private inline fun update(transform: (SanadBuilderUi) -> SanadBuilderUi) {
        _state.value = (_state.value as? UiState.Success)?.let { UiState.Success(transform(it.data)) } ?: _state.value
    }
}
