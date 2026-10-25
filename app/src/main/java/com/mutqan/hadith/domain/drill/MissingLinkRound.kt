package com.mutqan.hadith.domain.drill

import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import kotlin.random.Random

/** One question of the missing-link drill: [chain] with the node at [maskedIndex] hidden. */
data class MissingLinkRound(
    val hadithId: Long,
    val chain: List<SanadLink>,
    val maskedIndex: Int,
    /** The answer plus distractors, already shuffled. */
    val options: List<NarratorEntity>,
) {
    val answer: NarratorEntity get() = chain[maskedIndex].narrator
}

object MissingLinkRoundFactory {
    const val OPTION_COUNT = 4

    /**
     * Masks a random node (never [avoidIndex], so consecutive questions differ) and picks
     * distractors from narrators outside the chain, preferring the answer's own tier because
     * those are the plausible mix-ups; other tiers only fill the gap.
     *
     * The pool is of people, not of catalog rows: a row that names a man of the chain under another spelling («ابن
     * عباس» beside «عبد الله بن عباس») is no distractor but the answer itself, and the options never offer one man
     * twice.
     *
     * @throws IllegalStateException if there are no narrators to use as distractors.
     */
    fun create(
        hadith: HadithWithFullSanad,
        narrators: List<NarratorEntity>,
        random: Random = Random.Default,
        avoidIndex: Int? = null,
        optionCount: Int = OPTION_COUNT,
    ): MissingLinkRound {
        val chain = hadith.sanad
        check(chain.isNotEmpty()) { "Hadith ${hadith.hadith.id} has no chain" }
        val maskable = chain.indices.filter { it != avoidIndex }.ifEmpty { chain.indices.toList() }
        val maskedIndex = maskable.random(random)
        val answer = chain[maskedIndex].narrator

        val directory = NarratorDirectory.of(narrators + chain.map { it.narrator })
        val inChain = chain.map { directory.canonicalId(it.narrator.id) }.toSet()
        val candidates = narrators
            .filter { directory.canonicalId(it.id) !in inChain }
            .distinctBy { directory.canonicalId(it.id) }
        val (sameTier, otherTier) = candidates.partition { it.tier == answer.tier }
        val distractors = (sameTier.shuffled(random) + otherTier.shuffled(random)).take(optionCount - 1)
        check(distractors.isNotEmpty()) { "No distractor narrators available" }

        return MissingLinkRound(hadith.hadith.id, chain, maskedIndex, (distractors + answer).shuffled(random))
    }
}
