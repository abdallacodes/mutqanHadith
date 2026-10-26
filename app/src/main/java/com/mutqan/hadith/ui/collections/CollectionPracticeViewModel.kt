package com.mutqan.hadith.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.repository.CollectionRepository
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.review.CardStage
import com.mutqan.hadith.ui.review.HintKind
import com.mutqan.hadith.ui.review.ReviewCard
import com.mutqan.hadith.ui.review.hintKinds
import com.mutqan.hadith.ui.review.toReviewCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A practice round over the hadiths of one collection, the «ورد مخصص». The cards are the same as in a review, but what the
 * user answers is only for the session: the deck remembers who needed another look so it can be gone through again, and
 * nothing is kept. [recalled] and [needsLook] are this round's tally.
 */
data class PracticeSession(
    val title: String,
    /** The cards of this round, in the order the collection lists them. */
    val deck: List<ReviewCard>,
    val index: Int = 0,
    val stage: CardStage = CardStage.RECALL,
    val hideTashkeel: Boolean = false,
    val termsRevealed: Boolean = false,
    val hintsUsed: Int = 0,
    val recalled: Int = 0,
    /** The hadiths the user said needed another look in this round. */
    val needsLook: List<Long> = emptyList(),
    val round: Int = 1,
) {
    val current: ReviewCard? get() = deck.getOrNull(index)
    val isFinished: Boolean get() = index >= deck.size

    /** A card is judged once all of it is on screen: the matn at layer 1, the chain from layer 2. */
    val canJudge: Boolean get() = current?.let { stage == it.finalStage } == true

    val shownHints: List<HintKind> get() = current?.hintKinds?.take(hintsUsed).orEmpty()

    val canHint: Boolean
        get() {
            val card = current ?: return false
            return !card.activeLayer.opensSanad && stage == CardStage.RECALL && hintsUsed < card.hintKinds.size
        }
}

sealed interface PracticeEvent {
    /** Taps the card: recall prompt -> matn -> chain. */
    data object Reveal : PracticeEvent
    data object Hint : PracticeEvent
    data object ToggleTashkeel : PracticeEvent
    data object RevealTerms : PracticeEvent

    /** "I knew it": on to the next card. */
    data object Recalled : PracticeEvent

    /** "Needs another look": on to the next card, and it will be offered again at the end. */
    data object NeedsLook : PracticeEvent

    /** Goes through the cards that needed another look, as a new round. */
    data object PracticeAgain : PracticeEvent
}

/**
 * Loads a collection into a practice session and steps through it. It can only read: it is given the hadith and
 * collection repositories and the layer lookup, and nothing that records a review, so a session cannot change a mastery
 * record, a stability, a due date or the history of any narrator, and it does not take a hadith into the user's study.
 */
class CollectionPracticeViewModel(
    private val collectionId: Long,
    private val collections: CollectionRepository,
    private val hadiths: HadithRepository,
    private val getLayers: GetLayerStatusesUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<PracticeSession>>(UiState.Loading)
    val state: StateFlow<UiState<PracticeSession>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val collection = collections.observe(collectionId).first()
                if (collection == null) {
                    UiState.Error("Collection $collectionId not found")
                } else {
                    val ids = collections.getHadithIds(collectionId)
                    val byId = hadiths.getWithSanad(ids).associateBy { it.hadith.id }
                    val layers = getLayers(ids)
                    val deck = ids.mapNotNull(byId::get).map { it.toReviewCard(layers[it.hadith.id] ?: LayerStatus.Fresh) }
                    UiState.Success(PracticeSession(collection.title, deck))
                }
            } catch (e: Exception) {
                UiState.Error(e.message.orEmpty())
            }
        }
    }

    fun onEvent(event: PracticeEvent) {
        when (event) {
            PracticeEvent.Reveal -> update { s ->
                val last = s.current?.finalStage ?: return@update s
                s.copy(stage = CardStage.entries[(s.stage.ordinal + 1).coerceAtMost(last.ordinal)])
            }
            PracticeEvent.Hint -> update { if (it.canHint) it.copy(hintsUsed = it.hintsUsed + 1) else it }
            PracticeEvent.ToggleTashkeel -> update { it.copy(hideTashkeel = !it.hideTashkeel) }
            PracticeEvent.RevealTerms -> update { it.copy(termsRevealed = true) }
            PracticeEvent.Recalled -> update { s -> if (s.canJudge) s.next(recalled = true) else s }
            PracticeEvent.NeedsLook -> update { s -> if (s.canJudge) s.next(recalled = false) else s }
            PracticeEvent.PracticeAgain -> update { s ->
                if (!s.isFinished || s.needsLook.isEmpty()) {
                    s
                } else {
                    PracticeSession(
                        title = s.title,
                        deck = s.deck.filter { it.hadithId in s.needsLook },
                        hideTashkeel = s.hideTashkeel,
                        round = s.round + 1,
                    )
                }
            }
        }
    }

    private fun PracticeSession.next(recalled: Boolean): PracticeSession {
        val card = current ?: return this
        return copy(
            index = index + 1,
            stage = CardStage.RECALL,
            termsRevealed = false,
            hintsUsed = 0,
            recalled = this.recalled + if (recalled) 1 else 0,
            needsLook = if (recalled) needsLook else needsLook + card.hadithId,
        )
    }

    private inline fun update(transform: (PracticeSession) -> PracticeSession) {
        _state.value = (_state.value as? UiState.Success)?.let { UiState.Success(transform(it.data)) } ?: _state.value
    }
}
