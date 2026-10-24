package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.repository.RoomHadithRepository
import com.mutqan.hadith.data.repository.RoomReviewRepository
import com.mutqan.hadith.domain.model.ReviewRating.EASY
import com.mutqan.hadith.domain.model.ReviewRating.GOOD
import com.mutqan.hadith.domain.model.ReviewRating.HARD
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetDueHadithsUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.SubmitReviewUseCase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Phase 2 use cases wired to the real Room repositories and the seeded Bukhari data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewFlowIntegrationTest {

    private lateinit var db: MutqanDatabase
    private var clock = 1_700_000_000_000L

    private lateinit var submit: SubmitReviewUseCase
    private lateinit var getDue: GetDueHadithsUseCase
    private lateinit var heatmap: GetNarratorChainHeatmapUseCase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = MutqanDatabase.builder(context, name = null).allowMainThreadQueries().build()
        val hadiths = RoomHadithRepository(db)
        val reviews = RoomReviewRepository(db)
        val scheduler = DualDecayScheduler()
        submit = SubmitReviewUseCase(hadiths, reviews, scheduler) { clock }
        getDue = GetDueHadithsUseCase(hadiths, reviews, scheduler) { clock }
        heatmap = GetNarratorChainHeatmapUseCase(hadiths, reviews)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun reviewCycle_persistsSchedulesAndSurfacesTheWeakNarrator() = runBlocking {
        val niyyah = db.hadithDao().getAllWithSanad().first { it.hadith.hadithNumber == 1 }
        val yahya = niyyah.sanad.first { it.narrator.primaryName == "يحيى بن سعيد الأنصاري" }.narrator.id

        assertTrue(getDue().isEmpty())

        // Matn is easy, but the user keeps losing Yahya in the chain.
        var outcome = submit(niyyah.hadith.id, EASY, HARD, failedNarratorIds = setOf(yahya))
        assertEquals(outcome.record, db.masteryDao().getByHadithId(niyyah.hadith.id))
        assertEquals(clock + outcome.sanadInterval.inWholeMilliseconds, outcome.nextReviewDue)
        assertTrue(outcome.sanadInterval < outcome.matnInterval)

        assertTrue("not due before its interval elapses", getDue().isEmpty())
        clock = outcome.nextReviewDue
        assertEquals(listOf(niyyah.hadith.id), getDue().map { it.hadith.hadith.id })

        outcome = submit(niyyah.hadith.id, EASY, HARD, failedNarratorIds = setOf(yahya))
        clock = outcome.nextReviewDue
        submit(niyyah.hadith.id, GOOD, GOOD)

        val map = heatmap(niyyah.hadith.id)
        assertEquals(niyyah.sanad.map { it.narrator.id }, map.nodes.map { it.performance.narratorId })
        val weakest = map.weakestLink!!
        assertEquals(yahya, weakest.performance.narratorId)
        assertEquals(2, weakest.performance.errorCount)
        assertEquals(1, weakest.performance.successCount)
        assertEquals(1, map.nodes.count { it.performance.errorCount > 0 })

        assertEquals(3, db.masteryDao().getByHadithId(niyyah.hadith.id)!!.repetitionCount)
    }

    @Test
    fun dueQueue_ordersSeededHadithsByUrgency() = runBlocking {
        val ids = db.hadithDao().getAllWithSanad().take(3).map { it.hadith.id }
        submit(ids[0], EASY, EASY)
        submit(ids[1], HARD, HARD)
        submit(ids[2], GOOD, GOOD)

        clock += 30L * 86_400_000L
        val due = getDue()

        assertEquals(3, due.size)
        // The shakiest first review is the most forgotten after the same wait.
        assertEquals(ids[1], due.first().hadith.hadith.id)
        assertEquals(ids[0], due.last().hadith.hadith.id)
        assertEquals(due.sortedBy { it.retrievability.weakest }.map { it.hadith.hadith.id }, due.map { it.hadith.hadith.id })
    }

    @Test
    fun deletingAHadith_cascadesToItsReviewHistory() = runBlocking {
        val id = db.hadithDao().getAllWithSanad().first().hadith.id
        submit(id, GOOD, GOOD)
        db.openHelper.writableDatabase.execSQL("DELETE FROM hadith WHERE id = $id")

        assertEquals(null, db.masteryDao().getByHadithId(id))
        assertTrue(db.reviewHistoryDao().getOutcomeCounts(id).isEmpty())
    }
}
