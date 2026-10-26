package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.dao.searchInGroups
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.relation.HadithPlacement
import com.mutqan.hadith.data.repository.RoomHadithRepository
import com.mutqan.hadith.data.repository.RoomReviewRepository
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetHadithStudyUseCase
import com.mutqan.hadith.domain.usecase.GetLibraryPageUseCase
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The groups of the catalog (books and their sections) over the real seed, through the production database and prepopulation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupsCatalogTest {

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
    fun everySeededHadithHasATakhrijItsSahabiAndAPlaceInABook() {
        val seed = SeedLoader.load(context)
        val narrators = seed.narrators.associateBy { it.id }
        val groupIds = seed.groups.map { it.id }.toSet()
        seed.hadiths.forEach { hadith ->
            val chain = seed.edges.filter { it.hadithId == hadith.id }.sortedBy { it.orderIndex }.map { narrators.getValue(it.narratorId) }
            assertTrue("hadith ${hadith.id} is in a known group", hadith.groupId in groupIds)
            assertTrue("hadith ${hadith.id} has a takhrij", hadith.takhrij.isNotBlank())
            assertEquals("hadith ${hadith.id}: the sahabi is the end of the chain", chain.last().primaryName, hadith.sahabi)
            assertEquals("hadith ${hadith.id}: the end of the chain is a Companion", "صحابي", chain.last().tier)
        }
    }

    @Test
    fun theCatalogIsTheFortyHadithInOneBook() = runBlocking {
        val groups = dao.observeGroups().first()

        assertEquals(listOf(CatalogGroupEntity(1, null, "الأربعون النووية", "The Forty Hadith of an-Nawawi", 0)), groups)
        val all = dao.getAllWithSanad().map { it.hadith }
        assertEquals(42, all.size)
        assertTrue(all.all { it.groupId == 1L })
    }

    @Test
    fun theFortyHadithAreTaughtInTheOrderOfTheirNumbers() = runBlocking {
        val byPosition = dao.getAllWithSanad().map { it.hadith }.sortedBy { it.position }

        assertEquals((1..42).toList(), byPosition.map { it.position })
        assertEquals((1..42).toList(), byPosition.map { it.hadithNumber })
    }

    @Test
    fun theFoundationHadithsAreAnchoredToTheirCompanion() = runBlocking {
        val byNumber = dao.getHadithsInGroup(1).first().associateBy { it.hadithNumber }

        assertEquals((1..42).toSet(), byNumber.keys)
        // The Companion is named as an-Nawawi names him, and the takhrij is read from his own note.
        assertEquals("أمير المؤمنين أبي حفص عمر بن الخطاب" to "متفق عليه", byNumber.getValue(1).let { it.sahabi to it.takhrij })
        assertEquals("أبو عبد الرحمن عبد الله بن عمر بن الخطاب" to "متفق عليه", byNumber.getValue(3).let { it.sahabi to it.takhrij })
        assertEquals("أبو رقية تميم بن أوس الداري" to "رواه مسلم", byNumber.getValue(7).let { it.sahabi to it.takhrij })
        assertTrue(byNumber.values.all { it.sourceBook == "الأربعون النووية" })
    }

    // -- Queries ----------------------------------------------------------------------------

    @Test
    fun getHadithsInGroup_returnsTheGroupInCurriculumOrder() = runBlocking {
        val seeded = SeedLoader.load(context).hadiths
        assertEquals(seeded.sortedBy { it.hadithNumber }.map { it.id }, dao.getHadithsInGroup(1).first().map { it.id })
        assertTrue(dao.getHadithsInGroup(99).first().isEmpty())
    }

    @Test
    fun observePlacements_saysWhereEveryHadithIsFiledInCurriculumOrder() = runBlocking {
        val seeded = SeedLoader.load(context).hadiths.sortedBy { it.position }

        assertEquals(seeded.map { HadithPlacement(it.id, 1L) }, dao.observePlacements().first())
    }

    @Test
    fun newHadithsComeInTheOrderTheyAreTaught() = runBlocking {
        // The Forty Hadith by their numbers (their ids are not in that order).
        val curriculum = SeedLoader.load(context).hadiths.sortedBy { it.position }.map { it.id }
        assertEquals((1..42).map { n -> SeedLoader.load(context).hadiths.single { it.hadithNumber == n }.id }, curriculum)
        assertEquals(curriculum, dao.getUnseenWithSanad().map { it.hadith.id })
        // A hadith that was started drops out; nobody else moves.
        db.masteryDao().upsert(MasteryRecordEntity(11, 0f, 0f, null, null, 1_000, 0))
        assertEquals(curriculum - 11L, dao.getUnseenWithSanad().map { it.hadith.id })
    }

    @Test
    fun theHadithsOfANarratorAreInCurriculumOrder() = runBlocking {
        // «أبو هريرة» is the Companion of eight of the Forty Hadith.
        val seed = SeedLoader.load(context)
        val abuHurayra = seed.narrators.single { it.primaryName == "أبو هريرة" }.id
        val expected = seed.hadiths
            .filter { h -> seed.edges.any { it.hadithId == h.id && it.narratorId == abuHurayra } }
            .sortedBy { it.position }

        assertEquals(8, expected.size)
        assertEquals(expected.map { it.id }, dao.getHadithsByNarrator(abuHurayra).map { it.hadith.id })
    }

    @Test
    fun theGroupQueriesFollowTheLibrary() = runBlocking {
        var added = false
        val sizes = withTimeout(10_000) {
            dao.getHadithsInGroup(2)
                .onEach {
                    if (!added) {
                        added = true
                        dao.insertWithSanad(
                            otherBook(100),
                            listOf(SanadEdgeEntity(hadithId = 100, narratorId = 12, orderIndex = 0, transmissionTerm = "عن")),
                        )
                    }
                }
                .take(2)
                .toList()
                .map { it.size }
        }

        assertEquals(listOf(0, 1), sizes)
        assertEquals(1, dao.getHadithsInGroup(2).first().size)
    }

    private fun otherBook(id: Long) = HadithEntity(
        id, "الْبَيِّنَةُ عَلَى الْمُدَّعِي", "البينه على المدعي", "كتاب آخر", 1, "باب", "صحيح", "مصنف",
        takhrij = "رواه البخاري", sahabi = "عمر بن الخطاب", groupId = 2, position = 43,
    )

    @Test
    fun searchInGroups_staysInsideTheGroupsItIsGiven() = runBlocking {
        // «بني الإسلام على خمس» is Forty Hadith no. 3; looked for under another group it is not there.
        assertEquals(listOf(12L), dao.searchInGroups(listOf(1), "بني الاسلام على خمس").first().map { it.id })
        assertEquals(listOf(12L), dao.searchInGroups(listOf(2, 1), "بني الاسلام على خمس").first().map { it.id })
        assertTrue(dao.searchInGroups(listOf(2), "بني الاسلام على خمس").first().isEmpty())
        assertTrue(dao.searchInGroups(emptyList(), "بني الاسلام على خمس").first().isEmpty())
    }

    @Test
    fun searchInGroups_ignoresTashkeelAndOrthographicVariants() = runBlocking {
        assertEquals(listOf(12L), dao.searchInGroups(listOf(1), "بُنِيَ الإِسْلاَمُ عَلَى خَمْسٍ").first().map { it.id })
        assertEquals(listOf(13L), dao.searchInGroups(listOf(1), "الدين النصيحة").first().map { it.id })
    }

    @Test
    fun searchInGroups_treatsWildcardsAsLiteralTextAndNothingAsNothing() = runBlocking {
        assertTrue(dao.searchInGroups(listOf(1), "100%").first().isEmpty())
        assertTrue(dao.searchInGroups(listOf(1), "_").first().isEmpty())
        assertTrue(dao.searchInGroups(listOf(1), "   ").first().isEmpty())
        assertTrue(dao.searchInGroups(listOf(1), "").first().isEmpty())
    }

    @Test
    fun theRepositoryExposesTheGroupQueries() = runBlocking {
        val repository = RoomHadithRepository(db)

        assertEquals(listOf(1L), repository.observeGroups().first().map { it.id })
        assertEquals(42, repository.observePlacements().first().size)
        assertEquals(SeedLoader.load(context).hadiths.sortedBy { it.position }.map { it.id }, repository.getHadithsInGroup(1).first().map { it.id })
        assertEquals(listOf(13L), repository.searchInGroups(listOf(1), "النصيحة").first().map { it.id })
    }

    // -- GetLibraryPageUseCase over Room ------------------------------------------------------

    @Test
    fun theLibraryShelf_isTheOneBookWithItsFortyTwoHadiths() = runBlocking {
        val shelf = GetLibraryPageUseCase(RoomHadithRepository(db), RoomReviewRepository(db), DualDecayScheduler()).invoke().first()

        assertEquals(listOf(1L), shelf.groups.map { it.group.id })
        assertEquals(42, shelf.groups.single().status.total)
        assertEquals(42, shelf.groups.single().status.unseen)
        assertEquals(0, shelf.groups.single().sectionCount)
        assertNull(shelf.open)
    }

    @Test
    fun theLibraryBook_listsItsHadithsAllNew() = runBlocking {
        val book = GetLibraryPageUseCase(RoomHadithRepository(db), RoomReviewRepository(db), DualDecayScheduler()).invoke(1).first()

        assertEquals(SeedLoader.load(context).hadiths.sortedBy { it.position }.map { it.id }, book.hadiths.map { it.hadith.id })
        assertTrue(book.groups.isEmpty())
        assertTrue(book.hadiths.all { it.progress == HadithProgress.NEW })
    }

    @Test
    fun startingAHadithIsSeenByItsStudyAndTheLibrary() = runBlocking {
        val hadiths = RoomHadithRepository(db)
        val reviews = RoomReviewRepository(db)
        val scheduler = DualDecayScheduler()
        val study = GetHadithStudyUseCase(hadiths, reviews, scheduler) { 10_000L }
        val library = GetLibraryPageUseCase(hadiths, reviews, scheduler) { 10_000L }

        val studied = withTimeout(10_000) {
            study(12)
                .onEach { if (it?.isInStudy == false) StartMemorizingUseCase(hadiths, reviews) { 10_000L }(12) }
                .take(2)
                .toList()
        }

        assertEquals(listOf(false, true), studied.map { it!!.isInStudy })
        assertTrue(studied.last()!!.isDue)
        assertEquals(HadithProgress.LEARNING, studied.last()!!.progress)
        val inLibrary = library(1).first().hadiths.single { it.hadith.id == 12L }
        assertEquals(HadithProgress.LEARNING, inLibrary.progress)
        assertTrue(inLibrary.isDue)
        val rest = SeedLoader.load(context).hadiths.filter { it.id != 12L }.sortedBy { it.hadithNumber }.map { it.id }
        assertEquals(rest, hadiths.getUnseen().map { it.hadith.id })
    }

    @Test
    fun theLibraryPage_picksUpANewReview() = runBlocking {
        val useCase = GetLibraryPageUseCase(RoomHadithRepository(db), RoomReviewRepository(db), DualDecayScheduler()) { 10_000L }
        val record = MasteryRecordEntity(12, 2f, 1f, matnLastReviewedAt = 1_000, sanadLastReviewedAt = 1_000, nextReviewDue = 5_000, repetitionCount = 1)

        val emissions = withTimeout(10_000) {
            useCase(1).onEach { page ->
                if (page.hadiths.all { it.metrics == null }) db.masteryDao().upsert(record)
            }.take(2).toList()
        }

        val studied = emissions.last().hadiths.single { it.hadith.id == 12L }
        assertEquals(HadithProgress.LEARNING, studied.progress)
        assertTrue("due at 5 000, now 10 000", studied.isDue)
        assertEquals(1, emissions.last().status.learning)
        assertEquals(1, emissions.last().dueCount)
    }
}
