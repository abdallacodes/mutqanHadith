package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.catalog.RoomCatalogStore
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.CollectionEntity
import com.mutqan.hadith.data.entity.HadithCollectionCrossRef
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.data.text.ArabicNormalizer
import com.mutqan.hadith.domain.catalog.CatalogDelta
import com.mutqan.hadith.domain.catalog.CloudGroup
import com.mutqan.hadith.domain.catalog.CloudHadith
import com.mutqan.hadith.domain.catalog.CloudLink
import com.mutqan.hadith.domain.catalog.CloudNarrator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The cloud catalog applied to the library: edits land in place and the learner's progress stays where it is. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudCatalogDatabaseTest {

    private lateinit var db: MutqanDatabase
    private lateinit var store: RoomCatalogStore
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val seed = SeedLoader.load(context)

    /** A hadith with a long chain (the first narrator of its chain is not its Companion), and one with a different chain. */
    private val first = seed.hadiths.first()
    private val second = seed.hadiths[1]

    @Before
    fun setUp() {
        db = MutqanDatabase.builder(context, name = null).allowMainThreadQueries().build()
        store = RoomCatalogStore(db)
    }

    @After
    fun tearDown() = db.close()

    private fun chainOf(hadithId: Long) =
        seed.edges.filter { it.hadithId == hadithId }.sortedBy { it.orderIndex }.map { CloudLink(it.narratorId, it.transmissionTerm) }

    private fun cloud(h: com.mutqan.hadith.data.entity.HadithEntity, at: Long = 1_000, edit: (CloudHadith) -> CloudHadith = { it }) = edit(
        CloudHadith(
            h.id, h.matnWithTashkeel, h.sourceBook, h.hadithNumber, h.chapter, h.grade, h.collector, h.takhrij, h.sahabi,
            h.groupId, h.position, h.sanadSource, chainOf(h.id), at, deleted = false,
        ),
    )

    private suspend fun study(hadithId: Long) {
        db.masteryDao().upsert(MasteryRecordEntity(hadithId, 4f, 1.5f, 100L, 200L, 300L, 3))
        val narratorId = chainOf(hadithId).first().narratorId
        db.reviewHistoryDao().insertAll(listOf(NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = 400L, succeeded = true)))
        val collection = db.collectionDao().insert(CollectionEntity(title = "Mine", createdAt = 1))
        db.collectionDao().addReference(HadithCollectionCrossRef(collection, hadithId, 2))
    }

    private suspend fun progressOf(hadithId: Long) = Triple(
        db.masteryDao().getByHadithId(hadithId),
        db.reviewHistoryDao().getAll().count { it.hadithId == hadithId },
        db.collectionDao().getHadithIds(1).count { it == hadithId },
    )

    // -- Edits -----------------------------------------------------------------------------------

    @Test
    fun anEditedHadithIsUpdatedInPlaceAndItsProgressStays() = runBlocking {
        study(first.id)
        val before = progressOf(first.id)

        val result = store.apply(CatalogDelta(hadiths = listOf(cloud(first, at = 5_000) { it.copy(grade = "صحيح", matnWithTashkeel = "نَصٌّ جَدِيدٌ") })))

        assertEquals(1, result.applied)
        val stored = db.hadithDao().getHadithWithSanad(first.id)!!.hadith
        assertEquals("صحيح", stored.grade)
        assertEquals("نَصٌّ جَدِيدٌ", stored.matnWithTashkeel)
        assertEquals(ArabicNormalizer.normalize("نَصٌّ جَدِيدٌ"), stored.matnClean)
        assertEquals(5_000L, stored.catalogUpdatedAt)
        assertEquals(before, progressOf(first.id))
        assertEquals(seed.hadiths.size, db.hadithDao().count())
    }

    @Test
    fun aNewHadithWithItsChainIsAdded() = runBlocking {
        val chain = chainOf(first.id)
        val added = cloud(first, at = 7_000) { it.copy(id = 10_000, hadithNumber = 43, groupId = null, sanad = chain) }

        store.apply(CatalogDelta(hadiths = listOf(added)))

        val stored = db.hadithDao().getHadithWithSanad(10_000)!!
        assertEquals(43, stored.hadith.hadithNumber)
        assertEquals(chain.map { it.narratorId to it.transmissionTerm }, stored.sanad.map { it.narrator.id to it.transmissionTerm })
        assertEquals(seed.hadiths.size + 1, db.hadithDao().count())
    }

    @Test
    fun aChangedChainReplacesTheLinksAndKeepsWhatWasRecalled() = runBlocking {
        study(first.id)
        val newChain = chainOf(first.id).reversed().take(2)

        store.apply(CatalogDelta(hadiths = listOf(cloud(first, at = 5_000) { it.copy(sanad = newChain) })))

        val stored = db.hadithDao().getHadithWithSanad(first.id)!!
        assertEquals(newChain.map { it.narratorId }, stored.sanad.map { it.narrator.id })
        assertEquals(1, db.reviewHistoryDao().getAll().count { it.hadithId == first.id })
        assertEquals(3, db.masteryDao().getByHadithId(first.id)!!.repetitionCount)
    }

    @Test
    fun aNewNarratorAndTheHadithThatUsesItArriveTogether() = runBlocking {
        val narrator = CloudNarrator(10_000, "راوٍ جديد", "راوٍ جديد بن فلان", "ثقة", isPivot = false, updatedAt = 6_000, deleted = false)
        val hadith = cloud(first, at = 6_000) { it.copy(sanad = listOf(CloudLink(10_000, "حدثنا")) + it.sanad) }

        store.apply(CatalogDelta(hadiths = listOf(hadith), narrators = listOf(narrator)))

        val stored = db.hadithDao().getHadithWithSanad(first.id)!!
        assertEquals(10_000L, stored.sanad.first().narrator.id)
        assertEquals(6_000L, db.narratorDao().getById(10_000)!!.catalogUpdatedAt)
    }

    @Test
    fun aNarratorSaidToBeTheSamePersonAsAnotherKeepsThePointerUntilTheAdminTakesItBack() = runBlocking {
        val target = seed.narrators.first().id
        fun duplicate(sameAs: Long?, at: Long) = CloudNarrator(10_000, "راوٍ مكرر", "راوٍ مكرر", "ثقة", false, at, false, sameAs)

        store.apply(CatalogDelta(narrators = listOf(duplicate(target, 7_000))))
        assertEquals(target, db.narratorDao().getById(10_000)!!.sameAsId)
        assertNull("the row it points at is untouched", db.narratorDao().getById(target)!!.sameAsId)

        store.apply(CatalogDelta(narrators = listOf(duplicate(null, 8_000))))
        assertNull(db.narratorDao().getById(10_000)!!.sameAsId)
    }

    @Test
    fun aNarratorPointingAtItselfIsStoredAsNobodysDuplicate() = runBlocking {
        store.apply(CatalogDelta(narrators = listOf(CloudNarrator(10_000, "راوٍ", "راوٍ", "ثقة", false, 7_000, false, sameAs = 10_000))))

        assertNull(db.narratorDao().getById(10_000)!!.sameAsId)
    }

    @Test
    fun aHadithWhoseChainNamesAnUnknownNarratorIsSkippedAndTheRestIsApplied() = runBlocking {
        val bad = cloud(second, at = 3_000) { it.copy(grade = "bad", sanad = listOf(CloudLink(99_999, "عن"))) }
        val good = cloud(first, at = 4_000) { it.copy(grade = "good") }

        val result = store.apply(CatalogDelta(hadiths = listOf(bad, good)))

        assertEquals(1, result.applied)
        assertEquals(1, result.skipped)
        assertEquals(3_000L, result.earliestSkipped)
        assertEquals(second.grade, db.hadithDao().getHadithWithSanad(second.id)!!.hadith.grade)
        assertEquals("good", db.hadithDao().getHadithWithSanad(first.id)!!.hadith.grade)
    }

    @Test
    fun aHadithWithNoTextOrNoChainIsSkipped() = runBlocking {
        val noText = cloud(first) { it.copy(matnWithTashkeel = " ") }
        val noChain = cloud(second) { it.copy(sanad = emptyList()) }

        val result = store.apply(CatalogDelta(hadiths = listOf(noText, noChain)))

        assertEquals(0, result.applied)
        assertEquals(2, result.skipped)
    }

    // -- Deletions -------------------------------------------------------------------------------

    @Test
    fun aDeletedHadithGoesWithItsProgressAndNothingElseIsTouched() = runBlocking {
        study(first.id)
        db.masteryDao().upsert(MasteryRecordEntity(second.id, 2f, 1f, 1L, 1L, 5L, 1))
        val kept = progressOf(second.id)

        val result = store.apply(CatalogDelta(hadiths = listOf(cloud(first, at = 8_000) { it.copy(deleted = true) })))

        assertEquals(1, result.applied)
        assertNull(db.hadithDao().getHadithWithSanad(first.id))
        assertNull(db.masteryDao().getByHadithId(first.id))
        assertTrue(db.reviewHistoryDao().getAll().none { it.hadithId == first.id })
        assertEquals(seed.hadiths.size - 1, db.hadithDao().count())
        assertEquals(kept, progressOf(second.id))
    }

    @Test
    fun deletingAHadithTheLibraryNeverHadChangesNothing() = runBlocking {
        val result = store.apply(CatalogDelta(hadiths = listOf(cloud(first) { it.copy(id = 777, deleted = true) })))

        assertEquals(0, result.applied)
        assertEquals(seed.hadiths.size, db.hadithDao().count())
    }

    @Test
    fun aDeletedNarratorGoesOnlyWhenNothingUsesIt() = runBlocking {
        val unused = CloudNarrator(10_001, "لا أحد", "لا أحد", "ثقة", false, 1_000, deleted = false)
        store.apply(CatalogDelta(narrators = listOf(unused)))
        val inUse = chainOf(first.id).first().narratorId

        store.apply(
            CatalogDelta(
                narrators = listOf(
                    unused.copy(deleted = true, updatedAt = 2_000),
                    CloudNarrator(inUse, "x", "x", "x", false, 2_000, deleted = true),
                ),
            ),
        )

        assertNull(db.narratorDao().getById(10_001))
        assertTrue(db.narratorDao().getById(inUse) != null)
    }

    // -- Groups ----------------------------------------------------------------------------------

    @Test
    fun aGroupIsAddedAndRenamedInPlace() = runBlocking {
        val book = seed.groups.single()
        store.apply(
            CatalogDelta(
                groups = listOf(
                    CloudGroup(book.id, null, "عنوان جديد", "New title", 0, 3_000, false),
                    CloudGroup(10_000, book.id, "قسم", "Section", 1, 3_000, false),
                ),
            ),
        )

        val groups = db.hadithDao().observeGroups().first()
        assertEquals(listOf("عنوان جديد", "قسم"), groups.map { it.title })
        assertEquals(book.id, groups.last().parentId)
        assertEquals(seed.hadiths.size, db.hadithDao().count())
    }

    @Test
    fun aDeletedGroupLeavesItsHadithsUnfiledButPresent() = runBlocking {
        val book = seed.groups.single()

        store.apply(CatalogDelta(groups = listOf(CloudGroup(book.id, null, book.title, book.titleEn, 0, 3_000, deleted = true))))

        assertTrue(db.hadithDao().observeGroups().first().isEmpty())
        assertEquals(seed.hadiths.size, db.hadithDao().count())
        assertTrue(db.hadithDao().getAllWithSanad().all { it.hadith.groupId == null })
    }

    // -- The bundled catalog after the cloud has written ------------------------------------------

    @Test
    fun aBundledCatalogUpdateLeavesWhatTheCloudWroteAloneAndRefreshesTheRest() = runBlocking {
        store.apply(CatalogDelta(hadiths = listOf(cloud(first, at = 5_000) { it.copy(grade = "edited in the cloud") })))
        val raw = db.openHelper.writableDatabase
        raw.execSQL("UPDATE hadith SET grade = 'stale' WHERE id = ${second.id}")

        raw.beginTransaction()
        try {
            SeedLoader.sync(raw, seed, appliedVersion = SeedLoader.CATALOG_VERSION - 1)
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }

        assertEquals("edited in the cloud", db.hadithDao().getHadithWithSanad(first.id)!!.hadith.grade)
        assertEquals(second.grade, db.hadithDao().getHadithWithSanad(second.id)!!.hadith.grade)
    }

    @Test
    fun aBundledCatalogUpdateDoesNotReplaceAChainTheCloudChanged() = runBlocking {
        study(first.id)
        val newChain = chainOf(first.id).take(1)
        store.apply(CatalogDelta(hadiths = listOf(cloud(first, at = 5_000) { it.copy(sanad = newChain) })))
        val raw = db.openHelper.writableDatabase

        raw.beginTransaction()
        try {
            SeedLoader.sync(raw, seed, appliedVersion = SeedLoader.CATALOG_VERSION - 1)
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }

        assertEquals(newChain.map { it.narratorId }, db.hadithDao().getHadithWithSanad(first.id)!!.sanad.map { it.narrator.id })
        assertEquals(1, db.reviewHistoryDao().getAll().count { it.hadithId == first.id })
    }

    @Test
    fun aBundledCatalogUpdateKeepsTheGroupsAndNarratorsTheCloudAdded() = runBlocking {
        store.apply(
            CatalogDelta(
                groups = listOf(CloudGroup(10_000, null, "كتاب جديد", "New book", 5, 3_000, false)),
                narrators = listOf(CloudNarrator(10_000, "راوٍ", "راوٍ", "ثقة", false, 3_000, false)),
            ),
        )
        val raw = db.openHelper.writableDatabase

        raw.beginTransaction()
        try {
            SeedLoader.sync(raw, seed, appliedVersion = SeedLoader.CATALOG_VERSION - 1)
            raw.setTransactionSuccessful()
        } finally {
            raw.endTransaction()
        }

        assertEquals(setOf(seed.groups.single().id, 10_000L), db.hadithDao().observeGroups().first().map { it.id }.toSet())
        assertTrue(db.narratorDao().getById(10_000) != null)
    }
}
