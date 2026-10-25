package com.mutqan.hadith.ui.missinglink

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.drill.DrillAccess
import com.mutqan.hadith.domain.drill.MissingLinkRound
import com.mutqan.hadith.domain.drill.MissingLinkRoundFactory
import com.mutqan.hadith.domain.drill.SanadDrill
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.usecase.CheckSanadDrillAccessUseCase
import com.mutqan.hadith.domain.usecase.LinkDrillOutcome
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class MissingLinkUi(
    val hadithNumber: Int,
    val sourceBook: String,
    val matn: String,
    val round: MissingLinkRound,
    /** The option the user tapped; feedback is immediate and does not wait for the ledger write. */
    val chosenId: Long? = null,
    /** The ledger tally after the answer was recorded, once the write finished. */
    val outcome: LinkDrillOutcome? = null,
    val roundsPlayed: Int = 0,
    val correctCount: Int = 0,
    val submitError: String? = null,
) {
    val isAnswered: Boolean get() = chosenId != null
    val isCorrect: Boolean? get() = chosenId?.let { it == round.answer.id }
}

sealed interface MissingLinkEvent {
    data class Choose(val narratorId: Long) : MissingLinkEvent
    data object Next : MissingLinkEvent
    data object DismissError : MissingLinkEvent
}

class MissingLinkViewModel(
    private val hadithId: Long,
    private val hadiths: HadithRepository,
    private val narrators: NarratorRepository,
    private val submitDrill: SubmitLinkDrillUseCase,
    private val checkAccess: CheckSanadDrillAccessUseCase,
    private val random: Random = Random.Default,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<MissingLinkUi>>(UiState.Loading)
    val state: StateFlow<UiState<MissingLinkUi>> = _state.asStateFlow()

    private lateinit var hadith: HadithWithFullSanad
    private lateinit var allNarrators: List<NarratorEntity>

    init {
        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = try {
                val found = hadiths.getWithSanad(hadithId)
                val access = found?.let { checkAccess(SanadDrill.MISSING_NARRATOR, it) }
                if (found == null) {
                    UiState.Error("Hadith $hadithId not found")
                } else if (access is DrillAccess.Closed) {
                    UiState.Blocked(access)
                } else {
                    hadith = found
                    allNarrators = narrators.getAll()
                    UiState.Success(
                        MissingLinkUi(
                            hadithNumber = found.hadith.hadithNumber,
                            sourceBook = found.hadith.sourceBook,
                            matn = found.hadith.matnWithTashkeel,
                            round = MissingLinkRoundFactory.create(found, allNarrators, random),
                        ),
                    )
                }
            } catch (e: Exception) {
                UiState.Error(e.message.orEmpty())
            }
        }
    }

    fun onEvent(event: MissingLinkEvent) {
        when (event) {
            is MissingLinkEvent.Choose -> choose(event.narratorId)
            MissingLinkEvent.Next -> update { ui ->
                if (!ui.isAnswered) ui else ui.copy(
                    round = MissingLinkRoundFactory.create(hadith, allNarrators, random, avoidIndex = ui.round.maskedIndex),
                    chosenId = null,
                    outcome = null,
                    submitError = null,
                )
            }
            MissingLinkEvent.DismissError -> update { it.copy(submitError = null) }
        }
    }

    private fun choose(narratorId: Long) {
        val ui = (_state.value as? UiState.Success)?.data ?: return
        if (ui.isAnswered || ui.round.options.none { it.id == narratorId }) return

        val correct = narratorId == ui.round.answer.id
        update { it.copy(chosenId = narratorId, roundsPlayed = it.roundsPlayed + 1, correctCount = it.correctCount + if (correct) 1 else 0) }
        viewModelScope.launch {
            try {
                // The ledger tracks the narrator that was hidden, whatever distractor was tapped, under the chain's own row.
                val outcome = submitDrill(hadithId, ui.round.hiddenNarrator.id, correct)
                update { if (it.chosenId == narratorId) it.copy(outcome = outcome) else it }
            } catch (e: Exception) {
                update { it.copy(submitError = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private inline fun update(transform: (MissingLinkUi) -> MissingLinkUi) {
        _state.value = (_state.value as? UiState.Success)?.let { UiState.Success(transform(it.data)) } ?: _state.value
    }
}
