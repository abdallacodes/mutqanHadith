package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.ReviewRating.AGAIN
import com.mutqan.hadith.domain.model.ReviewRating.EASY
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.model.ReviewRating.HARD
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UseCasesTest {

    private val now = 1_700_000_000_000L
    private var clock = now
    private val scheduler = DualDecayScheduler()

    private val chain = (1L..4L).map { narrator(it) }
    private val hadiths = FakeHadithRepository(listOf(hadith(1, chain), hadith(2, chain.take(2)), hadith(3, chain.take(3))))
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }

    private val submit = SubmitReviewUseCase(hadiths, reviews, scheduler) { clock }
    private val getDue = GetDueHadithsUseCase(hadiths, reviews, scheduler) { clock }
    private val heatmap = GetNarratorChainHeatmapUseCase(hadiths, reviews)
    private val getUnseen = GetUnseenHadithsUseCase(hadiths)

    // -- SubmitReviewUseCase ---------------------------------------------------------------

    @Test
    fun submit_persistsTheScheduledStateAndReturnsIt() = runBlocking {
        val outcome = submit(1, GOOD, EASY)

        assertEquals(outcome.record, reviews.mastery[1])
        assertEquals(now, outcome.record.matnLastReviewedAt)
        assertEquals(now, outcome.record.sanadLastReviewedAt)
        assertEquals(1, outcome.record.repetitionCount)
        assertTrue(outcome.metrics.overallMastery > 0f)
        assertEquals(outcome.record.nextReviewDue, outcome.nextReviewDue)
        assertEquals(now + minOf(outcome.matnInterval, outcome.sanadInterval).inWholeMilliseconds, outcome.nextReviewDue)
    }

    @Test
    fun submit_laterReview_buildsOnThePersistedState() = runBlocking {
        val first = submit(1, GOOD, GOOD)
        clock = first.nextReviewDue
        val second = submit(1, GOOD, GOOD)

        assertEquals(2, second.record.repetitionCount)
        assertTrue(second.record.matnStability > first.record.matnStability)
    }

    @Test
    fun submit_recordsEveryNarratorWhenTheWholeChainWasRecalled() = runBlocking {
        submit(1, GOOD, GOOD)
        assertEquals(4, reviews.events.size)
        assertTrue(reviews.events.all { it.succeeded && it.reviewedAt == now })
    }

    @Test
    fun submit_failedNarratorsAreRecordedAndPenalizeTheSanad() = runBlocking {
        val clean = submit(2, GOOD, GOOD)
        val broken = submit(1, GOOD, GOOD, failedNarratorIds = setOf(3L))

        val byNarrator = reviews.events.filter { it.hadithId == 1L }.associate { it.narratorId to it.succeeded }
        assertEquals(mapOf(1L to true, 2L to true, 3L to false, 4L to true), byNarrator)
        assertTrue(broken.record.sanadStability < clean.record.sanadStability)
    }

    @Test
    fun submit_bareHardOrAgain_recordsNoNarratorOutcomes() = runBlocking {
        submit(1, GOOD, HARD)
        submit(2, GOOD, AGAIN)
        assertTrue(reviews.events.isEmpty())
    }

    @Test
    fun submit_matnOnlyReview_ignoresFailedNarratorsAndSanad() = runBlocking {
        val outcome = submit(1, GOOD, null, failedNarratorIds = setOf(2L))
        assertTrue(reviews.events.isEmpty())
        assertEquals(0f, outcome.record.sanadStability, 0f)
        assertNull(outcome.record.sanadLastReviewedAt)
        assertEquals(now, outcome.record.matnLastReviewedAt)
    }

    @Test
    fun submit_sanadOnlyReview_leavesTheMatnClockAlone() = runBlocking {
        val first = submit(1, EASY, EASY)
        clock += 5 * DAY_MS
        val sanadOnly = submit(1, null, GOOD)

        assertEquals(first.record.matnLastReviewedAt, sanadOnly.record.matnLastReviewedAt)
        assertEquals(first.record.matnStability, sanadOnly.record.matnStability, 0f)
        assertEquals(clock, sanadOnly.record.sanadLastReviewedAt)
        assertEquals(8, reviews.events.size) // 4 narrators per Sanad review
        assertEquals(2, sanadOnly.record.repetitionCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun submit_requiresAtLeastOneRating(): Unit = runBlocking { submit(1, null, null); Unit }

    @Test
    fun submit_rejectsUnknownHadithAndForeignNarrators() = runBlocking {
        try {
            submit(99, GOOD, GOOD)
            fail("expected NoSuchElementException")
        } catch (_: NoSuchElementException) {
        }
        try {
            submit(2, GOOD, HARD, failedNarratorIds = setOf(4L)) // narrator 4 is not in hadith 2
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertNull(reviews.mastery[2])
        assertTrue(reviews.events.isEmpty())
    }

    // -- GetDueHadithsUseCase --------------------------------------------------------------

    private fun seedMastery(id: Long, stability: Float, reviewedDaysAgo: Int, dueInDays: Int) {
        val reviewedAt = now - reviewedDaysAgo * DAY_MS
        reviews.mastery[id] = MasteryRecordEntity(
            id, stability, stability, reviewedAt, reviewedAt, now + dueInDays * DAY_MS, 3,
        )
    }

    @Test
    fun getDue_returnsOnlyDueHadiths_mostUrgentFirst() = runBlocking {
        seedMastery(1, stability = 30f, reviewedDaysAgo = 10, dueInDays = -1) // solid, barely overdue
        seedMastery(2, stability = 2f, reviewedDaysAgo = 10, dueInDays = -8) // shaky, long overdue
        seedMastery(3, stability = 5f, reviewedDaysAgo = 1, dueInDays = 3) // not due yet

        val due = getDue()

        assertEquals(listOf(2L, 1L), due.map { it.hadith.hadith.id })
        assertTrue(due[0].retrievability.weakest < due[1].retrievability.weakest)
        assertTrue(due[0].metrics.overallMastery < due[1].metrics.overallMastery)
        assertEquals(8 * DAY_MS, due[0].overdueMillis)
        assertEquals(listOf(2L), getDue(limit = 1).map { it.hadith.hadith.id })
    }

    @Test
    fun getDue_includesRecordsDueExactlyNow_andIgnoresOrphans() = runBlocking {
        seedMastery(1, stability = 5f, reviewedDaysAgo = 5, dueInDays = 0)
        seedMastery(77, stability = 5f, reviewedDaysAgo = 5, dueInDays = -1) // no such Hadith
        assertEquals(listOf(1L), getDue().map { it.hadith.hadith.id })
    }

    @Test
    fun getDue_hadithWithFailedSanadComesBackBeforeItsMatnIsDue() = runBlocking {
        submit(1, EASY, EASY)
        clock += 6 * DAY_MS
        val before = getDue().map { it.hadith.hadith.id }
        val outcome = submit(1, EASY, AGAIN)
        clock = outcome.nextReviewDue
        assertTrue(1L in before)
        assertTrue(outcome.sanadInterval < outcome.matnInterval)
        assertEquals(listOf(1L), getDue().map { it.hadith.hadith.id })
    }

    // -- GetUnseenHadithsUseCase -----------------------------------------------------------

    @Test
    fun unseen_listsHadithsWithoutMasteryInCurriculumOrder() = runBlocking {
        assertEquals(listOf(1L, 2L, 3L), getUnseen().map { it.hadith.id })

        submit(2, GOOD, GOOD)

        assertEquals(listOf(1L, 3L), getUnseen().map { it.hadith.id })
        assertEquals(listOf(1L), getUnseen(limit = 1).map { it.hadith.id })
    }

    @Test
    fun unseen_aHadithStudiedOnlyByMatnIsNoLongerNewButStaysDue() = runBlocking {
        submit(1, GOOD, null)

        assertEquals(listOf(2L, 3L), getUnseen().map { it.hadith.id })
        assertEquals(listOf(1L), getDue().map { it.hadith.hadith.id }) // the Sanad was never learned
    }

    // -- GetNarratorChainHeatmapUseCase ----------------------------------------------------

    @Test
    fun heatmap_identifiesTheNarratorThatKeepsFailing() = runBlocking {
        submit(1, GOOD, HARD, failedNarratorIds = setOf(3L))
        submit(1, GOOD, HARD, failedNarratorIds = setOf(3L))
        submit(1, GOOD, HARD, failedNarratorIds = setOf(3L, 2L))
        submit(1, GOOD, GOOD)

        val map = heatmap(1)

        assertEquals(listOf(1L, 2L, 3L, 4L), map.nodes.map { it.performance.narratorId })
        val weakest = map.weakestLink!!
        assertEquals(3L, weakest.performance.narratorId)
        assertEquals(3, weakest.performance.errorCount)
        assertEquals(1, weakest.performance.successCount)
        assertEquals(0.25f, weakest.performance.retentionRate, 1e-6f)
        assertEquals(0.75f, weakest.heat, 1e-6f)

        assertEquals(1, map.nodes.count { it.isWeakestLink })
        val second = map.nodes.first { it.performance.narratorId == 2L }
        assertEquals(1, second.performance.errorCount)
        assertEquals(0.25f, second.heat, 1e-6f)
        val flawless = map.nodes.first { it.performance.narratorId == 1L }
        assertEquals(0, flawless.performance.errorCount)
        assertEquals(1f, flawless.performance.retentionRate, 0f)
        assertEquals(0f, flawless.heat, 0f)
        assertEquals("راوي 3", weakest.performance.narratorName)
    }

    @Test
    fun heatmap_tiesGoToTheEarlierLink_andUntouchedNodesStayCold() = runBlocking {
        // Narrators 1 and 2 fail together twice and recall the chain once: equal 2-of-3 error rates.
        submit(3, GOOD, HARD, failedNarratorIds = setOf(1L, 2L))
        submit(3, GOOD, HARD, failedNarratorIds = setOf(1L, 2L))
        submit(3, GOOD, GOOD)

        val map = heatmap(3)

        assertEquals(2f / 3, map.nodes[0].heat, 1e-6f)
        assertEquals(map.nodes[0].heat, map.nodes[1].heat, 0f)
        assertEquals(1L, map.weakestLink!!.performance.narratorId)
        assertEquals(1, map.nodes.count { it.isWeakestLink })
        assertEquals(0f, map.nodes[2].heat, 0f)
    }

    @Test
    fun heatmap_withNoHistory_hasNoWeakestLink() = runBlocking {
        val map = heatmap(1)
        assertEquals(4, map.nodes.size)
        assertNull(map.weakestLink)
        assertTrue(map.nodes.all { it.heat == 0f && it.performance.retentionRate == 1f })
        assertNotNull(map.nodes.first().performance.narratorName)
    }

    @Test(expected = NoSuchElementException::class)
    fun heatmap_unknownHadith_throws(): Unit = runBlocking { heatmap(404); Unit }
}
