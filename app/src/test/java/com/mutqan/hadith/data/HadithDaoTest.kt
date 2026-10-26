package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.dao.HadithDao
import com.mutqan.hadith.data.dao.MasteryDao
import com.mutqan.hadith.data.dao.NarratorDao
import com.mutqan.hadith.data.dao.search
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.data.text.ArabicNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs against the real `seed_hadiths.json` loaded through the production prepopulation callback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HadithDaoTest {

    private lateinit var db: MutqanDatabase
    private lateinit var hadithDao: HadithDao
    private lateinit var narratorDao: NarratorDao
    private lateinit var masteryDao: MasteryDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = MutqanDatabase.builder(context, name = null).allowMainThreadQueries().build()
        hadithDao = db.hadithDao()
        narratorDao = db.narratorDao()
        masteryDao = db.masteryDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seed_loadsEveryHadithWithItsSanad() = runBlocking {
        val all = hadithDao.getAllWithSanad()
        assertEquals(42, all.size)
        // The Forty Hadith are anchored to their Companion alone.
        assertTrue(all.all { it.hadith.level == 1 })
        assertTrue(all.all { it.sanad.size == 1 })
    }

    @Test
    fun seed_separatesSourceBookFromHadithNumber() = runBlocking {
        val all = hadithDao.getAllWithSanad().map { it.hadith }
        assertTrue(all.all { it.sourceBook == "الأربعون النووية" })
        assertEquals((1..42).toSet(), all.map { it.hadithNumber }.toSet())
        // The hadith of intentions is the first of the forty.
        val intentions = hadithDao.search("إنما الأعمال بالنيات").single().hadith
        assertEquals(1, intentions.hadithNumber)
    }

    @Test
    fun sanad_isTheCompanionAlone() = runBlocking {
        val chain = hadithDao.search("إنما الأعمال بالنيات").single().sanad
        assertEquals(listOf("أمير المؤمنين أبي حفص عمر بن الخطاب"), chain.map { it.narrator.primaryName })
        assertEquals(listOf(0), chain.map { it.orderIndex })
        assertEquals(listOf("عن"), chain.map { it.transmissionTerm })
        assertEquals("صحابي", chain.last().narrator.tier)
    }

    @Test
    fun sanad_orderIsIndependentOfInsertionOrder() = runBlocking {
        val hadith = HadithEntity(100, "نص", "نص", "كتاب", 100, "باب", "صحيح", "جامع", takhrij = "رواه مسلم", sahabi = "عمر بن الخطاب", level = 2)
        // Narrators 12, 16 and 29 exist from the seed; insert the edges back to front.
        hadithDao.insertWithSanad(
            hadith,
            listOf(
                SanadEdgeEntity(hadithId = 100, narratorId = 29, orderIndex = 2, transmissionTerm = "عن"),
                SanadEdgeEntity(hadithId = 100, narratorId = 12, orderIndex = 0, transmissionTerm = "حدثنا"),
                SanadEdgeEntity(hadithId = 100, narratorId = 16, orderIndex = 1, transmissionTerm = "أخبرنا"),
            ),
        )
        val chain = hadithDao.getHadithWithSanad(100)!!.sanad
        assertEquals(listOf(12L, 16L, 29L), chain.map { it.narrator.id })
        assertEquals(listOf("حدثنا", "أخبرنا", "عن"), chain.map { it.transmissionTerm })
    }

    @Test
    fun narrator_returnsAllLinkedHadiths() = runBlocking {
        suspend fun hadithsOf(name: String) = hadithDao.getHadithsByNarrator(narratorDao.getAll().single { it.primaryName == name }.id)

        // «أبو هريرة» is the Companion of eight of the Forty Hadith, returned in the order of the book.
        val abuHurayra = hadithsOf("أبو هريرة")
        assertEquals(listOf(10, 12, 15, 16, 26, 35, 36, 38), abuHurayra.map { it.hadith.hadithNumber })
        assertTrue(abuHurayra.all { h -> h.sanad.any { it.narrator.primaryName == "أبو هريرة" } })

        // Narrators are kept apart by the name the source gives: «عمر» is not «أمير المؤمنين أبي حفص عمر بن الخطاب».
        assertTrue(hadithsOf("عمر").none { it in hadithsOf("أمير المؤمنين أبي حفص عمر بن الخطاب") })
    }

    @Test
    fun search_withoutTashkeel_findsHadith() = runBlocking {
        // «الحلال بين» opens Forty Hadith no. 6.
        assertEquals(listOf(6), hadithDao.search("الحلال بين").map { it.hadith.hadithNumber })
        hadithDao.search("الحلال بين").forEach {
            assertTrue(it.hadith.matnClean.contains("الحلال بين"))
            assertTrue(it.hadith.matnWithTashkeel.any { c -> c in 'ً'..'ْ' })
        }

        // Orthographic variants: hamza-on-alif and ta marbuta vs ha.
        assertEquals(listOf(10), hadithDao.search("ان الله طيب لا يقبل الا طيبا").map { it.hadith.hadithNumber })
        assertEquals(listOf(3), hadithDao.search("بني الاسلام على خمس").map { it.hadith.hadithNumber })
    }

    @Test
    fun search_withTashkeel_matchesAndWildcardsAreLiteral() = runBlocking {
        assertEquals(1, hadithDao.search("بُنِيَ الإِسْلاَمُ عَلَى خَمْسٍ").size)
        assertTrue(hadithDao.search("100%").isEmpty())
        assertTrue(hadithDao.search("_").isEmpty())
        assertTrue(hadithDao.search("   ").isEmpty())
    }

    @Test
    fun mastery_upsertAndDueQuery() = runBlocking {
        val record = MasteryRecordEntity(11, 2.5f, 1.0f, matnLastReviewedAt = 1_000, sanadLastReviewedAt = 1_000, nextReviewDue = 5_000, repetitionCount = 1)
        masteryDao.upsert(record)
        assertEquals(record, masteryDao.getByHadithId(11))

        // Matn and Sanad stabilities are stored independently.
        val updated = record.copy(sanadStability = 0.4f, repetitionCount = 2, nextReviewDue = 9_000)
        masteryDao.upsert(updated)
        assertEquals(updated, masteryDao.getByHadithId(11))
        assertEquals(2.5f, masteryDao.getByHadithId(11)!!.matnStability)

        assertTrue(masteryDao.getDue(now = 8_999).isEmpty())
        assertEquals(listOf(updated), masteryDao.getDue(now = 9_000))

        masteryDao.delete(11)
        assertNull(masteryDao.getByHadithId(11))
    }

    @Test
    fun deletingHadith_cascadesToSanadAndMastery() = runBlocking {
        masteryDao.upsert(MasteryRecordEntity(13, 1f, 1f, 0, 0, 0, 0))
        db.openHelper.writableDatabase.execSQL("DELETE FROM hadith WHERE id = 13")
        assertNull(hadithDao.getHadithWithSanad(13))
        assertNull(masteryDao.getByHadithId(13))
        val orphans = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM sanad_edge WHERE hadithId = 13")
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, orphans)
    }

    @Test
    fun seedParser_computesCleanMatnFromTashkeel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val seed = SeedLoader.load(context)
        assertEquals(42, seed.hadiths.size)
        seed.hadiths.forEach {
            assertEquals(ArabicNormalizer.normalize(it.matnWithTashkeel), it.matnClean)
            assertNotNull(it.matnClean)
            assertTrue(it.matnClean.none { c -> c in 'ً'..'ْ' })
        }
        val narratorIds = seed.narrators.map { it.id }.toSet()
        assertTrue(seed.edges.all { it.narratorId in narratorIds })
    }
}
