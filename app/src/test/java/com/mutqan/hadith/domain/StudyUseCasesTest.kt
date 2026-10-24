package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.ReviewRating.EASY
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.SubmitLinkDrillUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyUseCasesTest {

    private val now = 1_700_000_000_000L
    private var clock = now
    private val scheduler = DualDecayScheduler()

    private val chain = listOf(
        narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"), narrator(4, "عمر", tier = "صحابي"),
    )
    private val hadiths = FakeHadithRepository(
        listOf(hadith(1, chain), hadith(2, chain.take(2) + narrator(5, "أنس", tier = "صحابي")), hadith(3, chain.take(3))),
    )
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    private val submitReview = SubmitReviewUseCase(hadiths, reviews, scheduler) { clock }
    private val drill = SubmitLinkDrillUseCase(hadiths, reviews, scheduler) { clock }
    private val dashboard = ObserveStudyDashboardUseCase(hadiths, reviews, scheduler) { clock }

    // -- SubmitLinkDrillUseCase -------------------------------------------------------------

    @Test
    fun drill_correctAnswerIsLoggedButLeavesTheScheduleAlone() = runBlocking {
        submitReview(1, GOOD, GOOD)
        val before = reviews.mastery[1]!!
        val eventsBefore = reviews.events.size
        clock += DAY_MS

        val outcome = drill(1, narratorId = 3, correct = true)

        assertEquals(before, reviews.mastery[1])
        assertEquals(eventsBefore + 1, reviews.events.size)
        assertTrue(outcome.correct)
        assertEquals(2, outcome.successCount) // the full review plus this drill
        assertEquals(0, outcome.errorCount)
        assertEquals(1f, outcome.retentionRate, 0f)
    }

    @Test
    fun drill_wrongAnswerRecordsTheMissAndPenalizesOnlyTheSanad() = runBlocking {
        submitReview(1, EASY, EASY)
        val before = reviews.mastery[1]!!
        clock += 2 * DAY_MS

        val outcome = drill(1, narratorId = 3, correct = false)

        val after = reviews.mastery[1]!!
        assertTrue(after.sanadStability < before.sanadStability)
        assertEquals(before.matnStability, after.matnStability, 0f)
        assertEquals(before.matnLastReviewedAt, after.matnLastReviewedAt)
        assertEquals(clock, after.sanadLastReviewedAt)
        assertEquals(1, outcome.errorCount)
        assertEquals(1, outcome.successCount)
        assertEquals(0.5f, outcome.retentionRate, 0f)
        assertFalse(reviews.events.last().succeeded)
        assertEquals(3L, reviews.events.last().narratorId)
    }

    @Test
    fun drill_onAnUnseenHadithNeverMakesItLookSeen() = runBlocking {
        val outcome = drill(2, narratorId = 5, correct = false)

        assertNull(reviews.mastery[2])
        assertEquals(1, outcome.errorCount)
        assertEquals(listOf(1L, 2L, 3L), hadiths.getUnseen().map { it.hadith.id })
    }

    @Test(expected = IllegalArgumentException::class)
    fun drill_rejectsANarratorOutsideTheChain(): Unit = runBlocking { drill(3, narratorId = 4, correct = true); Unit }

    @Test(expected = NoSuchElementException::class)
    fun drill_rejectsAnUnknownHadith(): Unit = runBlocking { drill(99, narratorId = 1, correct = true); Unit }

    // -- ObserveStudyDashboardUseCase ---------------------------------------------------------

    @Test
    fun dashboard_countsDueAndNewAndAveragesMasteryOverTheWholeLibrary() = runBlocking {
        // Hadith 1 reviewed long ago (due), Hadith 2 reviewed just now (not due), Hadith 3 untouched (new).
        reviews.mastery[1] = MasteryRecordEntity(1, 20f, 20f, now - 30 * DAY_MS, now - 30 * DAY_MS, now - DAY_MS, 4)
        reviews.mastery[2] = MasteryRecordEntity(2, 5f, 5f, now, now, now + 3 * DAY_MS, 1)

        val d = dashboard().first()

        assertEquals(1, d.dueCount)
        assertEquals(1, d.newCount)
        assertEquals(listOf(1L, 2L, 3L), d.items.map { it.hadithId })
        assertEquals(listOf(true, false, false), d.items.map { it.isDue })
        assertEquals(listOf(false, false, true), d.items.map { it.isNew })
        val expected = (d.items[0].metrics!!.overallMastery + d.items[1].metrics!!.overallMastery + 0f) / 3
        assertEquals(expected, d.overallMastery, 1e-6f)
        assertTrue(d.overallMastery > 0f && d.overallMastery < 1f)
    }

    @Test
    fun dashboard_putsDueFirst_andNamesTheSahabi() = runBlocking {
        reviews.mastery[3] = MasteryRecordEntity(3, 2f, 2f, now - 5 * DAY_MS, now - 5 * DAY_MS, now - DAY_MS, 2)

        val d = dashboard().first()

        assertEquals(3L, d.items.first().hadithId)
        assertEquals("عمر", d.items.first { it.hadithId == 1L }.sahabiName)
        assertEquals("أنس", d.items.first { it.hadithId == 2L }.sahabiName)
        assertEquals(listOf(1, 2, 3), d.items.map { it.hadithNumber }.sorted())
    }

    @Test
    fun dashboard_suggestsTheStudiedHadithWithTheShakiestSanadForDrills() = runBlocking {
        reviews.mastery[1] = MasteryRecordEntity(1, 30f, 30f, now, now, now + DAY_MS, 3)
        reviews.mastery[3] = MasteryRecordEntity(3, 30f, 1f, now, now, now + DAY_MS, 3)

        assertEquals(3L, dashboard().first().drillSuggestionId)

        // With nothing studied yet the first Hadith of the curriculum is suggested.
        reviews.mastery.clear()
        assertEquals(1L, dashboard().first().drillSuggestionId)
    }

    @Test
    fun dashboard_emptyLibrary() = runBlocking {
        val empty = ObserveStudyDashboardUseCase(FakeHadithRepository(emptyList()), FakeReviewRepository(), scheduler) { clock }
        val d = empty().first()
        assertEquals(0, d.dueCount)
        assertEquals(0f, d.overallMastery, 0f)
        assertNull(d.drillSuggestionId)
    }
}
