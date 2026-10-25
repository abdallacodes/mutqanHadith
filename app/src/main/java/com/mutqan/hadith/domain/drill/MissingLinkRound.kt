package com.mutqan.hadith.domain.drill

import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.narrator.CanonicalNarrator
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.narrator.NarratorNormalizer
import kotlin.random.Random

/** A person on an answer card: [id] is his canonical id, a real narrator id that stands for him under every spelling. */
data class DrillChoice(val id: Long, val name: String, val fullName: String, val tier: String) {
    companion object {
        fun of(narrator: CanonicalNarrator) = DrillChoice(narrator.id, narrator.name, narrator.fullName, narrator.tier)
    }
}

/**
 * One question of the missing-narrator drill: [chain] with the node at [maskedIndex] hidden. The choices are people
 * rather than catalog rows, so the same man never stands in two of them nor in the chain and among the choices at once.
 */
data class MissingLinkRound(
    val hadithId: Long,
    val chain: List<SanadLink>,
    val maskedIndex: Int,
    /** The answer plus distractors, already shuffled. */
    val options: List<DrillChoice>,
    /** The man behind the hidden node, as the canonical person he is. */
    val answer: DrillChoice,
    /** Whom the hidden narrator received the hadith from, in this chain; null for the Companion at its end. */
    val narratedFrom: DrillChoice? = null,
    /** Who received it from him; null for the collector's teacher at its head, whose pupil is the collector. */
    val narratedTo: DrillChoice? = null,
) {
    /** The chain's own row for the hidden node: what the narrator ledger is kept under, whichever spelling it has. */
    val hiddenNarrator: NarratorEntity get() = chain[maskedIndex].narrator

    val isCollectorsTeacher: Boolean get() = maskedIndex == 0

    val isLast: Boolean get() = maskedIndex == chain.lastIndex
}

object MissingLinkRoundFactory {
    const val OPTION_COUNT = 4

    /**
     * Masks a random narrator (never [avoidIndex], so consecutive questions differ) and picks distractors from the
     * people outside the chain, preferring the answer's own tier because those are the plausible mix-ups; other tiers
     * only fill the gap.
     *
     * Only a narrator whose name says who he is can be asked for (see [CanonicalNarrator.isResolved]). The pool is of
     * people, not of catalog rows: a row that names a man of the chain under another spelling («ابن عباس» beside «عبد
     * الله بن عباس») is no distractor but the answer itself, and no name that reads as the beginning of a chain
     * narrator's («سفيان» beside «سفيان الثوري») is offered either, for it may be the same man. The options never offer
     * one man twice.
     *
     * @throws IllegalArgumentException if the chain has fewer than [SanadDrill.MISSING_NARRATOR]'s minimum of narrators.
     * @throws IllegalStateException if no narrator of the chain can be asked for, or there are none to use as distractors.
     */
    fun create(
        hadith: HadithWithFullSanad,
        narrators: List<NarratorEntity>,
        random: Random = Random.Default,
        avoidIndex: Int? = null,
        optionCount: Int = OPTION_COUNT,
    ): MissingLinkRound {
        val chain = hadith.sanad
        require(chain.size >= SanadDrill.MISSING_NARRATOR.minLinks) {
            "Hadith ${hadith.hadith.id} has ${chain.size} narrator(s); the drill needs ${SanadDrill.MISSING_NARRATOR.minLinks}"
        }
        val directory = NarratorDirectory.of(narrators + chain.map { it.narrator })
        val people = chain.map { directory.canonicalOf(it.narrator.id)!! }

        val askable = chain.indices.filter { people[it].isResolved }
        check(askable.isNotEmpty()) { "No narrator of Hadith ${hadith.hadith.id} is known by a name that says who he is" }
        val maskedIndex = askable.filter { it != avoidIndex }.ifEmpty { askable }.random(random)
        val answer = people[maskedIndex]

        val inChain = people.mapTo(mutableSetOf()) { it.id }
        val candidates = directory.narrators.filter { it.isResolved && it.id !in inChain && people.none { p -> mayBeSame(p, it) } }
        val (sameTier, otherTier) = candidates.partition { it.tier == answer.tier }
        val distractors = mutableListOf<CanonicalNarrator>()
        for (candidate in sameTier.shuffled(random) + otherTier.shuffled(random)) {
            if (distractors.size == optionCount - 1) break
            if (distractors.none { mayBeSame(it, candidate) }) distractors += candidate
        }
        check(distractors.isNotEmpty()) { "No distractor narrators available" }

        return MissingLinkRound(
            hadithId = hadith.hadith.id,
            chain = chain,
            maskedIndex = maskedIndex,
            options = (distractors + answer).map(DrillChoice::of).shuffled(random),
            answer = DrillChoice.of(answer),
            narratedFrom = people.getOrNull(maskedIndex + 1)?.let(DrillChoice::of),
            narratedTo = people.getOrNull(maskedIndex - 1)?.let(DrillChoice::of),
        )
    }

    private fun mayBeSame(a: CanonicalNarrator, b: CanonicalNarrator): Boolean {
        val namesA = listOf(a.name, a.fullName) + a.aliases
        val namesB = listOf(b.name, b.fullName) + b.aliases
        return namesA.any { x -> namesB.any { y -> NarratorNormalizer.mayBeSamePerson(x, y) } }
    }
}
