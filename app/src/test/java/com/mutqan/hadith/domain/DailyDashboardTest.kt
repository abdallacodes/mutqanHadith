package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetMasteryAnalyticsUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the daily study hub is built from: the streak, the learning and mastered counts, the retention and the next hadith. */
class DailyDashboardTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"))

    // Ids are not in curriculum order, and the hadith numbers are the ids: the order must come from the position first.
    // Taught in the order 2, 3, 4, 1, 5.
    private val library = listOf(
        hadith(1, chain + narrator(10, "أبو هريرة", tier = "صحابي"), position = 4),
        hadith(5, chain + narrator(11, "أنس", tier = "صحابي"), position = 5),
        hadith(4, chain + narrator(12, "ابن عمر", tier = "صحابي"), position = 3),
        hadith(3, listOf(narrator(13, "عائشة", tier = "صحابي")), position = 2),
        hadith(2, listOf(narrator(14, "عمر بن الخطاب", tier = "صحابي")), position = 1),
    )
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val dashboard = ObserveStudyDashboardUseCase(hadiths, reviews, scheduler, { ZoneOffset.UTC }, { now })

    private fun started(id: Long, stability: Float = 2f, reviewedDaysAgo: Int = 0, dueInDays: Int = 1) {
        val at = now - reviewedDaysAgo * DAY_MS
        reviews.mastery[id] = MasteryRecordEntity(id, stability, stability, at, at, now + dueInDays * DAY_MS, 1)
    }

    // -- The next hadith to learn ----------------------------------------------------------

    @Test
    fun nextToLearn_startsAtTheFirstHadithOfTheCurriculum() = runBlocking {
        val next = dashboard().first().nextToLearn

        assertNotNull(next)
        assertEquals(2L, next!!.hadithId)
        assertEquals(2, next.hadithNumber)
        assertEquals("عمر بن الخطاب", next.sahabiName)
        assertEquals("متن 2", next.matn)
    }

    @Test
    fun nextToLearn_followsThePositionBeforeTheHadithNumberAndSkipsWhatWasStarted() = runBlocking {
        // Hadith no. 1 is taught fourth although its number is the lowest.
        val order = mutableListOf<Long>()
        while (true) {
            val next = dashboard().first().nextToLearn ?: break
            order += next.hadithId
            started(next.hadithId)
        }

        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), order)
    }

    @Test
    fun nextToLearn_isNullOnceEveryHadithWasStartedOrTheLibraryIsEmpty() = runBlocking {
        library.forEach { started(it.hadith.id) }
        assertNull(dashboard().first().nextToLearn)

        val empty = ObserveStudyDashboardUseCase(FakeHadithRepository(emptyList()), FakeReviewRepository(), scheduler) { now }
        assertNull(empty().first().nextToLearn)
    }

    @Test
    fun nextToLearn_isNotChosenByWhatIsDue() = runBlocking {
        // A hadith taught later that is due now does not take the place of the one still to learn first.
        started(1, reviewedDaysAgo = 5, dueInDays = -1)

        val d = dashboard().first()

        assertEquals(1, d.dueCount)
        assertEquals(2L, d.nextToLearn?.hadithId)
    }

    // -- Learning and mastered -------------------------------------------------------------

    @Test
    fun counts_splitTheStudiedHadithsAtTheMasteredThreshold() = runBlocking {
        started(1, stability = 400f)
        started(2, stability = 400f)
        started(3, stability = 2f)
        val mastered = scheduler.metrics(reviews.mastery.getValue(1)).overallMastery
        val learning = scheduler.metrics(reviews.mastery.getValue(3)).overallMastery
        assertTrue(mastered >= GetMasteryAnalyticsUseCase.MASTERED_AT)
        assertTrue(learning < GetMasteryAnalyticsUseCase.MASTERED_AT)

        val d = dashboard().first()

        assertEquals(2, d.masteredCount)
        assertEquals(1, d.learningCount)
        assertEquals(3, d.studiedCount)
        assertEquals(2, d.newCount)
    }

    @Test
    fun counts_areZeroAndRetentionIsEmptyBeforeAnythingIsStudied() = runBlocking {
        val d = dashboard().first()

        assertEquals(0, d.studiedCount)
        assertEquals(0, d.dueCount)
        assertNull(d.retention)
    }

    // -- Retention -------------------------------------------------------------------------

    @Test
    fun retention_isTheMeanRecallNowOverTheStudiedHadiths() = runBlocking {
        started(1, reviewedDaysAgo = 0)
        started(2, reviewedDaysAgo = 10)
        val recalls = listOf(1L, 2L).map { id -> scheduler.retrievability(reviews.mastery.getValue(id), now).let { (it.matn + it.sanad) / 2 } }
        assertEquals(1f, recalls[0], 0.0001f)
        assertTrue(recalls[1] < 1f)

        assertEquals(recalls.average().toFloat(), dashboard().first().retention!!, 0.0001f)
    }

    @Test
    fun retention_countsOnlyTheComponentsThatWereReviewed() = runBlocking {
        // The sanad of this hadith was never reviewed: its zero recall is not a forgotten sanad.
        reviews.mastery[1] = MasteryRecordEntity(1, 2f, 0f, now - 10 * DAY_MS, null, now + DAY_MS, 1)
        val matnOnly = scheduler.retrievability(reviews.mastery.getValue(1), now).matn

        assertEquals(matnOnly, dashboard().first().retention!!, 0.0001f)
    }

    // -- Streak ----------------------------------------------------------------------------

    private fun reviewed(vararg daysAgo: Int) {
        daysAgo.forEach { reviews.events += NarratorReviewEventEntity(hadithId = 1, narratorId = 1, reviewedAt = now - it * DAY_MS, succeeded = true) }
    }

    @Test
    fun streak_isTheRunOfDaysWithAReviewEndingToday() = runBlocking {
        reviewed(0, 1, 2, 4)

        val streak = dashboard().first().streak

        assertEquals(3, streak.current)
        assertTrue(streak.studiedToday)
    }

    @Test
    fun streak_survivesADayNotYetStudiedButNotAMissedOne() = runBlocking {
        reviewed(1, 2)
        val yesterday = dashboard().first().streak
        assertEquals(2, yesterday.current)
        assertFalse(yesterday.studiedToday)

        reviews.events.clear()
        reviewed(2, 3)
        assertEquals(0, dashboard().first().streak.current)
    }

    @Test
    fun streak_isEmptyForANewcomer() = runBlocking {
        assertEquals(0, dashboard().first().streak.current)
    }
}
