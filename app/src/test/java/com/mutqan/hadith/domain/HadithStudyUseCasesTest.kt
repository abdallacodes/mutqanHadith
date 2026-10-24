package com.mutqan.hadith.domain

import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Taking a hadith from the library into memorization, and reading how it is going. */
class HadithStudyUseCasesTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val companion = narrator(1, "عمر بن الخطاب", tier = "صحابي")
    private val teacher = narrator(2, "الحميدي", tier = "شيخ المصنف")

    private val hadiths = FakeHadithRepository(
        listOf(hadith(1, listOf(companion), level = 1), hadith(2, listOf(teacher, companion), level = 3)),
    )
    private val reviews = LiveReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val start = StartMemorizingUseCase(hadiths, reviews) { now }
    private val study = GetHadithStudyUseCase(hadiths, reviews, scheduler) { now }

    // -- StartMemorizingUseCase ----------------------------------------------------------------

    @Test
    fun starting_givesTheHadithAnEmptyRecordThatIsDueNow() = runBlocking {
        val record = start(1)

        assertEquals(record, reviews.mastery[1L])
        assertEquals(1L, record.hadithId)
        assertEquals(0f, record.matnStability, 0f)
        assertEquals(0f, record.sanadStability, 0f)
        assertNull(record.matnLastReviewedAt)
        assertNull(record.sanadLastReviewedAt)
        assertEquals(now, record.nextReviewDue)
        assertEquals(0, record.repetitionCount)
        assertTrue("no narrator was tested", reviews.events.isEmpty())
    }

    @Test
    fun starting_takesTheHadithOutOfTheNewOnes() = runBlocking {
        assertEquals(listOf(1L, 2L), hadiths.getUnseen().map { it.hadith.id })

        start(1)

        assertEquals(listOf(2L), hadiths.getUnseen().map { it.hadith.id })
    }

    @Test
    fun starting_aHadithAlreadyInStudyKeepsTheUsersProgress() = runBlocking {
        SubmitReviewUseCase(hadiths, reviews, scheduler) { now - 3 * DAY_MS }(2, ReviewRating.GOOD, ReviewRating.EASY)
        val before = reviews.mastery.getValue(2L)

        val record = start(2)

        assertSame(before, record)
        assertEquals(before, reviews.mastery[2L])
        assertTrue(record.matnStability > 0f && record.repetitionCount == 1)
    }

    @Test
    fun starting_twiceChangesNothing() = runBlocking {
        val first = start(1)
        val second = StartMemorizingUseCase(hadiths, reviews) { now + DAY_MS }(1)

        assertEquals(first, second)
        assertEquals(now, reviews.mastery.getValue(1L).nextReviewDue)
    }

    @Test
    fun starting_aHadithThatDoesNotExistFailsAndSavesNothing() = runBlocking {
        try {
            start(99)
            fail("expected NoSuchElementException")
        } catch (expected: NoSuchElementException) {
            // The library never holds a record for a hadith it does not have.
        }
        assertTrue(reviews.mastery.isEmpty())
    }

    @Test
    fun theFirstReviewOfAStartedHadithIsTheFirstReviewOfAnyNewHadith() = runBlocking {
        val fresh = LiveReviewRepository()
        SubmitReviewUseCase(hadiths, fresh, scheduler) { now }(1, ReviewRating.GOOD, ReviewRating.HARD)

        start(1)
        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(1, ReviewRating.GOOD, ReviewRating.HARD)

        assertEquals(fresh.mastery.getValue(1L), reviews.mastery.getValue(1L))
        assertEquals(1, reviews.mastery.getValue(1L).repetitionCount)
    }

    // -- GetHadithStudyUseCase ------------------------------------------------------------------

    @Test
    fun aHadithNeverStartedIsNew() = runBlocking {
        val s = study(1).first()!!

        assertEquals(1L, s.hadith.hadith.id)
        assertFalse(s.isInStudy)
        assertEquals(HadithProgress.NEW, s.progress)
        assertNull(s.metrics)
        assertNull(s.retention)
        assertNull(s.nextReviewDue)
        assertFalse(s.isDue)
    }

    @Test
    fun aStartedHadithIsInStudyAndDueWithNothingRetainedYet() = runBlocking {
        start(1)

        val s = study(1).first()!!

        assertTrue(s.isInStudy)
        assertEquals(HadithProgress.LEARNING, s.progress)
        assertTrue(s.isDue)
        assertEquals(now, s.nextReviewDue)
        assertEquals(0f, s.retention!!.weakest, 0f)
        assertEquals(0f, s.metrics!!.overallMastery, 0f)
    }

    @Test
    fun aReviewedHadithShowsWhatIsRetainedNow() = runBlocking {
        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(2, ReviewRating.GOOD, ReviewRating.GOOD)

        val s = study(2).first()!!

        assertEquals(1f, s.retention!!.matn, 1e-6f)
        assertEquals(1f, s.retention!!.sanad, 1e-6f)
        assertFalse("reviewed this very moment", s.isDue)
        assertNotNull(s.nextReviewDue)
        assertTrue(s.nextReviewDue!! > now)
    }

    @Test
    fun retentionFallsAsTimePasses() = runBlocking {
        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(2, ReviewRating.GOOD, ReviewRating.GOOD)
        val later = GetHadithStudyUseCase(hadiths, reviews, scheduler) { now + 30 * DAY_MS }

        val s = later(2).first()!!

        assertTrue(s.retention!!.sanad < s.retention!!.matn)
        assertTrue(s.retention!!.weakest < 0.9f)
        assertTrue(s.isDue)
    }

    @Test
    fun aHadithThatDoesNotExistIsNull() = runBlocking {
        assertNull(study(99).first())
    }

    @Test
    fun theStudyFollowsTheUsersProgress() = runBlocking {
        val flow = study(1)
        val before = flow.first()!!.isInStudy
        start(1)
        val after = flow.first()!!.isInStudy

        assertEquals(listOf(false, true), listOf(before, after))
    }
}
