package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.DAY_MS
import com.mutqan.hadith.domain.FakeCollectionRepository
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.ReadOnlyReviewRepository
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.pacing.DailyPacing
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.CreateCollectionUseCase
import com.mutqan.hadith.domain.usecase.DeleteCollectionUseCase
import com.mutqan.hadith.domain.usecase.DueHadith
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.GetUnseenHadithsUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.RenameCollectionUseCase
import com.mutqan.hadith.domain.usecase.SetCollectionMembershipUseCase
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.collections.CollectionPracticeViewModel
import com.mutqan.hadith.ui.collections.PracticeEvent
import com.mutqan.hadith.ui.common.UiState
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The hard invariant of the personal collections: a practice of a collection is not a review. Whatever is done in one, and
 * whatever is done to a collection, the global spaced-repetition state stays byte for byte as it was: every mastery
 * record with its stabilities and dates, the narrators' history, the queue of what is due, what is new, the dashboard, and
 * the count of new hadiths started today.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdHocReviewInvarianceTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()

    private val chain = listOf(narrator(1, "الحميدي"), narrator(2, "سفيان"), narrator(3, "يحيى"), narrator(4, "عمر", tier = "صحابي"))
    private val library = (1L..9L).map { hadith(it, if (it % 2 == 0L) chain else chain.takeLast(1), level = if (it <= 4) 3 else 1) }
    private val hadiths = FakeHadithRepository(library)
    private val collections = FakeCollectionRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private val preferences = InMemoryStudyPreferences()
    private val pacing = DailyPacing(preferences, { ZoneOffset.UTC }, { now })

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A record whose chain has not been studied yet when [sanadStudied] is false: it stays on layer 1 until the matn is mastered. */
    private fun record(id: Long, matn: Float, sanad: Float, dueInDays: Int, sanadStudied: Boolean = true) =
        MasteryRecordEntity(id, matn, sanad, now - 3 * DAY_MS, if (sanadStudied) now - 3 * DAY_MS else null, now + dueInDays * DAY_MS, 4)

    /**
     * A user in the middle of the curriculum: 1 is overdue on layer 1, 2 is not due for days, 3 has its chain open, 4 has every
     * layer open and is overdue; 5 to 9 were never started. 7 is due nowhere but is in no collection, while 1 to 6 are in one.
     */
    @Before
    fun aStudyHistory() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        reviews.mastery[1] = record(1, matn = 2f, sanad = 0f, dueInDays = -2, sanadStudied = false)
        reviews.mastery[2] = record(2, matn = 6f, sanad = 2f, dueInDays = 5)
        reviews.mastery[3] = record(3, matn = 40f, sanad = 1f, dueInDays = 1)
        reviews.mastery[4] = record(4, matn = 60f, sanad = 60f, dueInDays = -1)
        reviews.events += chain.mapIndexed { i, n -> NarratorReviewEventEntity(i + 1L, 4, n.id, now - DAY_MS, succeeded = i != 1) }
        preferences.recordNewStart(today())
        assertEquals(MemorizationLayer.LAYER_1_CORE, scheduler.layerStatus(reviews.mastery[1]).layer)
        assertEquals(MemorizationLayer.LAYER_2_SANAD_TREE, scheduler.layerStatus(reviews.mastery[3]).layer)
        assertEquals(MemorizationLayer.LAYER_3_TRANSMISSION_TERMS, scheduler.layerStatus(reviews.mastery[4]).layer)
    }

    private fun today() = java.time.Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

    /** Everything the global scheduler is: what a practice or a collection must leave exactly as it found it. */
    private data class Schedule(
        val mastery: Map<Long, MasteryRecordEntity>,
        val events: List<NarratorReviewEventEntity>,
        val due: List<DueHadith>,
        val unseen: List<Long>,
        val dashboard: StudyDashboard,
        val startedToday: Int,
    )

    private fun schedule(): Schedule = runBlocking {
        Schedule(
            mastery = reviews.mastery.toMap(),
            events = reviews.events.toList(),
            due = GetDueHadithsUseCase(hadiths, reviews, scheduler) { now }(),
            unseen = GetUnseenHadithsUseCase(hadiths)(limit = 50).map { it.hadith.id },
            dashboard = ObserveStudyDashboardUseCase(hadiths, reviews, scheduler, { ZoneOffset.UTC }, { now })().first(),
            startedToday = pacing.startedToday(),
        )
    }

    private fun practiceVm(collectionId: Long) =
        CollectionPracticeViewModel(collectionId, collections, hadiths, GetLayerStatusesUseCase(ReadOnlyReviewRepository(reviews), scheduler))

    private fun CollectionPracticeViewModel.session() = (state.value as UiState.Success).data

    private fun collectionOf(vararg ids: Long): Long = runBlocking {
        val id = collections.create("Mine", 0, now)
        ids.forEachIndexed { i, hadithId -> collections.add(id, hadithId, now + i) }
        id
    }

    /** Uses every control a practice has on every card of the round, judging with [recalled]. */
    private fun playRound(vm: CollectionPracticeViewModel, recalled: (Long) -> Boolean) {
        var guard = 0
        while (!vm.session().isFinished && guard++ < 100) {
            val card = vm.session().current!!
            repeat(3) { vm.onEvent(PracticeEvent.Hint) }
            vm.onEvent(PracticeEvent.ToggleTashkeel)
            repeat(3) { vm.onEvent(PracticeEvent.Reveal) }
            vm.onEvent(PracticeEvent.RevealTerms)
            vm.onEvent(if (recalled(card.hadithId)) PracticeEvent.Recalled else PracticeEvent.NeedsLook)
        }
        assertTrue("the round ended", vm.session().isFinished)
    }

    // -- The proof -----------------------------------------------------------------------------------------------------

    @Test
    fun theScheduleOfThisTestIsWhatItSaysItIs() {
        val before = schedule()

        assertEquals(listOf(1L, 4L), before.due.map { it.hadith.hadith.id }.sorted())
        assertEquals(setOf(1L, 2L, 3L, 4L), before.mastery.keys)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), before.unseen.sorted())
        assertEquals(1, before.startedToday)
    }

    @Test
    fun aWholePracticeLeavesTheGlobalScheduleExactlyAsItWas() {
        val before = schedule()
        val vm = practiceVm(collectionOf(1, 2, 3, 4, 5, 6))

        playRound(vm) { it % 2 == 0L }
        vm.onEvent(PracticeEvent.PracticeAgain)
        playRound(vm) { false }
        vm.onEvent(PracticeEvent.PracticeAgain)
        playRound(vm) { true }

        assertEquals(before, schedule())
    }

    @Test
    fun practisingHadithsThatWereNeverStartedDoesNotStartThem() {
        val before = schedule()
        val vm = practiceVm(collectionOf(5, 6, 8))

        playRound(vm) { true }

        val after = schedule()
        assertEquals(before, after)
        assertNull(reviews.mastery[5])
        assertNull(reviews.mastery[6])
        assertEquals("the day's count of new hadiths is not spent", 1, after.startedToday)
        assertEquals("they are still new", setOf(5L, 6L, 7L, 8L, 9L), after.unseen.toSet())
    }

    @Test
    fun theDueQueueGivesTheSameHadithsInTheSameOrderAfterAPractice() {
        val before = schedule().due
        val id = collectionOf(1, 4)

        playRound(practiceVm(id)) { true }
        playRound(practiceVm(id)) { false }

        // Recalling the overdue hadiths in a practice does not make them less due.
        assertEquals(before, schedule().due)
        assertEquals(listOf(1L, 4L), schedule().due.map { it.hadith.hadith.id }.sorted())
    }

    @Test
    fun noStabilityOrDateOfAnyRecordChangesWhateverIsAnswered() {
        val before = reviews.mastery.mapValues { it.value.copy() }
        listOf(true, false).forEach { answer ->
            val vm = practiceVm(collectionOf(1, 2, 3, 4))
            playRound(vm) { answer }
        }

        assertEquals(before, reviews.mastery)
        reviews.mastery.values.forEach {
            assertEquals(before.getValue(it.hadithId).matnStability, it.matnStability, 0f)
            assertEquals(before.getValue(it.hadithId).sanadStability, it.sanadStability, 0f)
            assertEquals(before.getValue(it.hadithId).nextReviewDue, it.nextReviewDue)
            assertEquals(before.getValue(it.hadithId).repetitionCount, it.repetitionCount)
        }
    }

    @Test
    fun theNarratorsHistoryIsNotAddedTo() {
        val before = reviews.events.toList()
        val vm = practiceVm(collectionOf(3, 4))

        playRound(vm) { false }

        assertEquals(before, reviews.events)
    }

    @Test
    fun curatingCollectionsLeavesTheScheduleAlone() = runBlocking {
        val before = schedule()
        val create = CreateCollectionUseCase(collections) { now }
        val membership = SetCollectionMembershipUseCase(collections) { now }

        val first = (create("Sermon", firstHadithId = 5) as com.mutqan.hadith.domain.model.CollectionSaved.Saved).id
        membership(first, 1, true)
        membership(first, 6, true)
        membership(first, 1, false)
        RenameCollectionUseCase(collections)(first, "Renamed", 2)
        DeleteCollectionUseCase(collections)(first)

        assertEquals("filing a hadith, even an unstarted one, starts nothing", before, schedule())
    }

    // -- The proof can fail --------------------------------------------------------------------------------------------

    @Test
    fun aRealReviewDoesChangeTheScheduleSoTheComparisonIsNotVacuous() = runBlocking {
        val before = schedule()

        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(1, ReviewRating.GOOD, ReviewRating.GOOD)
        assertNotEquals(before, schedule())
        assertNotEquals(before.mastery.getValue(1), reviews.mastery.getValue(1))
        assertFalse(schedule().due.any { it.hadith.hadith.id == 1L })
    }

    @Test
    fun startingAHadithChangesTheScheduleToo() = runBlocking {
        val before = schedule()

        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(5, ReviewRating.GOOD, null)

        assertNotEquals(before, schedule())
        assertTrue(5L !in schedule().unseen)
    }
}
