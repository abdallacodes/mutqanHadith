package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.dao.searchInLevel
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.repository.RoomHadithRepository
import com.mutqan.hadith.data.repository.RoomReviewRepository
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.domain.model.HadithLevel
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetLibraryByLevelUseCase
import com.mutqan.hadith.domain.usecase.HadithProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The curriculum levels over the real seed, through the production database and prepopulation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LevelsCatalogTest {

    private lateinit var db: MutqanDatabase
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = MutqanDatabase.builder(context, name = null).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val dao get() = db.hadithDao()

    // -- The seed ---------------------------------------------------------------------------

    @Test
    fun everySeededHadithHasALevelATakhrijAndItsSahabi() {
        val seed = SeedLoader.load(context)
        val narrators = seed.narrators.associateBy { it.id }
        seed.hadiths.forEach { hadith ->
            val chain = seed.edges.filter { it.hadithId == hadith.id }.sortedBy { it.orderIndex }.map { narrators.getValue(it.narratorId) }
            assertTrue("hadith ${hadith.id} has a known level", HadithLevel.of(hadith.level) != null)
            assertTrue("hadith ${hadith.id} has a takhrij", hadith.takhrij.isNotBlank())
            assertEquals("hadith ${hadith.id}: the sahabi is the end of the chain", chain.last().primaryName, hadith.sahabi)
            assertEquals("hadith ${hadith.id}: the end of the chain is a Companion", "صحابي", chain.last().tier)
        }
    }

    @Test
    fun theExistingBukhariHadithsAreLevelThree() = runBlocking {
        val bukhari = dao.getAllWithSanad().map { it.hadith }.filter { it.sourceBook == "صحيح البخاري" }

        assertEquals((1L..10L).toList(), bukhari.map { it.id })
        assertTrue(bukhari.all { it.level == HadithLevel.MUSNAD.value })
        assertTrue(bukhari.all { it.takhrij == "متفق عليه" })
    }

    @Test
    fun theFoundationHadithsAreAnchoredToTheirCompanion() = runBlocking {
        val byNumber = dao.getHadithsByLevel(1).first().associateBy { it.hadithNumber }

        assertEquals(setOf(1, 3, 7), byNumber.keys)
        assertEquals("عمر بن الخطاب" to "متفق عليه", byNumber.getValue(1).let { it.sahabi to it.takhrij })
        assertEquals("عبد الله بن عمر" to "متفق عليه", byNumber.getValue(3).let { it.sahabi to it.takhrij })
        assertEquals("تميم الداري" to "رواه مسلم", byNumber.getValue(7).let { it.sahabi to it.takhrij })
        assertTrue(byNumber.values.all { it.sourceBook == "الأربعون النووية" })
        // «بني الإسلام على خمس» is the same hadith as Bukhari no. 8, told by the same Companion.
        val bukhari8 = dao.getHadithWithSanad(3)!!.hadith
        assertEquals(bukhari8.sahabi, byNumber.getValue(3).sahabi)
    }

    // -- Queries ----------------------------------------------------------------------------

    @Test
    fun getHadithsByLevel_returnsTheLevelInCurriculumOrder() = runBlocking {
        assertEquals(listOf(11L, 12L, 13L), dao.getHadithsByLevel(1).first().map { it.id })
        assertEquals((1L..10L).toList(), dao.getHadithsByLevel(3).first().map { it.id })
        assertTrue(dao.getHadithsByLevel(2).first().isEmpty())
        assertTrue(dao.getHadithsByLevel(99).first().isEmpty())
    }

    @Test
    fun countHadithsByLevel_countsEachLevel() = runBlocking {
        assertEquals(listOf(3, 0, 10), (1..3).map { dao.countHadithsByLevel(it).first() })
    }

    @Test
    fun theLevelQueriesFollowTheLibrary() = runBlocking {
        val sizes = withTimeout(10_000) {
            dao.getHadithsByLevel(2)
                .onEach { if (it.isEmpty()) dao.insertWithSanad(ahkam(100), listOf(SanadEdgeEntity(hadithId = 100, narratorId = 1, orderIndex = 0, transmissionTerm = "عن"))) }
                .take(2)
                .toList()
                .map { it.size }
        }

        assertEquals(listOf(0, 1), sizes)
        assertEquals(1, dao.countHadithsByLevel(2).first())
    }

    private fun ahkam(id: Long) = HadithEntity(
        id, "الْبَيِّنَةُ عَلَى الْمُدَّعِي", "البينه على المدعي", "عمدة الأحكام", 1, "باب", "صحيح", "عبد الغني المقدسي",
        takhrij = "رواه البخاري", sahabi = "عمر بن الخطاب", level = 2,
    )

    @Test
    fun searchInLevel_staysInsideTheLevel() = runBlocking {
        // «بني الإسلام على خمس» is told at level 1 (Forty Hadith no. 3) and at level 3 (Bukhari no. 8).
        assertEquals(listOf(12L), dao.searchInLevel(1, "بني الاسلام على خمس").first().map { it.id })
        assertEquals(listOf(3L), dao.searchInLevel(3, "بني الاسلام على خمس").first().map { it.id })
        assertTrue(dao.searchInLevel(2, "بني الاسلام على خمس").first().isEmpty())
    }

    @Test
    fun searchInLevel_ignoresTashkeelAndOrthographicVariants() = runBlocking {
        assertEquals(listOf(12L), dao.searchInLevel(1, "بُنِيَ الإِسْلاَمُ عَلَى خَمْسٍ").first().map { it.id })
        assertEquals(listOf(13L), dao.searchInLevel(1, "الدين النصيحة").first().map { it.id })
        assertEquals(listOf(6L), dao.searchInLevel(3, "اذا سمعتم النداء").first().map { it.id })
    }

    @Test
    fun searchInLevel_treatsWildcardsAsLiteralTextAndNothingAsNothing() = runBlocking {
        assertTrue(dao.searchInLevel(1, "100%").first().isEmpty())
        assertTrue(dao.searchInLevel(1, "_").first().isEmpty())
        assertTrue(dao.searchInLevel(1, "   ").first().isEmpty())
        assertTrue(dao.searchInLevel(1, "").first().isEmpty())
    }

    @Test
    fun theRepositoryExposesTheLevelQueries() = runBlocking {
        val repository = RoomHadithRepository(db)

        assertEquals(listOf(11L, 12L, 13L), repository.getHadithsByLevel(1).first().map { it.id })
        assertEquals(10, repository.countHadithsByLevel(3).first())
        assertEquals(listOf(13L), repository.searchInLevel(1, "النصيحة").first().map { it.id })
    }

    // -- GetLibraryByLevelUseCase over Room ---------------------------------------------------

    @Test
    fun theLibraryByLevel_listsTheSeededLevels() = runBlocking {
        val levels = GetLibraryByLevelUseCase(RoomHadithRepository(db), RoomReviewRepository(db), DualDecayScheduler()).invoke().first()

        assertEquals(listOf(3, 0, 10), levels.map { it.hadiths.size })
        assertEquals(listOf(3, 0, 10), levels.map { it.totalInLevel })
        assertTrue(levels.flatMap { it.hadiths }.all { it.progress == HadithProgress.NEW })
    }

    @Test
    fun theLibraryByLevel_picksUpANewReview() = runBlocking {
        val useCase = GetLibraryByLevelUseCase(RoomHadithRepository(db), RoomReviewRepository(db), DualDecayScheduler()) { 10_000L }
        val record = MasteryRecordEntity(12, 2f, 1f, matnLastReviewedAt = 1_000, sanadLastReviewedAt = 1_000, nextReviewDue = 5_000, repetitionCount = 1)

        val emissions = withTimeout(10_000) {
            useCase().onEach { levels ->
                if (levels.all { l -> l.hadiths.all { it.metrics == null } }) db.masteryDao().upsert(record)
            }.take(2).toList()
        }

        val studied = emissions.last().first { it.level == HadithLevel.FOUNDATION }.hadiths.single { it.hadith.id == 12L }
        assertEquals(HadithProgress.LEARNING, studied.progress)
        assertTrue("due at 5 000, now 10 000", studied.isDue)
        assertEquals(1, emissions.last().first().status.learning)
    }
}
