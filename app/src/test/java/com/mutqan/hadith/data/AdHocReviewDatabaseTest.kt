package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.repository.RoomCollectionRepository
import com.mutqan.hadith.data.repository.RoomHadithRepository
import com.mutqan.hadith.data.repository.RoomReviewRepository
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetLayerStatusesUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import com.mutqan.hadith.ui.collections.CollectionPracticeViewModel
import com.mutqan.hadith.ui.collections.PracticeEvent
import com.mutqan.hadith.ui.collections.PracticeSession
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The same promise as [com.mutqan.hadith.ui.AdHocReviewInvarianceTest], proved on the real thing: a practice of a collection
 * run over the real repositories and the real catalog leaves every row of every table of the database exactly as it was.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdHocReviewDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MutqanDatabase
    private lateinit var collections: RoomCollectionRepository
    private val scheduler = DualDecayScheduler()
    private val now = 1_800_000_000_000L

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = MutqanDatabase.builder(context, name = null).allowMainThreadQueries().build()
        collections = RoomCollectionRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private val hadiths get() = RoomHadithRepository(db)
    private val reviews get() = RoomReviewRepository(db)

    private fun record(id: Long, matn: Float, sanad: Float, dueAt: Long, sanadStudied: Boolean = true) = MasteryRecordEntity(
        id, matn, sanad, matnLastReviewedAt = now - 86_400_000L, sanadLastReviewedAt = if (sanadStudied) now - 86_400_000L else null,
        nextReviewDue = dueAt, repetitionCount = 4,
    )

    /**
     * Hadith 1 is overdue and has every layer open, 2 is learning with its chain open and not due, 3 is overdue on layer 1, its chain not yet studied;
     * 4, 5 and 6 were never started. The narrators of 1 have a history.
     */
    private suspend fun studyHistory() {
        db.masteryDao().upsertAll(
            listOf(
                record(1, matn = 60f, sanad = 60f, dueAt = now - 86_400_000L),
                record(2, matn = 40f, sanad = 1f, dueAt = now + 3 * 86_400_000L),
                record(3, matn = 2f, sanad = 0f, dueAt = now - 2 * 86_400_000L, sanadStudied = false),
            ),
        )
        val chain = db.hadithDao().getHadithWithSanad(1)!!.sanad.map { it.narrator.id }
        db.reviewHistoryDao().insertAll(chain.mapIndexed { i, id -> NarratorReviewEventEntity(hadithId = 1, narratorId = id, reviewedAt = now - 86_400_000L, succeeded = i % 2 == 0) })
    }

    /** Every row of every table the app owns, so that nothing can change without this noticing. */
    private fun dumpEverything(): Map<String, List<List<String?>>> {
        val sqlite = db.openHelper.writableDatabase
        val tables = sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name NOT LIKE 'room_%' ORDER BY name")
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }
        return tables.associateWith { table ->
            sqlite.query("SELECT * FROM $table ORDER BY 1, 2").use { c ->
                generateSequence { if (c.moveToNext()) (0 until c.columnCount).map { i -> c.getString(i) } else null }.toList()
            }
        }
    }

    private suspend fun practiceOf(vararg hadithIds: Long, title: String = "Mine"): CollectionPracticeViewModel {
        val id = collections.create(title, accent = 0, createdAt = now)
        hadithIds.forEachIndexed { i, hadithId -> collections.add(id, hadithId, addedAt = now + i) }
        val vm = CollectionPracticeViewModel(id, collections, hadiths, GetLayerStatusesUseCase(reviews, scheduler))
        withTimeout(10_000) { vm.state.first { it !is UiState.Loading } }
        return vm
    }

    private fun CollectionPracticeViewModel.session(): PracticeSession = (state.value as UiState.Success).data

    private fun CollectionPracticeViewModel.playRound(recalled: (Long) -> Boolean) {
        var guard = 0
        while (!session().isFinished && guard++ < 100) {
            val card = session().current!!
            repeat(3) { onEvent(PracticeEvent.Hint) }
            repeat(3) { onEvent(PracticeEvent.Reveal) }
            onEvent(PracticeEvent.RevealTerms)
            onEvent(PracticeEvent.ToggleTashkeel)
            onEvent(if (recalled(card.hadithId)) PracticeEvent.Recalled else PracticeEvent.NeedsLook)
        }
        assertTrue(session().isFinished)
    }

    private suspend fun dashboard() = ObserveStudyDashboardUseCase(hadiths, reviews, scheduler, now = { now })().first()

    private suspend fun due() = GetDueHadithsUseCase(hadiths, reviews, scheduler) { now }()

    @Test
    fun theDeckIsMadeOfTheCatalogsRealHadithsInTheOrderTheyWereAdded() = runBlocking {
        studyHistory()

        val vm = practiceOf(3, 1, 5)

        assertEquals(listOf(3L, 1L, 5L), vm.session().deck.map { it.hadithId })
        assertEquals("the layer is the one the user earned", listOf(1, 3, 1), vm.session().deck.map { it.activeLayer.number })
        assertTrue(vm.session().deck.all { it.matn.isNotBlank() })
    }

    @Test
    fun aWholePracticeLeavesEveryRowOfEveryTableAsItWas() = runBlocking {
        studyHistory()
        val vm = practiceOf(1, 2, 3, 4, 5, 6)
        val before = dumpEverything()
        val dueBefore = due()
        val dashboardBefore = dashboard()

        vm.playRound { it % 2 == 0L }
        vm.onEvent(PracticeEvent.PracticeAgain)
        vm.playRound { false }
        vm.onEvent(PracticeEvent.PracticeAgain)
        vm.playRound { true }

        assertEquals(before, dumpEverything())
        assertEquals(dueBefore, due())
        assertEquals(dashboardBefore, dashboard())
        assertEquals(setOf(1L, 3L), due().map { it.hadith.hadith.id }.toSet())
    }

    @Test
    fun practisingHadithsNeverStartedDoesNotCreateARecordForThem() = runBlocking {
        studyHistory()
        val vm = practiceOf(4, 5, 6)
        val before = dumpEverything()

        vm.playRound { true }

        assertEquals(before, dumpEverything())
        assertEquals(listOf(1L, 2L, 3L), reviews.getAllMastery().map { it.hadithId }.sorted())
        assertEquals("they are still new", 394, dashboard().newCount)
    }

    @Test
    fun aPracticeOnAnEmptyCollectionHasNothingToWriteEither() = runBlocking {
        studyHistory()
        val vm = practiceOf()
        val before = dumpEverything()

        assertTrue(vm.session().deck.isEmpty())
        assertTrue(vm.session().isFinished)
        vm.onEvent(PracticeEvent.Recalled)
        vm.onEvent(PracticeEvent.PracticeAgain)

        assertEquals(before, dumpEverything())
    }

    @Test
    fun deletingTheCollectionAfterAPracticeChangesNoProgressEither() = runBlocking {
        studyHistory()
        val vm = practiceOf(1, 2, 3)
        vm.playRound { true }
        val progress = dumpEverything().filterKeys { it != "collections" && it != "hadith_collection_cross_ref" }

        collections.delete(collections.observeAll().first().single().id)

        assertEquals(progress, dumpEverything().filterKeys { it != "collections" && it != "hadith_collection_cross_ref" })
    }

    // -- The comparison can fail -------------------------------------------------------------------------------------

    @Test
    fun aRealReviewDoesChangeTheDump() = runBlocking {
        studyHistory()
        val before = dumpEverything()

        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(1, ReviewRating.GOOD, ReviewRating.GOOD)

        val after = dumpEverything()
        assertNotEquals(before.getValue("mastery_record"), after.getValue("mastery_record"))
        assertNotEquals(before.getValue("narrator_review_event"), after.getValue("narrator_review_event"))
    }

    @Test
    fun aRealReviewOfAHadithNeverStartedAddsARecordWhichAPracticeDoesNot() = runBlocking {
        studyHistory()
        val vm = practiceOf(4)
        vm.playRound { true }
        assertEquals(3, db.masteryDao().getAll().size)

        SubmitReviewUseCase(hadiths, reviews, scheduler) { now }(4, ReviewRating.GOOD, null)

        assertEquals(4, db.masteryDao().getAll().size)
    }
}
