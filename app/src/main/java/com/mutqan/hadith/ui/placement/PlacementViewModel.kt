package com.mutqan.hadith.ui.placement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.usecase.PlacementUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One hadith of the self-check: the first words as the cue, the whole text to check against. */
data class CheckCard(val hadithId: Long, val hadithNumber: Int, val cue: String, val matn: String, val sahabi: String)

/** Where the learner is in telling the app their starting point. */
sealed interface PlacementStep {
    /** Choosing between beginner, intermediate and expert. */
    data object Pick : PlacementStep

    /** An intermediate or expert is asked whether to grade each hadith or let the app mark them all. */
    data class Offer(val point: StartingPoint) : PlacementStep

    /** Grading, one hadith at a time: [index] is the card on screen, [revealed] whether its text is shown. */
    data class Check(
        val point: StartingPoint,
        val cards: List<CheckCard>,
        val index: Int = 0,
        val revealed: Boolean = false,
        val tally: Map<RecallBand, Int> = emptyMap(),
    ) : PlacementStep {
        val card: CheckCard get() = cards[index]
    }

    /** Everything is marked; [tally] says how many hadiths went into each band. */
    data class Done(val tally: Map<RecallBand, Int>) : PlacementStep
}

data class PlacementUi(
    val step: PlacementStep = PlacementStep.Pick,
    /** The answer already on record, so that reopening the screen from Settings shows it. */
    val current: StartingPoint? = null,
    val isBusy: Boolean = false,
    /** True once the flow is over and the screen should close. */
    val finished: Boolean = false,
)

class PlacementViewModel(
    private val placement: PlacementUseCase,
    private val hadiths: HadithRepository,
    current: StartingPoint?,
) : ViewModel() {

    private val _state = MutableStateFlow(PlacementUi(current = current))
    val state: StateFlow<PlacementUi> = _state.asStateFlow()

    /** The learner's answer. A beginner is done at once: nothing is marked and the app is as it is for anyone new. */
    fun choose(point: StartingPoint) {
        if (_state.value.isBusy) return
        placement.choose(point)
        _state.update {
            if (point.offersCheck) it.copy(step = PlacementStep.Offer(point), current = point)
            else it.copy(current = point, finished = true)
        }
    }

    /** The self-check was turned down: every hadith still unmarked goes into the starting point's default band. */
    fun skipCheck() {
        val offer = _state.value.step as? PlacementStep.Offer ?: return
        runBusy {
            val marked = placement.placeRemainingByDefault(offer.point)
            val band = offer.point.defaultBand
            _state.update { it.copy(step = PlacementStep.Done(if (band != null && marked > 0) mapOf(band to marked) else emptyMap())) }
        }
    }

    /** Begins the self-check over the hadiths that are not marked yet, in the order they are taught. */
    fun startCheck() {
        val offer = _state.value.step as? PlacementStep.Offer ?: return
        runBusy {
            val cards = hadiths.getUnseen().map { it.hadith }.map { h ->
                CheckCard(h.id, h.hadithNumber, cueOf(h.matnWithTashkeel), h.matnWithTashkeel, h.sahabi)
            }
            val step: PlacementStep = if (cards.isEmpty()) PlacementStep.Done(emptyMap()) else PlacementStep.Check(offer.point, cards)
            _state.update { it.copy(step = step) }
        }
    }

    fun reveal() = _state.update { ui ->
        val check = ui.step as? PlacementStep.Check ?: return@update ui
        ui.copy(step = check.copy(revealed = true))
    }

    /** Grades the hadith on screen at [band], or, for null ("not memorized"), leaves it unmarked; then moves on. */
    fun rate(band: RecallBand?) {
        val check = _state.value.step as? PlacementStep.Check ?: return
        if (!check.revealed) return
        runBusy {
            if (band != null) placement.place(check.card.hadithId, band)
            val tally = if (band != null) check.tally + (band to (check.tally[band] ?: 0) + 1) else check.tally
            val next = check.index + 1
            val step: PlacementStep =
                if (next >= check.cards.size) PlacementStep.Done(tally) else check.copy(index = next, revealed = false, tally = tally)
            _state.update { it.copy(step = step) }
        }
    }

    /** Stops grading: the hadiths not yet shown get the starting point's default band; those already answered stay as answered. */
    fun finishRest() {
        val check = _state.value.step as? PlacementStep.Check ?: return
        val band = check.point.defaultBand ?: return
        runBusy {
            var added = 0
            check.cards.drop(check.index).forEach { if (placement.place(it.hadithId, band)) added++ }
            val tally = if (added > 0) check.tally + (band to (check.tally[band] ?: 0) + added) else check.tally
            _state.update { it.copy(step = PlacementStep.Done(tally)) }
        }
    }

    fun close() = _state.update { it.copy(finished = true) }

    private fun runBusy(block: suspend () -> Unit) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Marking is a convenience: if it fails the learner can try again from Settings, and nothing is half-written.
                _state.update { it.copy(step = PlacementStep.Done(emptyMap())) }
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }

    private companion object {
        const val CUE_WORDS = 6

        /** The first words of the hadith as they are written, then an ellipsis when there are more. */
        fun cueOf(matn: String): String {
            val words = matn.trim().split(Regex("\\s+"))
            return if (words.size <= CUE_WORDS) matn.trim() else words.take(CUE_WORDS).joinToString(" ") + "…"
        }
    }
}
