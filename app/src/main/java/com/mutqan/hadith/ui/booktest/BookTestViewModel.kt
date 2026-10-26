package com.mutqan.hadith.ui.booktest

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.model.BookTestResult
import com.mutqan.hadith.domain.model.CurriculumOrder
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.PlacementUseCase
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.placement.CheckCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The test on the whole book, in the order it is taught: [index] is the hadith on screen, [revealed] whether its text
 * is shown, and [resting] that a sitting is over and the learner chooses to go on or to stop. [finished] ends it.
 */
data class BookTestUi(
    val cards: List<CheckCard>,
    val index: Int = 0,
    val revealed: Boolean = false,
    val tally: Map<RecallBand, Int> = emptyMap(),
    val notMemorized: Int = 0,
    val resting: Boolean = false,
    val finished: Boolean = false,
    val isBusy: Boolean = false,
) {
    val card: CheckCard? get() = cards.getOrNull(index)

    val result: BookTestResult get() = BookTestResult(tally, notMemorized)

    val answered: Int get() = result.total
}

class BookTestViewModel(
    private val placement: PlacementUseCase,
    private val hadiths: HadithRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<BookTestUi>>(UiState.Loading)
    val state: StateFlow<UiState<BookTestUi>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val cards = hadiths.getAll().map { it.hadith }.sortedWith(CurriculumOrder).map { h ->
                    CheckCard(h.id, h.hadithNumber, cueOf(h.matnWithTashkeel), h.matnWithTashkeel, h.sahabi)
                }
                UiState.Success(BookTestUi(cards, finished = cards.isEmpty()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Error(e.message.orEmpty())
            }
        }
    }

    fun reveal() = update { if (it.card != null && !it.resting && !it.finished) it.copy(revealed = true) else it }

    /** Grades the hadith on screen; null is "not memorized". After every [SITTING] answers the test pauses. */
    fun answer(band: RecallBand?) {
        val ui = current() ?: return
        val card = ui.card ?: return
        if (!ui.revealed || ui.resting || ui.finished || ui.isBusy) return
        update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                placement.grade(card.hadithId, band)
                update { s ->
                    val tally = if (band != null) s.tally + (band to (s.tally[band] ?: 0) + 1) else s.tally
                    val next = s.index + 1
                    val answered = s.answered + 1
                    s.copy(
                        index = next,
                        revealed = false,
                        tally = tally,
                        notMemorized = s.notMemorized + if (band == null) 1 else 0,
                        finished = next >= s.cards.size,
                        resting = next < s.cards.size && answered % SITTING == 0,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The answer was not written, so the hadith stays on screen to be answered again.
            } finally {
                update { it.copy(isBusy = false) }
            }
        }
    }

    /** Goes on with the next sitting. */
    fun continueTest() = update { it.copy(resting = false) }

    /** Ends the test here; what was answered stays answered, and the score is over those hadiths. */
    fun stop() = update { it.copy(resting = false, finished = true) }

    private fun current(): BookTestUi? = (_state.value as? UiState.Success)?.data

    private fun update(transform: (BookTestUi) -> BookTestUi) =
        _state.update { if (it is UiState.Success) UiState.Success(transform(it.data)) else it }

    companion object {
        /** How many hadiths make a sitting: the test pauses after each, and stopping there is fine. */
        const val SITTING = 10
        private const val CUE_WORDS = 6

        /** The first words of the hadith as they are written, then an ellipsis when there are more. */
        fun cueOf(matn: String): String {
            val words = matn.trim().split(Regex("\\s+"))
            return if (words.size <= CUE_WORDS) matn.trim() else words.take(CUE_WORDS).joinToString(" ") + "…"
        }
    }
}
