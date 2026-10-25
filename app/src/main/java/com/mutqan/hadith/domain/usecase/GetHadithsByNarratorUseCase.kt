package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.ReviewRepository

/** A Hadith whose chain passes through the narrator being inspected, with how he fared in it. */
data class NarratorHadith(
    val hadithId: Long,
    val hadithNumber: Int,
    val sourceBook: String,
    val chapter: String,
    val matn: String,
    /** The narrator's place in the chain, 1 = the collector's teacher. */
    val position: Int,
    val chainLength: Int,
    val errorCount: Int,
    val successCount: Int,
    /** Whether the Hadith has been reviewed at least once. */
    val isStudied: Boolean,
) {
    val attempts: Int get() = errorCount + successCount
}

class GetHadithsByNarratorUseCase(
    private val hadiths: HadithRepository,
    private val reviews: ReviewRepository,
) {
    /**
     * Every Hadith through the man [narratorId] stands for (a canonical id, or any of his rows), under any of his
     * names, in curriculum order; empty for an unknown narrator.
     */
    suspend operator fun invoke(narratorId: Long): List<NarratorHadith> {
        val library = hadiths.getAll()
        val rows = NarratorDirectory.ofLibrary(library).membersOf(narratorId)
        val through = library.filter { h -> h.edges.any { it.narrator.id in rows } }
        if (through.isEmpty()) return emptyList()
        val outcomes = rows.flatMap { reviews.getOutcomeCountsByHadith(it) }.groupBy { it.hadithId }
            .mapValues { (_, counts) -> counts.sumOf { it.errorCount } to counts.sumOf { it.successCount } }
        val studied = reviews.getAllMastery().mapTo(mutableSetOf()) { it.hadithId }

        return through.sortedBy { it.hadith.id }.map { h ->
            val chain = h.sanad
            val outcome = outcomes[h.hadith.id]
            NarratorHadith(
                hadithId = h.hadith.id,
                hadithNumber = h.hadith.hadithNumber,
                sourceBook = h.hadith.sourceBook,
                chapter = h.hadith.chapter,
                matn = h.hadith.matnWithTashkeel,
                position = chain.indexOfFirst { it.narrator.id in rows } + 1,
                chainLength = chain.size,
                errorCount = outcome?.first ?: 0,
                successCount = outcome?.second ?: 0,
                isStudied = h.hadith.id in studied,
            )
        }
    }
}
