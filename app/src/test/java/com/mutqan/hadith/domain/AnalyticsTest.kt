package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.analytics.StudyStreakCalculator
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.ComponentRetention
import com.mutqan.hadith.domain.usecase.DualRetention
import com.mutqan.hadith.domain.usecase.GetMasteryAnalyticsUseCase
import com.mutqan.hadith.domain.usecase.MemoryComponent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class AnalyticsTest {

    private val utc = ZoneOffset.UTC
    private val today: LocalDate = LocalDate.of(2026, 10, 24)
    private val now = at(today, 15, 30)

    private fun at(day: LocalDate, hour: Int = 12, minute: Int = 0, zone: ZoneId = utc): Long =
        day.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun streak(vararg daysAgo: Int, zone: ZoneId = utc, clock: Long = now) =
        StudyStreakCalculator.compute(daysAgo.map { at(today.minusDays(it.toLong())) }, clock, zone)

    // -- Streak -----------------------------------------------------------------------------

    @Test
    fun noReviewsMeansNoStreak() {
        assertEquals(StudyStreak.None, StudyStreakCalculator.compute(emptyList(), now, utc))
        assertEquals(List(7) { false }, StudyStreak.None.lastSevenDays)
    }

    @Test
    fun studyingTodayStartsAStreakOfOne() {
        val s = streak(0)
        assertEquals(1, s.current)
        assertEquals(1, s.longest)
        assertTrue(s.studiedToday)
        assertEquals(1, s.studyDays)
    }

    @Test
    fun consecutiveDaysBuildTheStreakCountingBackFromToday() {
        val s = streak(0, 1, 2, 3)
        assertEquals(4, s.current)
        assertEquals(4, s.longest)
        assertEquals(4, s.studyDays)
    }

    @Test
    fun aStreakSurvivesUntilTodayEndsWithoutStudy() {
        val s = streak(1, 2, 3)

        assertEquals("yesterday still counts while today is young", 3, s.current)
        assertFalse(s.studiedToday)
    }

    @Test
    fun aMissedDayBreaksItButKeepsTheBestRun() {
        val s = streak(2, 3, 4, 5)

        assertEquals(0, s.current)
        assertEquals(4, s.longest)
        assertFalse(s.studiedToday)
    }

    @Test
    fun aGapSplitsRunsAndOnlyTheLatestOneIsCurrent() {
        val s = streak(0, 1, 4, 5, 6, 7)

        assertEquals(2, s.current)
        assertEquals(4, s.longest)
        assertEquals(6, s.studyDays)
    }

    @Test
    fun manyReviewsInOneDayCountOnce() {
        val s = StudyStreakCalculator.compute(
            listOf(at(today, 6), at(today, 12), at(today, 23, 59), at(today.minusDays(1), 0, 1)),
            now,
            utc,
        )
        assertEquals(2, s.current)
        assertEquals(2, s.studyDays)
    }

    @Test
    fun daysFollowTheLocalCalendarNotUtc() {
        val riyadh = ZoneId.of("Asia/Riyadh") // UTC+3
        // 22:30 UTC on the 23rd is 01:30 on the 24th in Riyadh, so it counts as today there.
        val review = Instant.parse("2026-10-23T22:30:00Z").toEpochMilli()
        val inRiyadh = StudyStreakCalculator.compute(listOf(review), now, riyadh)
        val inUtc = StudyStreakCalculator.compute(listOf(review), now, utc)

        assertTrue(inRiyadh.studiedToday)
        assertFalse(inUtc.studiedToday)
        assertEquals(1, inUtc.current) // yesterday in UTC
    }

    @Test
    fun reviewsFromTheFutureAreIgnored() {
        val s = StudyStreakCalculator.compute(listOf(at(today.plusDays(1)), at(today.plusDays(2))), now, utc)
        assertEquals(StudyStreak.None, s)

        val withReal = StudyStreakCalculator.compute(listOf(at(today.minusDays(1)), at(today.plusDays(1))), now, utc)
        assertEquals(1, withReal.current)
        assertFalse(withReal.studiedToday)
    }

    @Test
    fun theLastSevenDaysAreFlaggedOldestFirstEndingToday() {
        val s = streak(0, 2, 3, 6, 9)
        //           6d    5d     4d     3d    2d     1d     today
        assertEquals(listOf(true, false, false, true, true, false, true), s.lastSevenDays)
    }

    // -- Retention comparison ---------------------------------------------------------------

    private fun retention(matn: Float?, sanad: Float?) =
        DualRetention(matn?.let { ComponentRetention(it, 1f) }, sanad?.let { ComponentRetention(it, 1f) })

    @Test
    fun theWeakerComponentIsNamedOnlyWhenTheGapIsClear() {
        assertEquals(MemoryComponent.SANAD, retention(0.9f, 0.7f).weaker)
        assertEquals(MemoryComponent.MATN, retention(0.6f, 0.85f).weaker)
        assertNull(retention(0.9f, 0.85f).weaker)
        assertNull(retention(0.9f, null).weaker)
        assertNull(retention(null, null).weaker)
    }

    // -- Analytics use case -----------------------------------------------------------------

    private val scheduler = DualDecayScheduler()
    private val shaykh = narrator(1, "الحميدي", "شيخ المصنف")
    private val sufyan = narrator(2, "سفيان", "تابع التابعين")
    private val zuhri = narrator(3, "الزهري", "تابعي").copy(isPivot = true)
    private val abuHurayra = narrator(4, "أبو هريرة", "صحابي")
    private val narrators = listOf(shaykh, sufyan, zuhri, abuHurayra)
    private val library = (1L..6L).map { hadith(it, narrators) }

    private val hadithRepo = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository()
    private val useCase = GetMasteryAnalyticsUseCase(hadithRepo, FakeNarratorRepository(narrators), reviews, scheduler, { now }, { utc })

    private fun record(id: Long, matn: Float, sanad: Float, matnAt: Long? = now, sanadAt: Long? = now) {
        reviews.mastery[id] = MasteryRecordEntity(id, matn, sanad, matnAt, sanadAt, now + 1, 1)
    }

    private fun event(narratorId: Long, succeeded: Boolean, hadithId: Long = 1, time: Long = now) =
        NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = time, succeeded = succeeded)

    private fun outcomes(narratorId: Long, errors: Int, successes: Int) {
        repeat(errors) { reviews.events += event(narratorId, false) }
        repeat(successes) { reviews.events += event(narratorId, true) }
    }

    @Test
    fun aFreshInstallHasEmptyFigures() = runBlocking {
        val a = useCase()

        assertEquals(StudyStreak.None, a.streak)
        assertNull(a.retention.matn)
        assertNull(a.retention.sanad)
        assertEquals(6, a.status.total)
        assertEquals(6, a.status.unseen)
        assertEquals(0, a.status.learning)
        assertEquals(0, a.status.mastered)
        assertTrue(a.weakestNarrators.isEmpty())
        assertFalse(a.hasActivity)
    }

    @Test
    fun hadithsAreSortedIntoUnseenLearningAndMastered() = runBlocking {
        record(1, matn = 60f, sanad = 100f) // recall holds for months: mastered
        record(2, matn = 2f, sanad = 2f) // just started
        record(3, matn = 40f, sanad = 0f, sanadAt = null) // Matn learnt, Sanad never: still learning

        val s = useCase().status

        assertEquals(6, s.total)
        assertEquals(3, s.unseen)
        assertEquals(2, s.learning)
        assertEquals(1, s.mastered)
        assertEquals(s.total, s.unseen + s.learning + s.mastered)
    }

    @Test
    fun aHadithNeedsBothMatnAndSanadToCountAsMastered() = runBlocking {
        record(1, matn = 300f, sanad = 0.5f)
        record(2, matn = 0.5f, sanad = 300f)

        assertEquals(0, useCase().status.mastered)
    }

    @Test
    fun retentionAveragesTheRecallOfStudiedComponentsOnly() = runBlocking {
        // Reviewed just now: recall 100%. Reviewed exactly one stability ago: recall 90% by definition.
        record(1, matn = 10f, sanad = 10f)
        record(2, matn = 10f, sanad = 10f, matnAt = now - 10 * DAY_MS, sanadAt = null)
        // Sanad of #2 was never reviewed, so it must not pull the Sanad average down.
        record(3, matn = 4f, sanad = 4f, matnAt = null, sanadAt = now)

        val r = useCase().retention

        assertEquals((1.0 + 0.9) / 2, r.matn!!.retention.toDouble(), 1e-3)
        assertEquals(1.0, r.sanad!!.retention.toDouble(), 1e-3)
        assertEquals(10f, r.matn!!.averageStabilityDays, 1e-4f)
        assertEquals((10f + 4f) / 2, r.sanad!!.averageStabilityDays, 1e-4f)
    }

    @Test
    fun sanadForgetsFasterSoItsRetentionFallsBelowMatnForTheSameReviews() = runBlocking {
        val weekAgo = now - 7 * DAY_MS
        record(1, matn = 8f, sanad = 8f, matnAt = weekAgo, sanadAt = weekAgo)

        val r = useCase().retention

        assertTrue(r.sanad!!.retention < r.matn!!.retention)
    }

    @Test
    fun aSanadHeldFarLessFirmlyThanTheMatnIsFlaggedAsTheWeakerSide() = runBlocking {
        val weekAgo = now - 7 * DAY_MS
        record(1, matn = 20f, sanad = 3f, matnAt = weekAgo, sanadAt = weekAgo)

        val r = useCase().retention

        assertEquals(MemoryComponent.SANAD, r.weaker)
    }

    @Test
    fun theStreakComesFromTheDaysReviewsWereRecorded() = runBlocking {
        reviews.events += listOf(
            event(1, true, time = now),
            event(2, true, time = now),
            event(1, false, time = now - DAY_MS),
            event(3, true, time = now - 5 * DAY_MS),
        )

        val s = useCase().streak

        assertEquals(2, s.current)
        assertTrue(s.studiedToday)
        assertEquals(3, s.studyDays)
        assertTrue(useCase().hasActivity)
    }

    @Test
    fun theWeakestNarratorsAreRankedByErrorRate() = runBlocking {
        outcomes(1, errors = 1, successes = 9) // 10%
        outcomes(2, errors = 5, successes = 5) // 50%
        outcomes(3, errors = 3, successes = 1) // 75%
        outcomes(4, errors = 0, successes = 8) // never missed: not listed

        val weakest = useCase().weakestNarrators

        assertEquals(listOf("الزهري", "سفيان", "الحميدي"), weakest.map { it.name })
        assertEquals(listOf(3L, 2L, 1L), weakest.map { it.narratorId })
        val top = weakest.first()
        assertEquals(3, top.errorCount)
        assertEquals(4, top.attempts)
        assertEquals(0.75f, top.errorRate, 1e-6f)
        assertTrue(top.isPivot)
        assertEquals("تابعي", top.tier)
    }

    @Test
    fun aSingleAttemptIsNotYetAPattern() = runBlocking {
        outcomes(1, errors = 1, successes = 0) // 100% but of one try
        outcomes(2, errors = 1, successes = 1) // 50% of two

        assertEquals(listOf(2L), useCase().weakestNarrators.map { it.narratorId })
    }

    @Test
    fun tiesBreakOnMoreErrorsThenMoreAttempts() = runBlocking {
        outcomes(1, errors = 1, successes = 1) // 50%, 1 error
        outcomes(2, errors = 2, successes = 2) // 50%, 2 errors
        outcomes(3, errors = 2, successes = 2) // 50%, 2 errors, same attempts: lower id first
        outcomes(4, errors = 3, successes = 3) // 50%, 3 errors

        assertEquals(listOf(4L, 2L, 3L, 1L), useCase().weakestNarrators.map { it.narratorId })
    }

    @Test
    fun theLeaderboardIsCappedAtFive() = runBlocking {
        val many = (1L..8L).map { narrator(it, "راوي $it") }
        val big = GetMasteryAnalyticsUseCase(
            FakeHadithRepository(listOf(hadith(1, many))), FakeNarratorRepository(many), reviews, scheduler, { now }, { utc },
        )
        many.forEach { n -> outcomes(n.id, errors = n.id.toInt(), successes = 1) }

        val weakest = big().weakestNarrators

        assertEquals(5, weakest.size)
        assertEquals(listOf(8L, 7L, 6L, 5L, 4L), weakest.map { it.narratorId })
    }

    @Test
    fun narratorsMissingFromTheLibraryAreSkippedRatherThanCrashing() = runBlocking {
        outcomes(99, errors = 4, successes = 0)
        outcomes(3, errors = 1, successes = 1)

        assertEquals(listOf(3L), useCase().weakestNarrators.map { it.narratorId })
    }
}
