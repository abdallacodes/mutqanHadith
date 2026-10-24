package com.mutqan.hadith.domain.drill

import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.model.ReviewRating
import kotlin.math.ceil
import kotlin.random.Random

/** A draggable narrator. [chipId] is the chain position the chip truly belongs to, unique per chip. */
data class BuilderChip(val chipId: Int, val narrator: NarratorEntity)

/** Outcome of one placement, for the immediate green/red feedback. */
data class PlacementFeedback(val slotIndex: Int, val correct: Boolean)

/**
 * Immutable state of the Sanad builder drill. [slots] is the target chain (index 0 = the
 * collector's teacher, last = the Sahabi); [placed] holds what the user put in each slot and
 * [tray] the chips still waiting. Every wrong placement is remembered in
 * [misplacedNarratorIds] even if the user later corrects it, because that is what the narrator
 * ledger should learn from.
 */
data class SanadBuilderState(
    val slots: List<SanadLink>,
    val placed: List<BuilderChip?>,
    val tray: List<BuilderChip>,
    val selectedSlot: Int? = null,
    val misplacedNarratorIds: Set<Long> = emptySet(),
) {
    val isComplete: Boolean get() = placed.all { it != null }

    val isAllCorrect: Boolean get() = slots.indices.all { isCorrect(it) }

    fun isCorrect(slot: Int): Boolean = placed[slot]?.narrator?.id == slots[slot].narrator.id

    /** Taps an empty slot to choose where the next chip lands; tapping it again deselects. */
    fun selectSlot(index: Int): SanadBuilderState =
        if (placed[index] != null) this else copy(selectedSlot = index.takeUnless { it == selectedSlot })

    /**
     * Places the tray chip [chipId] into the selected slot, or the first empty one.
     * Returns the new state and the feedback to show, or null if nothing could be placed.
     */
    fun place(chipId: Int): Pair<SanadBuilderState, PlacementFeedback?> {
        val chip = tray.firstOrNull { it.chipId == chipId } ?: return this to null
        val target = selectedSlot?.takeIf { placed[it] == null } ?: placed.indexOfFirst { it == null }
        if (target < 0) return this to null

        val correct = chip.narrator.id == slots[target].narrator.id
        val next = copy(
            placed = placed.toMutableList().also { it[target] = chip },
            tray = tray - chip,
            selectedSlot = null,
            misplacedNarratorIds = if (correct) misplacedNarratorIds else misplacedNarratorIds + chip.narrator.id,
        )
        return next to PlacementFeedback(target, correct)
    }

    /** Sends a wrongly placed chip back to the tray. Correct placements are locked. */
    fun removeFromSlot(index: Int): SanadBuilderState {
        val chip = placed[index] ?: return this
        if (isCorrect(index)) return this
        return copy(
            placed = placed.toMutableList().also { it[index] = null },
            tray = tray + chip,
            selectedSlot = null,
        )
    }

    /** Gives up: fills the whole chain correctly and counts every narrator not yet right as missed. */
    fun reveal(): SanadBuilderState {
        val missed = slots.indices.filterNot(::isCorrect).map { slots[it].narrator.id }
        return copy(
            placed = slots.mapIndexed { i, link -> BuilderChip(i, link.narrator) },
            tray = emptyList(),
            selectedSlot = null,
            misplacedNarratorIds = misplacedNarratorIds + missed,
        )
    }

    /** GOOD for a clean build, HARD for a few slips (up to a third of the chain), AGAIN beyond that. */
    fun rating(): ReviewRating {
        val mistakes = misplacedNarratorIds.size
        return when {
            mistakes == 0 -> ReviewRating.GOOD
            mistakes <= ceil(slots.size / 3.0).toInt() -> ReviewRating.HARD
            else -> ReviewRating.AGAIN
        }
    }

    companion object {
        /** Starts a build with the chips shuffled; for chains longer than one it never starts already solved. */
        fun start(chain: List<SanadLink>, random: Random = Random.Default): SanadBuilderState {
            val chips = chain.mapIndexed { i, link -> BuilderChip(i, link.narrator) }
            var tray = chips.shuffled(random)
            if (chips.size > 1) {
                var attempts = 0
                while (tray == chips && attempts++ < 10) tray = chips.shuffled(random)
                if (tray == chips) tray = chips.drop(1) + chips.first()
            }
            return SanadBuilderState(slots = chain, placed = List(chain.size) { null }, tray = tray)
        }
    }
}
