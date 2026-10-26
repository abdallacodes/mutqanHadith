package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.CurriculumOrder
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Curriculum order" is the position the catalog gives each hadith, then the number in the book, then the id, so a book
 * comes before the next however the rows were numbered when they were added.
 */
class CurriculumOrderTest {

    private val now = 1_700_000_000_000L
    private val companion = narrator(1, "عمر بن الخطاب", tier = "صحابي")
    private val chain = listOf(narrator(2, "الحميدي", tier = "شيخ المصنف"), companion)

    private fun numbered(id: Long, number: Int, position: Int, groupId: Long? = null) = hadith(id, chain, position, groupId).let {
        it.copy(hadith = it.hadith.copy(hadithNumber = number))
    }

    // Ids run the other way round to the curriculum: the rows taught last were added first.
    private val library = listOf(
        numbered(id = 1, number = 50, position = 5, groupId = 7),
        numbered(id = 2, number = 8, position = 4, groupId = 7),
        numbered(id = 3, number = 7, position = 2),
        numbered(id = 4, number = 1, position = 1),
        numbered(id = 5, number = 8, position = 4, groupId = 7), // the same position and number as id 2: the id breaks the tie
        numbered(id = 6, number = 2, position = 3),
    )
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    private val expected = listOf(4L, 3L, 6L, 2L, 5L, 1L)

    @Test
    fun theComparatorOrdersByPositionThenNumberThenId() {
        assertEquals(expected, library.map { it.hadith }.sortedWith(CurriculumOrder).map { it.id })
    }

    @Test
    fun aHadithTheCatalogHasNotNumberedFallsBackToItsNumberInTheBookAndThenItsId() {
        val unplaced = listOf(numbered(1, 3, 0), numbered(2, 1, 0), numbered(3, 1, 0)).map { it.hadith }

        assertEquals(listOf(2L, 3L, 1L), unplaced.sortedWith(CurriculumOrder).map { it.id })
    }

    @Test
    fun newHadithsComeInTheOrderTheyAreTaught() = runBlocking {
        assertEquals(expected, GetUnseenHadithsUseCase(hadiths)().map { it.hadith.id })
        assertEquals(listOf(4L, 3L), GetUnseenHadithsUseCase(hadiths)(limit = 2).map { it.hadith.id })
    }

    @Test
    fun aHadithThatIsStartedLeavesTheQueueAndTheRestKeepTheirPlaces() = runBlocking {
        reviews.mastery[4L] = MasteryRecordEntity(4, 0f, 0f, null, null, now, 0)

        assertEquals(listOf(3L, 6L, 2L, 5L, 1L), GetUnseenHadithsUseCase(hadiths)().map { it.hadith.id })
    }

    @Test
    fun theDashboardListsTheLibraryInCurriculumOrderWithTheDueFirst() = runBlocking {
        val dashboard = ObserveStudyDashboardUseCase(hadiths, reviews, DualDecayScheduler()) { now }

        assertEquals(expected, dashboard().first().items.map { it.hadithId })

        reviews.mastery[2L] = MasteryRecordEntity(2, 2f, 2f, now - 10 * DAY_MS, now - 10 * DAY_MS, now - DAY_MS, 1)
        assertEquals(listOf(2L, 4L, 3L, 6L, 5L, 1L), dashboard().first().items.map { it.hadithId })
    }

    @Test
    fun theHadithsOfOneNarratorAreInCurriculumOrder() = runBlocking {
        assertEquals(expected, hadiths.getThroughNarrator(companion.id).map { it.hadith.id })
    }

    @Test
    fun theHadithsOfOneGroupAreInBookOrder() = runBlocking {
        assertEquals(listOf(2L, 5L, 1L), hadiths.getHadithsInGroup(7).first().map { it.id })
    }
}
