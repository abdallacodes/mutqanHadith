package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.graph.GraphEdge
import com.mutqan.hadith.domain.graph.NarratorGraph
import com.mutqan.hadith.domain.graph.NarratorNode
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository

/**
 * Builds the narrator network from every chain in the library: each narrator becomes a node and
 * each teacher-to-student step an edge, deduplicated so a step shared by several Hadiths is one
 * edge whose weight is the number of Hadiths that contain it.
 */
class GetNarratorGraphUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
) {
    /** With [studiedOnly], only Hadiths that already have a mastery record take part. */
    suspend operator fun invoke(studiedOnly: Boolean = false): NarratorGraph {
        val studied = if (studiedOnly) reviews.getAllMastery().mapTo(mutableSetOf()) { it.hadithId } else null
        val library = hadiths.getAll().filter { studied == null || it.hadith.id in studied }
        val outcomes = reviews.getNarratorOutcomeTotals().associateBy { it.narratorId }

        val narrators = linkedMapOf<Long, com.mutqan.hadith.data.entity.NarratorEntity>()
        val hadithsOfNarrator = mutableMapOf<Long, MutableSet<Long>>()
        val hadithsOfEdge = mutableMapOf<Pair<Long, Long>, MutableSet<Long>>()

        for (hadith in library) {
            val chain = hadith.sanad
            for (link in chain) {
                narrators.putIfAbsent(link.narrator.id, link.narrator)
                hadithsOfNarrator.getOrPut(link.narrator.id) { mutableSetOf() } += hadith.hadith.id
            }
            // The chain runs from the collector's teacher to the Sahabi: each narrator received from the next.
            for (i in 0 until chain.lastIndex) {
                val student = chain[i].narrator.id
                val teacher = chain[i + 1].narrator.id
                if (teacher == student) continue
                hadithsOfEdge.getOrPut(teacher to student) { mutableSetOf() } += hadith.hadith.id
            }
        }

        val nodes = narrators.values.sortedBy { it.id }.map { n ->
            val outcome = outcomes[n.id]
            NarratorNode(
                id = n.id,
                name = n.primaryName,
                fullName = n.fullName,
                tier = n.tier,
                isPivot = n.isPivot,
                errorCount = outcome?.errorCount ?: 0,
                successCount = outcome?.successCount ?: 0,
                hadithCount = hadithsOfNarrator[n.id]?.size ?: 0,
            )
        }
        val edges = hadithsOfEdge
            .map { (pair, ids) -> GraphEdge(pair.first, pair.second, ids.size) }
            .sortedWith(compareBy({ it.fromNarratorId }, { it.toNarratorId }))
        return NarratorGraph(nodes, edges)
    }
}
