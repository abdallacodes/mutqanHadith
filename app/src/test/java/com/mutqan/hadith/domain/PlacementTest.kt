package com.mutqan.hadith.domain

import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.ScheduleRequest
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.PlacementUseCase
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling the app where the learner starts: the bands, the records they leave and what is never overwritten. */
class PlacementTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val preferences = InMemoryStudyPreferences()
    private val hadiths = FakeHadithRepository((1L..5L).map { hadith(it, listOf(narrator(1, "عمر", tier = "صحابي"))) })
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val pacing = DailyPacing(preferences, { ZoneOffset.UTC }, { now })
    private val placement = PlacementUseCase(hadiths, reviews, scheduler, preferences) { now }

    // -- Bands -------------------------------------------------------------------------------

    @Test
    fun aPlacedStabilityReadsBackAsItsOwnBand() {
        RecallBand.entries.forEach { assertEquals(it, RecallBand.of(it.placedStabilityDays)) }
    }

    @Test
    fun theBandsGrowWithStabilityAndANeverLearnedHadithHasNone() {
        assertNull(RecallBand.of(0f))
        assertEquals(RecallBand.WEAK, RecallBand.of(0.3f))
        assertEquals(RecallBand.WEAK, RecallBand.of(2.0f)) // a first GOOD review
        assertEquals(RecallBand.GOOD, RecallBand.of(3.0f))
        assertEquals(RecallBand.VERY_GOOD, RecallBand.of(9f))
        assertEquals(RecallBand.EXCELLENT, RecallBand.of(100f))
        val stabilities = RecallBand.entries.map { it.placedStabilityDays }
        assertEquals(stabilities.sorted(), stabilities)
    }

    @Test
    fun onlyTheLevelsAboveBeginnerOfferTheCheckAndMarkHadithByDefault() {
        assertFalse(StartingPoint.BEGINNER.offersCheck)
        assertNull(StartingPoint.BEGINNER.defaultBand)
        assertTrue(StartingPoint.INTERMEDIATE.offersCheck)
        assertEquals(RecallBand.WEAK, StartingPoint.INTERMEDIATE.defaultBand)
        assertTrue(StartingPoint.EXPERT.offersCheck)
        assertEquals(RecallBand.GOOD, StartingPoint.EXPERT.defaultBand)
    }

    // -- The record a band leaves ------------------------------------------------------------

    @Test
    fun aPlacedRecordIsAMatnReviewedNowWithTheBandsStabilityAndAnUnlearnedSanad() {
        val record = scheduler.place(7, RecallBand.GOOD, now)

        assertEquals(7L, record.hadithId)
        assertEquals(4f, record.matnStability)
        assertEquals(now, record.matnLastReviewedAt)
        assertEquals(0f, record.sanadStability)
        assertNull(record.sanadLastReviewedAt)
        assertEquals(1, record.repetitionCount)
        // Due when its recall falls to the target: stability days later, to the day's accuracy.
        assertEquals(4.0, (record.nextReviewDue - now) / DAY_MS.toDouble(), 0.05)
    }

    @Test
    fun eachBandIsDueLaterThanTheOneBelowAndItsLayerFollowsTheMatnMastery() {
        val records = RecallBand.entries.map { scheduler.place(1, it, now) }

        // The two top bands both have the Sanad tree open, so each is invited to it a day after; the lower ones are due later and later.
        assertEquals(records.take(2).map { it.nextReviewDue }.sorted(), records.take(2).map { it.nextReviewDue })
        assertEquals(MemorizationLayer.LAYER_1_CORE, scheduler.layerStatus(records[0]).layer)
        assertEquals(MemorizationLayer.LAYER_1_CORE, scheduler.layerStatus(records[1]).layer)
        // Matn mastery 0.5 opens the Sanad tree; 12 and 45 days are past it.
        assertEquals(MemorizationLayer.LAYER_2_SANAD_TREE, scheduler.layerStatus(records[2]).layer)
        assertEquals(MemorizationLayer.LAYER_2_SANAD_TREE, scheduler.layerStatus(records[3]).layer)
    }

    @Test
    fun aPlacedHadithWhoseSanadTreeIsOpenInvitesItsFirstSanadStudyAfterADay() {
        val record = scheduler.place(1, RecallBand.EXCELLENT, now)

        assertEquals(now + DAY_MS, record.nextReviewDue)
    }

    @Test
    fun aPlacedHadithCarriesOnFromItsStabilityWhenReviewed() {
        val placed = scheduler.place(1, RecallBand.GOOD, now)

        val reviewed = scheduler.schedule(ScheduleRequest(1, placed, ReviewRating.GOOD, null, now + 4 * DAY_MS)).record

        assertTrue("a good review makes it hold longer", reviewed.matnStability > placed.matnStability)
        assertEquals(2, reviewed.repetitionCount)
    }

    // -- Placing hadiths ---------------------------------------------------------------------

    @Test
    fun chooseRemembersTheAnswerAndMarksNothingByItself() = runTest {
        placement.choose(StartingPoint.INTERMEDIATE)

        assertEquals(StartingPoint.INTERMEDIATE, preferences.startingPoint.value)
        assertTrue(reviews.mastery.isEmpty())
    }

    @Test
    fun placingAHadithWritesItsRecordWithoutCountingAgainstTheDay() = runTest {
        assertTrue(placement.place(1, RecallBand.VERY_GOOD))
        assertTrue(placement.place(2, RecallBand.WEAK))
        assertTrue(placement.place(3, RecallBand.EXCELLENT))

        assertEquals(setOf(1L, 2L, 3L), reviews.mastery.keys)
        assertEquals(12f, reviews.mastery.getValue(1).matnStability)
        assertEquals(0, pacing.startedToday())
        assertFalse(pacing.isOverLimit())
    }

    @Test
    fun aHadithWithProgressIsNeverOverwritten() = runTest {
        val earned = scheduler.schedule(ScheduleRequest(1, null, ReviewRating.EASY, null, now)).record
        reviews.mastery[1] = earned

        assertFalse(placement.place(1, RecallBand.WEAK))

        assertEquals(earned, reviews.mastery.getValue(1))
    }

    @Test
    fun aHadithThatDoesNotExistIsRefused() = runTest {
        val failure = runCatching { placement.place(99, RecallBand.GOOD) }.exceptionOrNull()

        assertTrue(failure is NoSuchElementException)
        assertTrue(reviews.mastery.isEmpty())
    }

    @Test
    fun placingTheRestFillsOnlyWhatIsStillUnmarked() = runTest {
        placement.place(2, RecallBand.EXCELLENT)

        val marked = placement.placeRemaining(RecallBand.WEAK)

        assertEquals(4, marked)
        assertEquals(RecallBand.EXCELLENT, RecallBand.of(reviews.mastery.getValue(2).matnStability))
        listOf(1L, 3L, 4L, 5L).forEach { assertEquals(RecallBand.WEAK, RecallBand.of(reviews.mastery.getValue(it).matnStability)) }
    }

    @Test
    fun skippingTheCheckMarksEverythingWithTheStartingPointsDefault() = runTest {
        assertEquals(0, placement.placeRemainingByDefault(StartingPoint.BEGINNER))
        assertTrue(reviews.mastery.isEmpty())

        assertEquals(5, placement.placeRemainingByDefault(StartingPoint.EXPERT))

        assertEquals(setOf(RecallBand.GOOD), reviews.mastery.values.map { RecallBand.of(it.matnStability) }.toSet())
    }

    @Test
    fun theLayerStatusOfAPlacedHadithIsNotFresh() = runTest {
        placement.place(1, RecallBand.GOOD)

        assertTrue(scheduler.layerStatus(reviews.mastery.getValue(1)) != LayerStatus.Fresh)
        assertEquals(LayerStatus.Fresh, scheduler.layerStatus(reviews.mastery[2]))
    }
}
