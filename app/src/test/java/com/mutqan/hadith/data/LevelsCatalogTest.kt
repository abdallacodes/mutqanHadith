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
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryByLevelUseCase
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.StartMemorizingUseCase
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
    fun theCatalogHoldsTheFoundationLevelAlone() = runBlocking {
        val all = dao.getAllWithSanad().map { it.hadith }

        assertEquals(42, all.size)
        assertTrue(all.all { it.level == HadithLevel.FOUNDATION.value })
    }

    @Test
    fun theFoundationHadithsAreAnchoredToTheirCompanion() = runBlocking {
        val byNumber = dao.getHadithsByLevel(1).first().associateBy { it.hadithNumber }

        assertEquals((1..42).toSet(), byNumber.keys)
        // The Companion is named as an-Nawawi names him, and the takhrij is read from his own note.
        assertEquals("أمير المؤمنين أبي حفص عمر بن الخطاب" to "متفق عليه", byNumber.getValue(1).let { it.sahabi to it.takhrij })
        assertEquals("أبو عبد الرحمن عبد الله بن عمر بن الخطاب" to "متفق عليه", byNumber.getValue(3).let { it.sahabi to it.takhrij })
        assertEquals("أبو رقية تميم بن أوس الداري" to "رواه مسلم", byNumber.getValue(7).let { it.sahabi to it.takhrij })
        assertTrue(byNumber.values.all { it.sourceBook == "الأربعون النووية" })
    }

    // -- Queries ----------------------------------------------------------------------------

    @Test
    fun getHadithsByLevel_returnsTheLevelInCurriculumOrder() = runBlocking {
        val seeded = SeedLoader.load(context).hadiths
        assertEquals(seeded.filter { it.level == 1 }.sortedBy { it.hadithNumber }.map { it.id }, dao.getHadithsByLevel(1).first().map { it.id })
        // The other levels hold nothing in this catalog.
        assertTrue(dao.getHadithsByLevel(2).first().isEmpty())
        assertTrue(dao.getHadithsByLevel(3).first().isEmpty())
        assertTrue(dao.getHadithsByLevel(99).first().isEmpty())
    }

    @Test
    fun newHadithsComeLevelByLevelThenByNumber() = runBlocking {
        // The Forty Hadith by their numbers (their ids are not in that order).
        val curriculum = SeedLoader.load(context).hadiths.sortedWith(compareBy({ it.level }, { it.hadithNumber }, { it.id })).map { it.id }
        assertEquals((1..42).map { n -> SeedLoader.load(context).hadiths.single { it.level == 1 && it.hadithNumber == n }.id }, curriculum)
        assertEquals(curriculum, dao.getUnseenWithSanad().map { it.hadith.id })
        // A hadith that was started drops out; nobody else moves.
        db.masteryDao().upsert(MasteryRecordEntity(11, 0f, 0f, null, null, 1_000, 0))
        assertEquals(curriculum - 11L, dao.getUnseenWithSanad().map { it.hadith.id })
    }

    @Test
    fun theHadithsOfANarratorComeLevelByLevel() = runBlocking {
        // «أبو هريرة» is the Companion of eight of the Forty Hadith.
        val seed = SeedLoader.load(context)
        val abuHurayra = seed.narrators.single { it.primaryName == "أبو هريرة" }.id
        val expected = seed.hadiths
            .filter { h -> seed.edges.any { it.hadithId == h.id && it.narratorId == abuHurayra } }
            .sortedWith(compareBy({ it.level }, { it.hadithNumber }, { it.id }))

        assertEquals(8, expected.size)
        assertEquals(List(8) { 1 }, expected.map { it.level })
        assertEquals(expected.map { it.id }, dao.getHadithsByNarrator(abuHurayra).map { it.hadith.id })
    }

    @Test
    fun countHadithsByLevel_countsEachLevel() = runBlocking {
        assertEquals(listOf(42, 0, 0), (1..3).map { dao.countHadithsByLevel(it).first() })
    }

    @Test
    fun theLevelQueriesFollowTheLibrary() = runBlocking {
        var added = false
        val sizes = withTimeout(10_000) {
            dao.getHadithsByLevel(2)
                .onEach {
                    if (!added) {
                        added = true
                        dao.insertWithSanad(ahkam(100), listOf(SanadEdgeEntity(hadithId = 100, narratorId = 12, orderIndex = 0, transmissionTerm = "عن")))
                    }
                }
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
        // «بني الإسلام على خمس» is Forty Hadith no. 3, at level 1; no other level has it.
        assertEquals(listOf(12L), dao.searchInLevel(1, "بني الاسلام على خمس").first().map { it.id })
        assertTrue(dao.searchInLevel(2, "بني الاسلام على خمس").first().isEmpty())
        assertTrue(dao.searchInLevel(3, "بني الاسلام على خمس").first().isEmpty())
    }

    @Test
    fun searchInLevel_ignoresTashkeelAndOrthographicVariants() = runBlocking {
        assertEquals(listOf(12L), dao.searchInLevel(1, "بُنِيَ الإِسْلاَمُ عَلَى خَمْسٍ").first().map { it.id })
        assertEquals(listOf(13L), dao.searchInLevel(1, "الدين النصيحة").first().map { it.id })
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

        assertEquals(SeedLoader.load(context).hadiths.filter { it.level == 1 }.sortedBy { it.hadithNumber }.map { it.id }, repository.getHadithsByLevel(1).first().map { it.id })
        assertEquals(42, repository.countHadithsByLevel(1).first())
        assertEquals(listOf(13L), repository.searchInLevel(1, "النصيحة").first().map { it.id })
    }

    // -- GetLibraryByLevelUseCase over Room ---------------------------------------------------

    @Test
    fun theLibraryByLevel_listsTheSeededLevels() = runBlocking {
        val levels = GetLibraryByLevelUseCase(RoomHadithRepository(db), RoomReviewRepository(db), DualDecayScheduler()).invoke().first()

        assertEquals(listOf(42, 0, 0), levels.map { it.hadiths.size })
        assertEquals(listOf(42, 0, 0), levels.map { it.totalInLevel })
        assertTrue(levels.flatMap { it.hadiths }.all { it.progress == HadithProgress.NEW })
    }

    @Test
    fun startingAHadithIsSeenByItsStudyAndTheLibrary() = runBlocking {
        val hadiths = RoomHadithRepository(db)
        val reviews = RoomReviewRepository(db)
        val scheduler = DualDecayScheduler()
        val study = GetHadithStudyUseCase(hadiths, reviews, scheduler) { 10_000L }
        val library = GetLibraryByLevelUseCase(hadiths, reviews, scheduler) { 10_000L }

        val studied = withTimeout(10_000) {
            study(12)
                .onEach { if (it?.isInStudy == false) StartMemorizingUseCase(hadiths, reviews) { 10_000L }(12) }
                .take(2)
                .toList()
        }

        assertEquals(listOf(false, true), studied.map { it!!.isInStudy })
        assertTrue(studied.last()!!.isDue)
        assertEquals(HadithProgress.LEARNING, studied.last()!!.progress)
        val inLibrary = library().first().first { it.level == HadithLevel.FOUNDATION }.hadiths.single { it.hadith.id == 12L }
        assertEquals(HadithProgress.LEARNING, inLibrary.progress)
        assertTrue(inLibrary.isDue)
        val foundation = SeedLoader.load(context).hadiths.filter { it.level == 1 && it.id != 12L }.sortedBy { it.hadithNumber }.map { it.id }
        assertEquals(foundation, hadiths.getUnseen().filter { it.hadith.level == 1 }.map { it.hadith.id })
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
