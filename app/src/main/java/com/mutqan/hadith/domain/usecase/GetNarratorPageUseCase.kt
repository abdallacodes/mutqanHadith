package com.mutqan.hadith.domain.usecase

import com.mutqan.hadith.domain.graph.NarratorNode
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.narrator.bioParagraphs
import com.mutqan.hadith.domain.repository.NarratorRepository
import com.mutqan.hadith.domain.repository.ReviewRepository

/**
 * Everything the narrator's page shows: who he is ([node], with the user's tally over all his rows), what an admin
 * wrote about him ([bio], in paragraphs) and the hadiths whose chain runs through him.
 */
data class NarratorPage(
    val node: NarratorNode,
    val bio: List<String>,
    val hadiths: List<NarratorHadith>,
)

class GetNarratorPageUseCase(
    private val narrators: NarratorRepository,
    private val reviews: ReviewRepository,
    private val hadithsByNarrator: GetHadithsByNarratorUseCase,
) {
    /**
     * The page of the man the narrator row [narratorId] is (any of his rows lands on the same page); null for an id that
     * is not in the catalog. Built from the whole catalog, so a narrator no chain uses yet still has a page.
     */
    suspend operator fun invoke(narratorId: Long): NarratorPage? {
        val person = NarratorDirectory.of(narrators.getAll()).canonicalOf(narratorId) ?: return null
        val hadiths = hadithsByNarrator(person.id)
        val tally = reviews.getNarratorOutcomeTotals().filter { it.narratorId in person.memberIds }
        return NarratorPage(
            node = NarratorNode(
                id = person.id,
                name = person.name,
                fullName = person.fullName,
                tier = person.tier,
                isPivot = person.isPivot,
                errorCount = tally.sumOf { it.errorCount },
                successCount = tally.sumOf { it.successCount },
                hadithCount = hadiths.size,
                aliases = person.aliases,
            ),
            bio = bioParagraphs(person.bio),
            hadiths = hadiths,
        )
    }
}
