package com.mutqan.hadith.domain.drill

import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.narrator.NarratorNormalizer
import kotlin.math.ceil
import kotlin.random.Random

/** GOOD for a clean chain, HARD for a few slips (up to a third of the chain), AGAIN beyond that. */
fun ratingForChainMistakes(mistakes: Int, chainSize: Int): ReviewRating = when {
    mistakes == 0 -> ReviewRating.GOOD
    mistakes <= ceil(chainSize / 3.0).toInt() -> ReviewRating.HARD
    else -> ReviewRating.AGAIN
}

/**
 * A narrator on the move. [chipId] is unique within the chain and is the place the chip truly belongs to (0 = first to
 * receive the hadith); [personId] is the canonical id of the man he is, so that a chain which names one man twice (or a
 * man under two spellings) can be put in order either way round.
 */
data class OrderChip(val chipId: Int, val narrator: NarratorEntity, val personId: Long)

/** The verdict on one check of the order: whether each place holds the right man. */
data class ChainVerdict(val correctPlaces: List<Boolean>) {
    val isCorrect: Boolean get() = correctPlaces.all { it }

    val misplacedCount: Int get() = correctPlaces.count { !it }

    val correctCount: Int get() = correctPlaces.count { it }
}

/**
 * Immutable state of the order-the-chain drill. The chain is shown the way it was received, from the Companion down to
 * the collector's teacher ([target]); [order] is what the user has made of the scrambled narrators, top first.
 *
 * Narrators are swapped, not placed: tapping one and then another trades their places ([tap]), or [move] shifts one
 * place up or down. Nothing is judged until [verify]; every place that was wrong when the order was checked is
 * remembered in [missedNarratorIds] even if the user then corrects it, because that is what the narrator ledger should
 * learn from. An order that checked out is final.
 */
data class ChainOrderState(
    val target: List<OrderChip>,
    val order: List<OrderChip>,
    val selected: Int? = null,
    val verdict: ChainVerdict? = null,
    val missedNarratorIds: Set<Long> = emptySet(),
    /** How many times the order was checked. */
    val checks: Int = 0,
) {
    val isSolved: Boolean get() = verdict?.isCorrect == true

    /** Whether [place] holds the man who belongs there. */
    fun isCorrectAt(place: Int): Boolean = order[place].personId == target[place].personId

    /** Whether the order, as it stands now, is right. A man the chain names twice may stand at either of his places. */
    val isInOrder: Boolean get() = order.indices.all(::isCorrectAt)

    /** Taps the narrator at [place]: selects it, or swaps it with the one already selected; tapping it again deselects. */
    fun tap(place: Int): ChainOrderState = when {
        isSolved || place !in order.indices -> this
        selected == null -> copy(selected = place)
        selected == place -> copy(selected = null)
        else -> swap(selected, place)
    }

    /** Moves the narrator at [place] one place up ([delta] = -1) or down (+1). */
    fun move(place: Int, delta: Int): ChainOrderState {
        val to = place + delta
        return if (isSolved || place !in order.indices || to !in order.indices) this else swap(place, to)
    }

    private fun swap(a: Int, b: Int): ChainOrderState {
        val next = order.toMutableList().also { it[a] = order[b]; it[b] = order[a] }
        // Any change makes the last verdict stale: it was about an arrangement that no longer stands.
        return copy(order = next, selected = null, verdict = null)
    }

    /** Checks the order: marks every place right or wrong, and remembers the narrators who were not where they belong. */
    fun verify(): ChainOrderState {
        if (isSolved) return this
        val verdict = ChainVerdict(order.indices.map(::isCorrectAt))
        val wrong = order.filterIndexed { place, _ -> !verdict.correctPlaces[place] }.map { it.narrator.id }
        return copy(selected = null, verdict = verdict, missedNarratorIds = missedNarratorIds + wrong, checks = checks + 1)
    }

    /** Gives up: sets the chain right and counts every narrator not yet in place as missed. */
    fun reveal(): ChainOrderState {
        if (isSolved) return this
        val wrong = order.filterIndexed { place, _ -> !isCorrectAt(place) }.map { it.narrator.id }
        return copy(
            order = target,
            selected = null,
            verdict = ChainVerdict(List(target.size) { true }),
            missedNarratorIds = missedNarratorIds + wrong,
            checks = checks + 1,
        )
    }

    fun rating(): ReviewRating = ratingForChainMistakes(missedNarratorIds.size, target.size)

    /**
     * Whether the chip needs more than its name to be told apart: another narrator of the chain is called the same, or
     * the name reads as the beginning of his («سفيان» and «سفيان الثوري»), though the two are not one man.
     */
    fun needsQualifier(chip: OrderChip): Boolean = target.any { other ->
        other.personId != chip.personId && NarratorNormalizer.mayBeSamePerson(other.narrator.primaryName, chip.narrator.primaryName)
    }

    companion object {
        /**
         * Starts a round with the narrators of [chain] (given the way the books write it, the collector's teacher
         * first) scrambled. The order never starts out solved, unless the chain cannot help it (one man throughout).
         */
        fun start(chain: List<SanadLink>, random: Random = Random.Default): ChainOrderState {
            val directory = NarratorDirectory.of(chain.map { it.narrator })
            val target = chain.reversed().mapIndexed { place, link ->
                OrderChip(place, link.narrator, directory.canonicalId(link.narrator.id))
            }
            fun solved(order: List<OrderChip>) = order.indices.all { order[it].personId == target[it].personId }

            var order = target.shuffled(random)
            var attempts = 0
            while (solved(order) && attempts++ < 10) order = target.shuffled(random)
            if (solved(order) && target.size > 1) order = target.drop(1) + target.first()
            return ChainOrderState(target = target, order = order)
        }
    }
}
