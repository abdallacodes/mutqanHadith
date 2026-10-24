package com.mutqan.hadith.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class ReviewMode(val route: String) {
    /** Hadiths whose review is due. */
    DUE("due"),

    /**
     * Hadiths studied for the first time: the next never-seen ones in curriculum order, or, when a target is
     * given, that one Hadith (the library's "start memorizing").
     */
    NEW("new"),

    /** One chosen Hadith. */
    SINGLE("single"),

    /** Every Hadith whose chain passes through one narrator, e.g. a pivot (مدار). */
    NARRATOR("narrator");

    companion object {
        fun fromRoute(route: String?) = entries.firstOrNull { it.route == route } ?: DUE
    }
}

/** How much of the card is revealed: the recall prompt, the Matn, then the whole chain. */
enum class CardStage { RECALL, MATN, SANAD }

data class ReviewCard(
    val hadithId: Long,
    val hadithNumber: Int,
    val sourceBook: String,
    val grade: String,
    val matn: String,
    val links: List<SanadLink>,
)

fun HadithWithFullSanad.toReviewCard() = ReviewCard(
    hadithId = hadith.id,
    hadithNumber = hadith.hadithNumber,
    sourceBook = hadith.sourceBook,
    grade = hadith.grade,
    matn = hadith.matnWithTashkeel,
    links = sanad,
)

data class ReviewSession(
    val mode: ReviewMode,
    val queue: List<ReviewCard>,
    val index: Int = 0,
    val stage: CardStage,
    val matnRating: ReviewRating? = null,
    val sanadRating: ReviewRating? = null,
    /** Narrators the user marked as forgotten or misordered on the revealed chain. */
    val missedNarratorIds: Set<Long> = emptySet(),
    val isSubmitting: Boolean = false,
    val submitError: String? = null,
    /** Chain heatmaps loaded when a card's Sanad was revealed, by Hadith id. */
    val heatmaps: Map<Long, ChainHeatmap> = emptyMap(),
) {
    val current: ReviewCard? get() = queue.getOrNull(index)
    val isFinished: Boolean get() = index >= queue.size
    val canSubmit: Boolean get() = matnRating != null && sanadRating != null && !isSubmitting
}

sealed interface ReviewEvent {
    /** Taps the card: recall prompt -> Matn -> Sanad. */
    data object Reveal : ReviewEvent
    data class RateMatn(val rating: ReviewRating) : ReviewEvent
    data class RateSanad(val rating: ReviewRating) : ReviewEvent
    data class ToggleMissed(val narratorId: Long) : ReviewEvent
    data object Submit : ReviewEvent
    data object DismissError : ReviewEvent
}

class ReviewViewModel(
    private val mode: ReviewMode,
    /** The Hadith for [ReviewMode.SINGLE] and [ReviewMode.NEW], the narrator for [ReviewMode.NARRATOR], unused otherwise. */
    private val targetId: Long?,
    private val getDue: GetDueHadithsUseCase,
    private val getUnseen: GetUnseenHadithsUseCase,
    private val hadiths: HadithRepository,
    private val submitReview: SubmitReviewUseCase,
    private val getHeatmap: GetNarratorChainHeatmapUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<ReviewSession>>(UiState.Loading)
    val state: StateFlow<UiState<ReviewSession>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val queue = when (mode) {
                    ReviewMode.DUE -> getDue().map { it.hadith.toReviewCard() }
                    ReviewMode.NEW ->
                        if (targetId != null) {
                            listOfNotNull(hadiths.getWithSanad(targetId)?.toReviewCard())
                        } else {
                            getUnseen(limit = NEW_BATCH_SIZE).map { it.toReviewCard() }
                        }
                    ReviewMode.SINGLE -> listOfNotNull(targetId?.let { hadiths.getWithSanad(it) }?.toReviewCard())
                    ReviewMode.NARRATOR -> targetId?.let { hadiths.getThroughNarrator(it) }.orEmpty().map { it.toReviewCard() }
                }
                if (queue.isEmpty() && (mode == ReviewMode.SINGLE || (mode == ReviewMode.NEW && targetId != null))) {
                    UiState.Error("Hadith $targetId not found")
                } else {
                    UiState.Success(ReviewSession(mode, queue, stage = initialStage()))
                }
            } catch (e: Exception) {
                UiState.Error(e.message.orEmpty())
            }
        }
    }

    fun onEvent(event: ReviewEvent) {
        when (event) {
            ReviewEvent.Reveal -> {
                update { s ->
                    s.copy(stage = CardStage.entries[(s.stage.ordinal + 1).coerceAtMost(CardStage.SANAD.ordinal)])
                }
                loadHeatmapIfRevealed()
            }
            is ReviewEvent.RateMatn -> update { it.copy(matnRating = event.rating) }
            is ReviewEvent.RateSanad -> update { it.copy(sanadRating = event.rating) }
            is ReviewEvent.ToggleMissed -> update { s ->
                s.copy(
                    missedNarratorIds = if (event.narratorId in s.missedNarratorIds) {
                        s.missedNarratorIds - event.narratorId
                    } else {
                        s.missedNarratorIds + event.narratorId
                    },
                )
            }
            ReviewEvent.DismissError -> update { it.copy(submitError = null) }
            ReviewEvent.Submit -> submit()
        }
    }

    /** Once the chain is on screen, fetch how well each of its narrators has been recalled so far. */
    private fun loadHeatmapIfRevealed() {
        val session = (_state.value as? UiState.Success)?.data ?: return
        val card = session.current ?: return
        if (session.stage != CardStage.SANAD || card.hadithId in session.heatmaps) return
        viewModelScope.launch {
            try {
                val heatmap = getHeatmap(card.hadithId)
                update { it.copy(heatmaps = it.heatmaps + (card.hadithId to heatmap)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The heatmap is an extra; the review itself works without it.
            }
        }
    }

    private fun submit() {
        val session = (_state.value as? UiState.Success)?.data ?: return
        val card = session.current
        val matn = session.matnRating
        val sanad = session.sanadRating
        if (card == null || matn == null || sanad == null || session.isSubmitting) return

        update { it.copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                submitReview(card.hadithId, matn, sanad, session.missedNarratorIds)
                update { it.advance() }
            } catch (e: Exception) {
                update { it.copy(isSubmitting = false, submitError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun ReviewSession.advance() = copy(
        index = index + 1,
        stage = initialStage(),
        matnRating = null,
        sanadRating = null,
        missedNarratorIds = emptySet(),
        isSubmitting = false,
        submitError = null,
    )

    private fun initialStage() = if (mode == ReviewMode.NEW) CardStage.MATN else CardStage.RECALL

    private inline fun update(transform: (ReviewSession) -> ReviewSession) {
        _state.value = (_state.value as? UiState.Success)?.let { UiState.Success(transform(it.data)) } ?: _state.value
    }

    companion object {
        /** How many never-seen Hadiths one "new memorization" session offers. */
        const val NEW_BATCH_SIZE = 5
    }
}
