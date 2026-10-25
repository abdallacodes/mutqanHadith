package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_1_CORE
import com.mutqan.hadith.domain.model.MemorizationLayer.LAYER_3_TRANSMISSION_TERMS
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.ReviewBatching
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.scheduler.ScheduleRequest
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
import com.mutqan.hadith.ui.review.HintKind
import com.mutqan.hadith.ui.review.ReviewCard
import com.mutqan.hadith.ui.review.companionHint
import com.mutqan.hadith.ui.review.hintKinds
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the retention suite that need no screen: cutting a backlog into batches, the estimate of a sitting,
 * the daily limit on new hadiths, the hint text, and the advanced student's way round the layers.
 */
class RetentionSuiteDomainTest {

    // -- Batches -----------------------------------------------------------------------------

    @Test
    fun twelveDueReviewsAreCutIntoBatchesOfFiveFiveAndTwo() {
        val batches = ReviewBatching.batches((1..12).toList())

        assertEquals(listOf(5, 5, 2), batches.map { it.size })
        assertEquals(listOf(1, 2, 3, 4, 5), batches.first())
        assertEquals(listOf(11, 12), batches.last())
    }

    @Test
    fun aShortListIsOneBatchAndNothingIsNone() {
        assertEquals(listOf(listOf(1, 2, 3)), ReviewBatching.batches(listOf(1, 2, 3)))
        assertEquals(listOf((1..5).toList()), ReviewBatching.batches((1..5).toList()))
        assertTrue(ReviewBatching.batches(emptyList<Int>()).isEmpty())
    }

    @Test
    fun moreThanFiveDueIsABacklog() {
        assertFalse(ReviewBatching.isBacklog(0))
        assertFalse(ReviewBatching.isBacklog(5))
        assertTrue(ReviewBatching.isBacklog(6))
        assertEquals(5, ReviewBatching.nextBatchSize(12))
        assertEquals(3, ReviewBatching.nextBatchSize(3))
        assertEquals(0, ReviewBatching.nextBatchSize(0))
    }

    @Test
    fun aSittingIsEstimatedAtAMinuteAndAHalfPerHadithOfTheNextBatch() {
        // min(due, 5) * 1.5, rounded; a half goes to the even minute, so 3 hadiths are about 4 minutes.
        val minutes = (0..8).associateWith { ReviewBatching.estimatedMinutes(it) }

        assertEquals(mapOf(0 to 0, 1 to 2, 2 to 3, 3 to 4, 4 to 6, 5 to 8, 6 to 8, 7 to 8, 8 to 8), minutes)
        assertEquals(8, ReviewBatching.estimatedMinutes(500))
    }

    // -- Pacing ------------------------------------------------------------------------------

    private var clock = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    private val preferences = InMemoryStudyPreferences()
    private val pacing = DailyPacing(preferences, { ZoneOffset.UTC }, { clock })

    @Test
    fun theThirdNewHadithOfADayIsTheFirstOneAskedAbout() {
        assertFalse(pacing.isOverLimit())
        pacing.recordStart()
        assertFalse("one started: the second goes ahead", pacing.isOverLimit())
        pacing.recordStart()
        assertTrue("two started: the third is asked about", pacing.isOverLimit())
        assertEquals(2, pacing.startedToday())
    }

    @Test
    fun aNewCalendarDayStartsFromZero() {
        pacing.recordStart()
        pacing.recordStart()
        assertTrue(pacing.isOverLimit())

        clock += DAY_MS

        assertEquals(0, pacing.startedToday())
        assertFalse(pacing.isOverLimit())
    }

    @Test
    fun theDayIsTheLocalCalendarDayNotTheUtcOne() {
        // 22:13 UTC is already the next morning in Tokyo, so the same moment falls on two different days.
        val tokyo = DailyPacing(preferences, { ZoneOffset.ofHours(9) }, { clock })
        pacing.recordStart()
        pacing.recordStart()

        assertTrue(pacing.isOverLimit())
        assertFalse("in Tokyo it is another day", tokyo.isOverLimit())
    }

    @Test
    fun onlyAHadithReallyTakenIntoStudyCountsTowardsTheDay() = runTest {
        val hadiths = FakeHadithRepository((1L..3L).map { hadith(it, listOf(narrator(1, "عمر", tier = "صحابي")), level = 1) })
        val reviews = FakeReviewRepository()
        val start = StartMemorizingUseCase(hadiths, reviews, pacing) { clock }

        start(1)
        start(1) // asking again for the same hadith changes nothing
        assertEquals(1, pacing.startedToday())

        start(2)
        assertEquals(2, pacing.startedToday())
        assertTrue(pacing.isOverLimit())
    }

