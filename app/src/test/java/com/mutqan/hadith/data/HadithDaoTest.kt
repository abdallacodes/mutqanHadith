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
    fun seed_loadsTenHadithsWithSanad() = runBlocking {
        val all = hadithDao.getAllWithSanad()
        assertEquals(10, all.size)
        assertTrue(all.all { it.sanad.size >= 4 })
        assertTrue(narratorDao.getPivots().size >= 2)
    }

    @Test
    fun seed_separatesSourceBookFromHadithNumber() = runBlocking {
        val all = hadithDao.getAllWithSanad().map { it.hadith }
        assertTrue(all.all { it.sourceBook == "صحيح البخاري" })
        assertEquals(
            setOf(1, 6, 8, 13, 50, 52, 54, 135, 611, 877),
            all.map { it.hadithNumber }.toSet(),
        )
        // The Hadith about the Prophet's generosity is Bukhari no. 6.
        val generosity = hadithDao.search("أجود الناس").single().hadith
        assertEquals(6, generosity.hadithNumber)
    }

    @Test
    fun sanad_isReconstructedInChronologicalOrder() = runBlocking {
        // Bukhari 1: al-Humaydi <- Sufyan <- Yahya al-Ansari <- Muhammad b. Ibrahim <- Alqama <- Umar.
        val bukhari1 = hadithDao.search("إنما الأعمال بالنيات").first { it.hadith.hadithNumber == 1 }
        val chain = bukhari1.sanad
        assertEquals(
            listOf(
                "الحميدي",
                "سفيان بن عيينة",
                "يحيى بن سعيد الأنصاري",
                "محمد بن إبراهيم التيمي",
                "علقمة بن وقاص",
                "عمر بن الخطاب",
            ),
            chain.map { it.narrator.primaryName },
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5), chain.map { it.orderIndex })
        assertEquals(
            listOf("حدثنا", "حدثنا", "حدثنا", "أخبرني", "سمع", "سمعت"),
            chain.map { it.transmissionTerm },
        )
        assertEquals("صحابي", chain.last().narrator.tier)
    }

    @Test
    fun sanad_orderIsIndependentOfInsertionOrder() = runBlocking {
        val hadith = HadithEntity(100, "نص", "نص", "كتاب", 100, "باب", "صحيح", "جامع")
        // Narrators 1..3 exist from the seed; insert the edges back to front.
        hadithDao.insertWithSanad(
            hadith,
            listOf(
                SanadEdgeEntity(hadithId = 100, narratorId = 3, orderIndex = 2, transmissionTerm = "عن"),
                SanadEdgeEntity(hadithId = 100, narratorId = 1, orderIndex = 0, transmissionTerm = "حدثنا"),
                SanadEdgeEntity(hadithId = 100, narratorId = 2, orderIndex = 1, transmissionTerm = "أخبرنا"),
            ),
        )
        val chain = hadithDao.getHadithWithSanad(100)!!.sanad
        assertEquals(listOf(1L, 2L, 3L), chain.map { it.narrator.id })
        assertEquals(listOf("حدثنا", "أخبرنا", "عن"), chain.map { it.transmissionTerm })
    }

    @Test
    fun pivot_returnsAllLinkedHadiths() = runBlocking {
        suspend fun nid(name: String) = narratorDao.getPivots().first { it.primaryName == name }.id

        val yahya = hadithDao.getHadithsByNarrator(nid("يحيى بن سعيد الأنصاري"))
        assertEquals(2, yahya.size)
        assertTrue(yahya.all { h -> h.sanad.any { it.narrator.primaryName == "يحيى بن سعيد الأنصاري" } })

        val zuhri = hadithDao.getHadithsByNarrator(nid("ابن شهاب الزهري"))
        assertEquals(2, zuhri.size)

        val malik = hadithDao.getHadithsByNarrator(nid("مالك بن أنس"))
        assertEquals(3, malik.size)

        // The two Yahyas must not be conflated: al-Qattan is a different narrator.
        val qattan = narratorDao.observeAll().first().first { it.primaryName == "يحيى بن سعيد القطان" }
        val viaQattan = hadithDao.getHadithsByNarrator(qattan.id)
        assertEquals(1, viaQattan.size)
        assertTrue(viaQattan.none { it in yahya })
    }

    @Test
    fun search_withoutTashkeel_findsHadith() = runBlocking {
        val results = hadithDao.search("الحلال بين والحرام بين")
        assertEquals(1, results.size)
        val hadith = results.single().hadith
        assertTrue(hadith.matnClean.startsWith("الحلال بين"))
        assertTrue(hadith.matnWithTashkeel.any { it in 'ً'..'ْ' })

        // Orthographic variants: hamza-on-alif and ta marbuta vs ha.
        assertEquals(1, hadithDao.search("اذا سمعتم النداء").size)
        assertEquals(1, hadithDao.search("بني الاسلام على خمس").size)
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
        val record = MasteryRecordEntity(1, 2.5f, 1.0f, lastReviewedAt = 1_000, nextReviewDue = 5_000, repetitionCount = 1)
        masteryDao.upsert(record)
        assertEquals(record, masteryDao.getByHadithId(1))

        // Matn and Sanad stabilities are stored independently.
        val updated = record.copy(sanadStability = 0.4f, repetitionCount = 2, nextReviewDue = 9_000)
        masteryDao.upsert(updated)
        assertEquals(updated, masteryDao.getByHadithId(1))
        assertEquals(2.5f, masteryDao.getByHadithId(1)!!.matnStability)

        assertTrue(masteryDao.getDue(now = 8_999).isEmpty())
        assertEquals(listOf(updated), masteryDao.getDue(now = 9_000))

        masteryDao.delete(1)
        assertNull(masteryDao.getByHadithId(1))
    }

    @Test
    fun deletingHadith_cascadesToSanadAndMastery() = runBlocking {
        masteryDao.upsert(MasteryRecordEntity(2, 1f, 1f, 0, 0, 0))
        db.openHelper.writableDatabase.execSQL("DELETE FROM hadith WHERE id = 2")
        assertNull(hadithDao.getHadithWithSanad(2))
        assertNull(masteryDao.getByHadithId(2))
        val orphans = db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM sanad_edge WHERE hadithId = 2")
            .use { it.moveToFirst(); it.getInt(0) }
        assertEquals(0, orphans)
    }

    @Test
    fun seedParser_computesCleanMatnFromTashkeel() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val seed = SeedLoader.load(context)
        assertEquals(10, seed.hadiths.size)
        seed.hadiths.forEach {
            assertEquals(ArabicNormalizer.normalize(it.matnWithTashkeel), it.matnClean)
            assertNotNull(it.matnClean)
            assertTrue(it.matnClean.none { c -> c in 'ً'..'ْ' })
        }
        val narratorIds = seed.narrators.map { it.id }.toSet()
        assertTrue(seed.edges.all { it.narratorId in narratorIds })
    }
}
