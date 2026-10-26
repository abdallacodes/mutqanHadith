package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.repository.RoomBackupRepository
import com.mutqan.hadith.data.repository.RoomCollectionRepository
import com.mutqan.hadith.domain.backup.UserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Personal collections on the real schema and the real catalog, with the user's study history next to them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionsDatabaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: MutqanDatabase
    private lateinit var collections: RoomCollectionRepository

    @Before
    fun setUp() {
        db = MutqanDatabase.builder(context, name = null).allowMainThreadQueries().build()
        collections = RoomCollectionRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun record(hadithId: Long, matn: Float = 6f, sanad: Float = 2f) = MasteryRecordEntity(
        hadithId, matn, sanad, matnLastReviewedAt = 1_000L, sanadLastReviewedAt = 2_000L, nextReviewDue = 9_000L, repetitionCount = 3,
    )

    /** A study history on hadiths 11 and 12: their mastery records and the narrator outcome of 11. */
    private suspend fun studyHistory(): Pair<List<MasteryRecordEntity>, List<NarratorReviewEventEntity>> {
        db.masteryDao().upsertAll(listOf(record(11, 12.5f, 3.25f), record(12)))
        val chain = db.hadithDao().getHadithWithSanad(11)!!.sanad.map { it.narrator.id }
        db.reviewHistoryDao().insertAll(chain.mapIndexed { i, id -> NarratorReviewEventEntity(hadithId = 11, narratorId = id, reviewedAt = 5_000L + i, succeeded = i != 1) })
        return db.masteryDao().getAll() to db.reviewHistoryDao().getAll()
    }

    private fun rowCount(table: String): Int = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun aFreshInstallStartsWithNoCollections() = runBlocking {
        assertTrue(collections.observeAll().first().isEmpty())
        assertEquals(42, rowCount("hadith"))
    }

    @Test
    fun aCollectionIsCreatedWithItsFirstHadithInOneStep() = runBlocking {
        val id = collections.create("Friday sermon", accent = 2, createdAt = 100, firstHadithId = 11)

        val summary = collections.observeAll().first().single()
        assertEquals(id, summary.id)
        assertEquals("Friday sermon", summary.title)
        assertEquals(2, summary.accent)
        assertEquals(1, summary.hadithCount)
        assertEquals(listOf(11L), collections.getHadithIds(id))
    }

    @Test
    fun aCollectionCreatedWithAHadithThatDoesNotExistIsNotLeftBehind() = runBlocking {
        val failed = runCatching { collections.create("Ghost", accent = 0, createdAt = 100, firstHadithId = 987_654) }

        assertTrue(failed.isFailure)
        assertTrue("no half-made collection", collections.observeAll().first().isEmpty())
    }

    @Test
    fun theHadithsOfACollectionComeInTheOrderTheyWereAdded() = runBlocking {
        val id = collections.create("Order", 0, createdAt = 1)
        collections.add(id, 13, addedAt = 30)
        collections.add(id, 11, addedAt = 10)
        collections.add(id, 12, addedAt = 20)

        assertEquals(listOf(11L, 12L, 13L), collections.getHadithIds(id))
        assertEquals(listOf(11L, 12L, 13L), collections.observeHadiths(id).first().map { it.id })
    }

    @Test
    fun addingAHadithTwiceKeepsOneReferenceAndTheFirstDate() = runBlocking {
        val id = collections.create("Twice", 0, createdAt = 1)
        collections.add(id, 15, addedAt = 10)
        collections.add(id, 16, addedAt = 15)
        collections.add(id, 15, addedAt = 99)

        assertEquals(listOf(15L, 16L), collections.getHadithIds(id))
        assertEquals(2, collections.observeAll().first().single().hadithCount)
    }

    @Test
    fun removingAHadithTakesItOutOfThatCollectionOnly() = runBlocking {
        val a = collections.create("A", 0, createdAt = 1, firstHadithId = 11)
        val b = collections.create("B", 0, createdAt = 2, firstHadithId = 11)

        collections.remove(a, 11)

        assertTrue(collections.getHadithIds(a).isEmpty())
        assertEquals(listOf(11L), collections.getHadithIds(b))
        assertEquals(42, rowCount("hadith"))
    }

    @Test
    fun summariesListTheNewestFirstAndCountAnEmptyCollectionAsZero() = runBlocking {
        collections.create("Old", 0, createdAt = 10, firstHadithId = 11)
        collections.create("New", 0, createdAt = 20)

        assertEquals(listOf("New" to 0, "Old" to 1), collections.observeAll().first().map { it.title to it.hadithCount })
    }

    @Test
    fun aHadithKnowsWhichCollectionsItIsIn() = runBlocking {
        val a = collections.create("A", 0, 1, firstHadithId = 14)
        val b = collections.create("B", 0, 2, firstHadithId = 14)
        collections.create("C", 0, 3, firstHadithId = 15)

        assertEquals(setOf(a, b), collections.observeCollectionIdsOf(14).first())
        assertEquals(setOf(14L, 15L), collections.observeCollectedHadithIds().first())
        assertTrue(collections.observeCollectionIdsOf(16).first().isEmpty())
    }

    @Test
    fun aNameIsTakenWhateverItsCaseExceptByTheCollectionThatHasIt() = runBlocking {
        val id = collections.create("Adhkar", 0, 1)

        assertTrue(collections.isTitleTaken("adhkar"))
        assertTrue(collections.isTitleTaken("ADHKAR"))
        assertFalse("it may be saved again under its own name", collections.isTitleTaken("Adhkar", exceptId = id))
        assertFalse(collections.isTitleTaken("Other"))
    }

    @Test
    fun updatingACollectionChangesItsNameAndAccentOnly() = runBlocking {
        val id = collections.create("Before", 0, createdAt = 7, firstHadithId = 12)

        collections.update(id, "After", 4)

        val summary = collections.observeAll().first().single()
        assertEquals(Triple("After", 4, 7L), Triple(summary.title, summary.accent, summary.createdAt))
        assertEquals(listOf(12L), collections.getHadithIds(id))
    }

    // -- Cascade isolation: deleting a collection never reaches the library or the study history ----------------------

    @Test
    fun deletingACollectionRemovesItsReferencesAndNoHadith() = runBlocking {
        val doomed = collections.create("Doomed", 0, 1)
        listOf(11L, 12L, 13L).forEachIndexed { i, id -> collections.add(doomed, id, addedAt = i.toLong()) }
        val hadithsBefore = db.hadithDao().getAllWithSanad()
        assertEquals(3, rowCount("hadith_collection_cross_ref"))

        collections.delete(doomed)

        assertEquals("its references are gone", 0, rowCount("hadith_collection_cross_ref"))
        assertEquals(0, rowCount("collections"))
        assertEquals("every hadith, with its chain, is as it was", hadithsBefore, db.hadithDao().getAllWithSanad())
        assertEquals(42, rowCount("hadith"))
    }

    @Test
    fun deletingACollectionLeavesTheStudyHistoryOfItsHadithsAsItWas() = runBlocking {
        val (masteryBefore, eventsBefore) = studyHistory()
        val doomed = collections.create("Doomed", 0, 1, firstHadithId = 11)
        collections.add(doomed, 12, addedAt = 2)

        collections.delete(doomed)

        assertEquals(masteryBefore, db.masteryDao().getAll())
        assertEquals(eventsBefore, db.reviewHistoryDao().getAll())
        assertEquals(2, db.masteryDao().getAll().size)
    }

    @Test
    fun deletingOneCollectionLeavesAnotherThatHoldsTheSameHadiths() = runBlocking {
        val kept = collections.create("Kept", 0, 1)
        val doomed = collections.create("Doomed", 0, 2)
        listOf(11L, 12L).forEach { collections.add(kept, it, 10); collections.add(doomed, it, 10) }

        collections.delete(doomed)

        assertEquals(listOf(11L, 12L), collections.getHadithIds(kept))
        assertEquals(listOf("Kept" to 2), collections.observeAll().first().map { it.title to it.hadithCount })
    }

    @Test
    fun deletingAnEmptyOrMissingCollectionIsHarmless() = runBlocking {
        val empty = collections.create("Empty", 0, 1)

        collections.delete(empty)
        collections.delete(empty)
        collections.delete(424_242)

        assertTrue(collections.observeAll().first().isEmpty())
        assertEquals(42, rowCount("hadith"))
    }

    // -- Collections are not progress: nothing that touches progress touches them -----------------------------------

    @Test
    fun resettingProgressKeepsTheCollections() = runBlocking {
        studyHistory()
        val id = collections.create("Mine", 0, 1, firstHadithId = 11)

        RoomBackupRepository(db).clearUserData()

        assertTrue(db.masteryDao().getAll().isEmpty())
        assertEquals(listOf(11L), collections.getHadithIds(id))
    }

    @Test
    fun restoringABackupKeepsTheCollections() = runBlocking {
        val (mastery, events) = studyHistory()
        val id = collections.create("Mine", 0, 1)
        collections.add(id, 11, 5)
        collections.add(id, 19, 6)

        RoomBackupRepository(db).replaceUserData(UserData(mastery.take(1), events.take(1)))

        assertEquals(1, db.masteryDao().getAll().size)
        assertEquals(listOf(11L, 19L), collections.getHadithIds(id))
    }

    @Test
    fun aHadithLeavingTheLibraryTakesItsReferencesWithItAndNothingElse() = runBlocking {
        val id = collections.create("Mine", 0, 1)
        collections.add(id, 11, 5)
        collections.add(id, 12, 6)

        db.openHelper.writableDatabase.execSQL("DELETE FROM hadith WHERE id = 12")

        assertEquals(listOf(11L), collections.getHadithIds(id))
        assertEquals(1, collections.observeAll().first().single().hadithCount)
    }

    @Test
    fun aCatalogUpdateOnOpenKeepsTheCollections() = runBlocking {
        val name = "catalog-sync.db"
        val first = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()
        val id = RoomCollectionRepository(first).create("Mine", 3, 1, firstHadithId = 11)
        RoomCollectionRepository(first).add(id, 12, 2)
        first.openHelper.writableDatabase.execSQL("UPDATE catalog_state SET version = 1")
        first.close()

        // Opening again finds an older catalog than the app bundles and syncs it.
        val reopened = MutqanDatabase.builder(context, name).allowMainThreadQueries().build()
        val repo = RoomCollectionRepository(reopened)

        assertEquals(listOf(11L, 12L), repo.getHadithIds(id))
        assertEquals("Mine", repo.observeAll().first().single().title)
        reopened.close()
        context.deleteDatabase(name)
        Unit
    }
}
