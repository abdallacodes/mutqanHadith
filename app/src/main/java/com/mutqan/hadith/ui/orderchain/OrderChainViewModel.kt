package com.mutqan.hadith.ui.orderchain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.drill.ChainOrderState
import com.mutqan.hadith.domain.drill.DrillAccess
import com.mutqan.hadith.domain.drill.SanadDrill
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/** What was recorded when the round ended: how the order went, and who was out of place on the way. */
data class OrderChainResult(
    val rating: ReviewRating,
    val missedNames: List<String>,
    /** The user gave up and was shown the order, rather than getting it right. */
    val revealed: Boolean,
)

data class OrderChainUi(
    val hadithId: Long,
    val hadithNumber: Int,
    val sourceBook: String,
    val matn: String,
    val chain: ChainOrderState,
    /** Incremented on every check that found a mistake, so that repeating the same mistake shakes the rows again. */
    val feedbackTick: Int = 0,
    val isSubmitting: Boolean = false,
    /** Set once the round has ended and been recorded in the narrator ledger. */
    val result: OrderChainResult? = null,
    val submitError: String? = null,
)

sealed interface OrderChainEvent {
    /** Taps the narrator at a place: selects it, or swaps it with the one selected. */
    data class Tap(val place: Int) : OrderChainEvent
    data class Move(val place: Int, val delta: Int) : OrderChainEvent
    data object Verify : OrderChainEvent
    data object Reveal : OrderChainEvent
    data object NewRound : OrderChainEvent
    data object DismissError : OrderChainEvent
}

class OrderChainViewModel(
    private val hadithId: Long,
    private val hadiths: HadithRepository,
    private val submitReview: SubmitReviewUseCase,
    private val checkAccess: CheckSanadDrillAccessUseCase,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<OrderChainUi>>(UiState.Loading)
    val state: StateFlow<UiState<OrderChainUi>> = _state.asStateFlow()

    private var links: List<SanadLink> = emptyList()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val hadith = hadiths.getWithSanad(hadithId)
                val access = hadith?.let { checkAccess(SanadDrill.ORDER_CHAIN, it) }
                if (hadith == null) {
                    UiState.Error("Hadith $hadithId not found")
                } else if (access is DrillAccess.Closed) {
                    UiState.Blocked(access)
                } else {
                    links = hadith.sanad
                    UiState.Success(
                        OrderChainUi(
                            hadithId = hadithId,
                            hadithNumber = hadith.hadith.hadithNumber,
                            sourceBook = hadith.hadith.sourceBook,
                            matn = hadith.hadith.matnWithTashkeel,
                            chain = ChainOrderState.start(links, random),
                        ),
                    )
                }
            } catch (e: Exception) {
                UiState.Error(e.message.orEmpty())
            }
        }
    }

    fun onEvent(event: OrderChainEvent) {
        when (event) {
            is OrderChainEvent.Tap -> edit { it.tap(event.place) }
            is OrderChainEvent.Move -> edit { it.move(event.place, event.delta) }
            OrderChainEvent.Verify -> finish(reveal = false)
            OrderChainEvent.Reveal -> finish(reveal = true)
            OrderChainEvent.NewRound -> update { ui ->
                if (ui.result == null) ui else ui.copy(chain = ChainOrderState.start(links, random), result = null, submitError = null)
            }
            OrderChainEvent.DismissError -> update { it.copy(submitError = null) }
        }
    }

    /** Checks the order, or gives it away. A round that ends is recorded; a check that finds mistakes only marks them. */
    private fun finish(reveal: Boolean) {
        val ui = (_state.value as? UiState.Success)?.data ?: return
        if (ui.isSubmitting || ui.result != null) return

        val next = if (reveal) ui.chain.reveal() else ui.chain.verify()
        if (!next.isSolved) {
            update { it.copy(chain = next, feedbackTick = it.feedbackTick + 1) }
            return
        }

        update { it.copy(isSubmitting = true, submitError = null) }
        viewModelScope.launch {
            try {
                // Only the Sanad was tested: the Matn is the anchor shown, not recalled.
                submitReview(ui.hadithId, null, next.rating(), next.missedNarratorIds)
                val names = next.target.map { it.narrator }.filter { it.id in next.missedNarratorIds }.map { it.primaryName }
                update {
                    it.copy(
                        chain = next,
                        isSubmitting = false,
                        result = OrderChainResult(next.rating(), names, revealed = reveal),
                    )
                }
            } catch (e: Exception) {
                // The round stays open, as it was, so that checking it again records it.
                update { it.copy(isSubmitting = false, submitError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private inline fun edit(transform: (ChainOrderState) -> ChainOrderState) =
        update { ui -> if (ui.result != null || ui.isSubmitting) ui else ui.copy(chain = transform(ui.chain)) }

    private inline fun update(transform: (OrderChainUi) -> OrderChainUi) {
        _state.value = (_state.value as? UiState.Success)?.let { UiState.Success(transform(it.data)) } ?: _state.value
    }
}