    // -- Hints -------------------------------------------------------------------------------

    @Test
    fun theCompanionHintIsTheFirstLetterJoinedToTheDots() {
        assertEquals("عـ…", companionHint("عمر بن الخطاب"))
        assertEquals("عـ…", companionHint("عُمَرُ بنُ الخطّاب")) // the vowels are not part of the name's first letter
        assertEquals("بـ…", companionHint("بلال"))
    }

    @Test
    fun aLetterThatDoesNotJoinIsLeftBareAndOtherScriptsAreKept() {
        assertEquals("أ…", companionHint("أبو هريرة"))
        assertEquals("و…", companionHint("وائلة"))
        assertEquals("U…", companionHint("Umar"))
        assertEquals("", companionHint("   "))
        assertEquals("", companionHint(""))
    }

    private fun card(sahabi: String, takhrij: String) =
        ReviewCard(1, 1, "الأربعون النووية", "", "متن", emptyList(), sahabi = sahabi, takhrij = takhrij)

    @Test
    fun theHintsOfACardAreTheCompanionThenTheTakhrijIfItHasThem() {
        assertEquals(listOf(HintKind.COMPANION, HintKind.TAKHRIJ), card("عمر", "متفق عليه").hintKinds)
        assertEquals(listOf(HintKind.TAKHRIJ), card("", "متفق عليه").hintKinds)
        assertEquals(listOf(HintKind.COMPANION), card("عمر", "").hintKinds)
        assertTrue(card(" ", " ").hintKinds.isEmpty())
    }

    // -- The advanced student ----------------------------------------------------------------

    private val coreRecord = MasteryRecordEntity(1, 1f, 0f, clock - DAY_MS, null, clock, 1)

    @Test
    fun byDefaultALayerOneHadithStaysOnLayerOne() {
        assertEquals(LAYER_1_CORE, DualDecayScheduler().layerStatus(coreRecord).layer)
    }

    @Test
    fun theAdvancedChoiceOpensEveryLayerOfAHadithInStudyAndCanBeTakenBack() {
        val choice = MutableStateFlow(true)
        val scheduler = DualDecayScheduler(unlockAllLayers = choice)

        assertEquals(LayerStatus(LAYER_3_TRANSMISSION_TERMS, 1f), scheduler.layerStatus(coreRecord))
        // A hadith that was never taken into study is still on its first step.
        assertEquals(LayerStatus.Fresh, scheduler.layerStatus(null))

        choice.value = false

        assertEquals(LAYER_1_CORE, scheduler.layerStatus(coreRecord).layer)
    }

    @Test
    fun theSchedulerSaysWhenWhatDecidesTheLayersChanges() = runTest {
        val choice = MutableStateFlow(false)
        val scheduler = DualDecayScheduler(unlockAllLayers = choice)

        assertFalse(scheduler.layerRules.first())
        choice.value = true
        assertTrue(scheduler.layerRules.first())
        assertFalse("the default scheduler never opens everything", DualDecayScheduler().layerRules.first())
    }

    @Test
    fun aReviewUnderTheAdvancedChoiceSchedulesTheChainToo() {
        val gentle = DualDecayScheduler()
        val advanced = DualDecayScheduler(unlockAllLayers = MutableStateFlow(true))
        val request = ScheduleRequest(1, previous = null, matnRating = ReviewRating.GOOD, sanadRating = null, now = clock)

        // A first review of the matn alone: on layer one only the matn has a clock; with everything open the chain is
        // due a day later, ahead of the matn's two days.
        assertEquals(clock + 2 * DAY_MS, gentle.schedule(request).record.nextReviewDue)
        assertEquals(clock + DAY_MS, advanced.schedule(request).record.nextReviewDue)
    }

    @Test
    fun theStoredChoiceIsReadBackAndTodaysCountsAreKeptPerDay() {
        val prefs = InMemoryStudyPreferences()
        assertFalse(prefs.unlockAllLayers.value)

        prefs.setUnlockAllLayers(true)
        prefs.recordNewStart(19_000)
        prefs.recordNewStart(19_000)

        assertTrue(prefs.unlockAllLayers.value)
        assertEquals(2, prefs.newStartsOn(19_000))
        assertEquals(0, prefs.newStartsOn(19_001))
    }
}
