package com.mutqan.hadith.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
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

/**
 * One hadith to review. The [layer] it is in decides how far the card goes: before the Sanad tree opens, the chain is
 * neither shown nor rated, and the transmission terms are only asked for once the last layer opens.
 */
data class ReviewCard(
    val hadithId: Long,
    val hadithNumber: Int,
    val sourceBook: String,
    val grade: String,
    val matn: String,
    val links: List<SanadLink>,
    val sahabi: String = "",
    val takhrij: String = "",
    val layer: LayerStatus = LayerStatus.Fresh,
) {
    val activeLayer: MemorizationLayer get() = layer.layer

    /** The last stage this card reaches: the Matn at layer 1, the chain from layer 2. */
    val finalStage: CardStage get() = if (activeLayer.opensSanad) CardStage.SANAD else CardStage.MATN
}

fun HadithWithFullSanad.toReviewCard(layer: LayerStatus = LayerStatus.Fresh) = ReviewCard(
    hadithId = hadith.id,
    hadithNumber = hadith.hadithNumber,
    sourceBook = hadith.sourceBook,
    grade = hadith.grade,
    matn = hadith.matnWithTashkeel,
    links = sanad,
    sahabi = hadith.sahabi.ifBlank { sanad.lastOrNull()?.narrator?.primaryName.orEmpty() },
    takhrij = hadith.takhrij,
    layer = layer,
)

/** A review that opened [layer] for a hadith. */
data class LayerUnlock(val hadithId: Long, val hadithNumber: Int, val layer: MemorizationLayer)

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
    /** Layer 1 cards show the Matn without its tashkeel, to recall the vowelling too; kept for the whole session. */
    val hideTashkeel: Boolean = false,
    /** Whether the transmission terms of the current card are shown; they are asked for before being shown. */
    val termsRevealed: Boolean = false,
    /** The layer the previous review opened, shown until dismissed or the next card is rated. */
    val unlock: LayerUnlock? = null,
    /** Every layer opened in this session, for the summary. */
    val unlocks: List<LayerUnlock> = emptyList(),
    /** Hadiths left out of a narrator's review because their Sanad tree is not open yet. */
    val lockedSkipped: Int = 0,
) {
    val current: ReviewCard? get() = queue.getOrNull(index)
    val isFinished: Boolean get() = index >= queue.size

    /** The Sanad is only rated once its layer is open; before that a card is graded on its Matn alone. */
    val canSubmit: Boolean
        get() {
            val card = current ?: return false
            val rated = matnRating != null && (!card.activeLayer.opensSanad || sanadRating != null)
            return rated && !isSubmitting
        }
}

sealed interface ReviewEvent {
    /** Taps the card: recall prompt -> Matn -> Sanad. */
    data object Reveal : ReviewEvent
    data class RateMatn(val rating: ReviewRating) : ReviewEvent
    data class RateSanad(val rating: ReviewRating) : ReviewEvent
    data class ToggleMissed(val narratorId: Long) : ReviewEvent
    data object Submit : ReviewEvent
    data object DismissError : ReviewEvent

    /** Hides or shows the tashkeel of the Matn on layer 1 cards. */
    data object ToggleTashkeel : ReviewEvent

    /** Shows the transmission terms of a layer 3 card, after the user tried to recall them. */
    data object RevealTerms : ReviewEvent

    /** Closes the banner announcing a newly opened layer. */
    data object DismissUnlock : ReviewEvent
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
    private val getLayers: GetLayerStatusesUseCase,
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
                val found = when (mode) {
                    ReviewMode.DUE -> getDue().map { it.hadith }
                    ReviewMode.NEW ->
                        if (targetId != null) {
                            listOfNotNull(hadiths.getWithSanad(targetId))
                        } else {
                            getUnseen(limit = NEW_BATCH_SIZE)
                        }
                    ReviewMode.SINGLE -> listOfNotNull(targetId?.let { hadiths.getWithSanad(it) })
                    ReviewMode.NARRATOR -> targetId?.let { hadiths.getThroughNarrator(it) }.orEmpty()
                }
                val layers = getLayers(found.map { it.hadith.id })
                var cards = found.map { it.toReviewCard(layers[it.hadith.id] ?: LayerStatus.Fresh) }
                // A narrator's hadiths are reviewed through their chains, which only the hadiths with an open Sanad tree have.
                val lockedSkipped = if (mode == ReviewMode.NARRATOR) cards.count { !it.activeLayer.opensSanad } else 0
                if (mode == ReviewMode.NARRATOR) cards = cards.filter { it.activeLayer.opensSanad }
                if (cards.isEmpty() && (mode == ReviewMode.SINGLE || (mode == ReviewMode.NEW && targetId != null))) {
                    UiState.Error("Hadith $targetId not found")
                } else {
                    UiState.Success(ReviewSession(mode, cards, stage = initialStage(), lockedSkipped = lockedSkipped))
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
                    val last = s.current?.finalStage ?: CardStage.SANAD
                    s.copy(stage = CardStage.entries[(s.stage.ordinal + 1).coerceAtMost(last.ordinal)])
                }
                loadHeatmapIfRevealed()
            }
            ReviewEvent.ToggleTashkeel -> update { it.copy(hideTashkeel = !it.hideTashkeel) }
            ReviewEvent.RevealTerms -> update { it.copy(termsRevealed = true) }
            ReviewEvent.DismissUnlock -> update { it.copy(unlock = null) }
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

    /** Once the chain is on screen at the last layer, fetch how well each of its narrators has been recalled so far. */
    private fun loadHeatmapIfRevealed() {
        val session = (_state.value as? UiState.Success)?.data ?: return
        val card = session.current ?: return
        if (session.stage != CardStage.SANAD || !card.activeLayer.opensTerms || card.hadithId in session.heatmaps) return
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
        // Before the Sanad tree opens the chain is not part of the review: only the Matn is graded.
        val sanad = if (card?.activeLayer?.opensSanad == true) session.sanadRating else null
        if (card == null || matn == null || (card.activeLayer.opensSanad && sanad == null) || session.isSubmitting) return

        update { it.copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                val outcome = submitReview(card.hadithId, matn, sanad, session.missedNarratorIds)
                val unlock = outcome.unlockedLayer?.let { LayerUnlock(card.hadithId, card.hadithNumber, it) }
                update { it.advance(unlock) }
            } catch (e: Exception) {
                update { it.copy(isSubmitting = false, submitError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun ReviewSession.advance(unlock: LayerUnlock?) = copy(
        index = index + 1,
        stage = initialStage(),
        matnRating = null,
        sanadRating = null,
        missedNarratorIds = emptySet(),
        termsRevealed = false,
        unlock = unlock,
        unlocks = if (unlock != null) unlocks + unlock else unlocks,
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
