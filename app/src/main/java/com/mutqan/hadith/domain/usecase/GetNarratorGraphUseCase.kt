package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.graph.GraphEdge
import com.mutqan.hadith.domain.graph.NarratorGraph
import com.mutqan.hadith.domain.graph.NarratorNode
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository

/**
 * Builds the narrator network from every chain in the library: each narrator becomes a node and
 * each teacher-to-student step an edge, deduplicated so a step shared by several Hadiths is one
 * edge whose weight is the number of Hadiths that contain it.
 *
 * A node is a person, not a row of the catalog: the rows that name one man differently («ابن عباس», «عبد الله بن
 * عباس») are merged into one node under his canonical id (see [NarratorDirectory]), so the chains that share him meet
 * at him instead of running side by side. His recall tally adds up the tallies of all his rows.
 */
class GetNarratorGraphUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
) {
    /** With [studiedOnly], only Hadiths that already have a mastery record take part. */
    suspend operator fun invoke(studiedOnly: Boolean = false): NarratorGraph {
        val studied = if (studiedOnly) reviews.getAllMastery().mapTo(mutableSetOf()) { it.hadithId } else null
        val everything = hadiths.getAll()
        // Built from the whole library, so a person keeps his id whichever hadiths take part.
        val directory = NarratorDirectory.ofLibrary(everything)
        val library = everything.filter { studied == null || it.hadith.id in studied }
        val errors = mutableMapOf<Long, Int>()
        val successes = mutableMapOf<Long, Int>()
        for (total in reviews.getNarratorOutcomeTotals()) {
            val person = directory.canonicalId(total.narratorId)
            errors.merge(person, total.errorCount, Int::plus)
            successes.merge(person, total.successCount, Int::plus)
        }

        val hadithsOfNarrator = mutableMapOf<Long, MutableSet<Long>>()
        val hadithsOfEdge = mutableMapOf<Pair<Long, Long>, MutableSet<Long>>()

        for (hadith in library) {
            val chain = hadith.sanad.map { directory.canonicalId(it.narrator.id) }
            for (person in chain) {
                hadithsOfNarrator.getOrPut(person) { mutableSetOf() } += hadith.hadith.id
            }
            // The chain runs from the collector's teacher to the Sahabi: each narrator received from the next.
            for (i in 0 until chain.lastIndex) {
                val student = chain[i]
                val teacher = chain[i + 1]
                if (teacher == student) continue
                hadithsOfEdge.getOrPut(teacher to student) { mutableSetOf() } += hadith.hadith.id
            }
        }

        val nodes = hadithsOfNarrator.keys.sorted().mapNotNull { id -> directory.canonicalOf(id) }.map { person ->
            NarratorNode(
                id = person.id,
                name = person.name,
                fullName = person.fullName,
                tier = person.tier,
                isPivot = person.isPivot,
                errorCount = errors[person.id] ?: 0,
                successCount = successes[person.id] ?: 0,
                hadithCount = hadithsOfNarrator[person.id]?.size ?: 0,
                aliases = person.aliases,
            )
        }
        val edges = hadithsOfEdge
            .map { (pair, ids) -> GraphEdge(pair.first, pair.second, ids.size) }
            .sortedWith(compareBy({ it.fromNarratorId }, { it.toNarratorId }))
        return NarratorGraph(nodes, edges)
    }
}
