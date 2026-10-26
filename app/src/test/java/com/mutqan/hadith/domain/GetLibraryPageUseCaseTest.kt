package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetLibraryPageUseCase
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.LibraryPage
import com.mutqan.hadith.domain.usecase.LibraryStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library page by page: the shelf of books, and a book or a section opened into its sections and hadiths, with the
 * user's progress rolled up through every section below.
 *
 * The catalog of these tests:
 *
 *     1 First book                      hadiths 1, 2
 *     2 Second book                     hadith 10
 *       3 Section on faith              hadiths 11, 12
 *         5 Part of the section         hadith 14
 *       4 Section on prayer             hadith 13
 */
class GetLibraryPageUseCaseTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val companion = narrator(1, "عمر بن الخطاب", tier = "صحابي")
    private val teacher = narrator(2, "الحميدي", tier = "شيخ المصنف")

    private fun group(id: Long, parent: Long?, title: String, position: Int = 0) =
        CatalogGroupEntity(id, parent, title, "$title (en)", position)

    private val groups = listOf(
        group(1, null, "الكتاب الأول", 0),
        group(2, null, "الكتاب الثاني", 1),
        group(3, 2, "باب الإيمان", 0),
        group(4, 2, "باب الصلاة", 1),
        group(5, 3, "فصل", 0),
    )

    private fun filed(id: Long, groupId: Long?, position: Int, matn: String, narrators: List<com.mutqan.hadith.data.entity.NarratorEntity> = listOf(companion)): HadithWithFullSanad =
        hadith(id, narrators, position, groupId).let {
            it.copy(hadith = it.hadith.copy(matnWithTashkeel = matn, matnClean = com.mutqan.hadith.data.text.ArabicNormalizer.normalize(matn)))
        }

    private val library = listOf(
        filed(1, 1, 1, "إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"),
        filed(2, 1, 2, "الدِّينُ النَّصِيحَةُ"),
        filed(10, 2, 3, "إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ", listOf(teacher, companion)),
        filed(11, 3, 4, "بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ", listOf(teacher, companion)),
        filed(12, 3, 5, "لَا يُؤْمِنُ أَحَدُكُمْ", listOf(teacher, companion)),
        filed(13, 4, 6, "الصَّلَاةُ عِمَادُ الدِّينِ", listOf(teacher, companion)),
        filed(14, 5, 7, "الْإِيمَانُ بِضْعٌ وَسَبْعُونَ", listOf(teacher, companion)),
    )
    private val reviews = FakeReviewRepository()
    private val useCase = GetLibraryPageUseCase(FakeHadithRepository(library, groups), reviews, scheduler) { now }

    private fun record(id: Long, matn: Float, sanad: Float, due: Long) =
        MasteryRecordEntity(id, matn, sanad, matnLastReviewedAt = now, sanadLastReviewedAt = now, nextReviewDue = due, repetitionCount = 3)

    private fun page(group: Long? = null, query: String = ""): LibraryPage = runBlocking { useCase(group, query).first() }

    private fun LibraryPage.hadithIds() = hadiths.map { it.hadith.id }

    private fun LibraryPage.groupIds() = this.groups.map { it.group.id }

    // -- The shelf ----------------------------------------------------------------------------

    @Test
    fun theRootIsTheBooksInOrderAndLeavesTheHadithsToTheirBooks() {
        val root = page()

        assertEquals(listOf(1L, 2L), root.groupIds())
        assertTrue(root.trail.isEmpty())
        assertNull(root.open)
        assertTrue(root.hadiths.isEmpty())
    }

    @Test
    fun aBookCountsEveryHadithInItAndInItsSectionsHoweverDeep() {
        val books = page().groups

        assertEquals(2, books.first { it.group.id == 1L }.status.total)
        assertEquals(5, books.first { it.group.id == 2L }.status.total) // 10, 11, 12, 13 and 14 down in the part of the section
        assertEquals(0, books.first { it.group.id == 1L }.sectionCount)
        assertEquals(2, books.first { it.group.id == 2L }.sectionCount) // the two sections, not the part of the first one
        assertEquals(7, page().status.total)
    }

    @Test
    fun theBooksComeInTheOrderOfTheirPositionWhateverTheirIds() {
        val reversed = listOf(group(1, null, "الأول", 5), group(2, null, "الثاني", 2), group(3, null, "الثالث", 2))
        val shelf = GetLibraryPageUseCase(FakeHadithRepository(emptyList(), reversed), reviews, scheduler) { now }

        assertEquals(listOf(2L, 3L, 1L), runBlocking { shelf().first() }.groupIds())
    }

    // -- A book and its sections --------------------------------------------------------------

    @Test
    fun aBookListsItsSectionsAndTheHadithsFiledDirectlyUnderIt() {
        val book = page(2)

        assertEquals(listOf(3L, 4L), book.groupIds())
        assertEquals(listOf(10L), book.hadithIds()) // 11 to 14 are in sections, not here
        assertEquals(listOf(2L), book.trail.map { it.id })
        assertEquals(2L, book.open?.id)
        assertEquals(5, book.status.total)
    }

    @Test
    fun aSectionOfASectionKeepsTheTrailFromItsBook() {
        val faith = page(3)

        assertEquals(listOf(2L, 3L), faith.trail.map { it.id })
        assertEquals(listOf(5L), faith.groupIds())
        assertEquals(listOf(11L, 12L), faith.hadithIds())
        assertEquals(3, faith.status.total)
        assertEquals(listOf(2L, 3L, 5L), page(5).trail.map { it.id })
        assertEquals(listOf(14L), page(5).hadithIds())
    }

    @Test
    fun aGroupWithNothingInItIsEmpty() {
        val empty = GetLibraryPageUseCase(FakeHadithRepository(library, groups + group(6, 1, "فارغ", 0)), reviews, scheduler) { now }

        val page = runBlocking { empty(6).first() }

        assertTrue(page.groups.isEmpty() && page.hadiths.isEmpty())
        assertEquals(LibraryStatus(0, 0, 0, 0), page.status)
    }

    @Test
    fun anUnknownGroupIsTheShelf() {
        assertEquals(page().groupIds(), page(404).groupIds())
        assertTrue(page(404).trail.isEmpty())
    }

    @Test
    fun aHadithTheCatalogHasNotFiledIsListedNowhere() {
        val stray = filed(99, null, 8, "نص")
        val strayed = GetLibraryPageUseCase(FakeHadithRepository(library + stray, groups), reviews, scheduler) { now }

        val everything = listOf(null, 1L, 2L, 3L, 4L, 5L).flatMap { runBlocking { strayed(it).first() }.hadithIds() }

        assertFalse(99L in everything)
        assertEquals(7, runBlocking { strayed().first() }.status.total)
    }

    @Test
    fun groupsThatNameEachOtherAsParentsDoNotLoopForever() {
        val tangled = listOf(group(1, 2, "أ", 0), group(2, 1, "ب", 0))
        val tangledUseCase = GetLibraryPageUseCase(FakeHadithRepository(emptyList(), tangled), reviews, scheduler) { now }

        val page = runBlocking { tangledUseCase(1).first() }

        assertEquals(setOf(1L, 2L), page.trail.map { it.id }.toSet())
        assertTrue(runBlocking { tangledUseCase().first() }.groups.isEmpty())
    }

    // -- The user's progress ------------------------------------------------------------------

    @Test
    fun aHadithWithoutAReviewIsNewAndCarriesNoMetrics() {
        val item = page(1).hadiths.first()

        assertNull(item.metrics)
        assertFalse(item.isDue)
        assertEquals(HadithProgress.NEW, item.progress)
    }

    @Test
    fun masteryIsAttachedToTheHadithItBelongsTo() {
        reviews.mastery[11] = record(11, matn = 2f, sanad = 1f, due = now + DAY_MS)

        val item = page(3).hadiths.single { it.hadith.id == 11L }

        assertEquals(scheduler.metrics(reviews.mastery.getValue(11)), item.metrics)
        assertEquals(HadithProgress.LEARNING, item.progress)
        assertFalse("not due until tomorrow", item.isDue)
        assertTrue(page(1).hadiths.all { it.metrics == null })
    }

    @Test
    fun aHadithHeldForWeeksOnBothSidesIsMastered() {
        reviews.mastery[10] = record(10, matn = 365f, sanad = 365f, due = now + 30 * DAY_MS)

        val item = page(2).hadiths.single()

        assertTrue(item.metrics!!.overallMastery >= 0.7f)
        assertEquals(HadithProgress.MASTERED, item.progress)
    }

    @Test
    fun dueMeansTheNextReviewIsAtOrBeforeNow() {
        reviews.mastery[11] = record(11, 2f, 1f, due = now)
        reviews.mastery[12] = record(12, 2f, 1f, due = now - 1)
        reviews.mastery[14] = record(14, 2f, 1f, due = now + 1)

        assertEquals(listOf(true, true), page(3).hadiths.map { it.isDue })
        assertEquals(listOf(false), page(5).hadiths.map { it.isDue })
    }

    @Test
    fun theStatusAndTheDueCountOfAGroupRollUpThroughItsSections() {
        reviews.mastery[10] = record(10, 365f, 365f, due = now + 30 * DAY_MS)
        reviews.mastery[11] = record(11, 2f, 1f, due = now)
        reviews.mastery[14] = record(14, 2f, 1f, due = now - DAY_MS)

        val shelf = page().groups.first { it.group.id == 2L }
        val faith = page(2).groups.first { it.group.id == 3L }

        assertEquals(LibraryStatus(total = 5, unseen = 2, learning = 2, mastered = 1), shelf.status)
        assertEquals(2, shelf.dueCount)
        assertEquals(LibraryStatus(total = 3, unseen = 1, learning = 2, mastered = 0), faith.status)
        assertEquals(2, faith.dueCount)
        // The open book says the same of itself as its card on the shelf does.
        assertEquals(shelf.status, page(2).status)
        assertEquals(shelf.dueCount, page(2).dueCount)
        assertEquals(LibraryStatus(total = 2, unseen = 2, learning = 0, mastered = 0), page().groups.first { it.group.id == 1L }.status)
    }

    // -- Search ------------------------------------------------------------------------------

    @Test
    fun aSearchLooksInTheOpenGroupAndEverySectionBelowIt() {
        val found = page(2, "الإيمان")

        // «الإيمان بضع وسبعون» is down in the part of the section; «لا يؤمن» is another word.
        assertEquals(listOf(14L), found.hadithIds())
        assertTrue(found.isSearch)
        assertEquals("الإيمان", found.searched)
    }

    @Test
    fun aSearchNeverLeavesTheGroupItWasTypedIn() {
        assertEquals(listOf(1L), page(1, "الأعمال بالنيات").hadithIds())
        assertEquals(emptyList<Long>(), page(2, "الأعمال بالنيات").hadithIds()) // «بالنية» in the second book is not «بالنيات»
        assertEquals(listOf(10L), page(2, "الأعمال بالنية").hadithIds())
    }

    @Test
    fun aSearchListsOnlyMatchesAndNoSectionCards() {
        val found = page(2, "الصلاة")

        assertEquals(listOf(13L), found.hadithIds())
        assertTrue(found.groups.isEmpty())
    }

    @Test
    fun aSearchDoesNotChangeTheProgressOfTheGroup() {
        reviews.mastery[11] = record(11, 2f, 1f, due = now)

        assertEquals(page(2).status, page(2, "الصلاة").status)
        assertEquals(page(2).dueCount, page(2, "الصلاة").dueCount)
    }

    @Test
    fun aSearchIgnoresTashkeelAndKeepsEachMatchWithItsMastery() {
        reviews.mastery[11] = record(11, 2f, 1f, due = now)

        val match = page(2, "بُنِيَ الإِسْلاَمُ").hadiths.single()

        assertEquals(11L, match.hadith.id)
        assertNotNull(match.metrics)
    }

    @Test
    fun aSearchFromTheShelfLooksInEveryBook() {
        assertEquals(listOf(1L, 10L), page(null, "الأعمال").hadithIds())
    }

    @Test
    fun aBlankQueryListsTheGroupAsItIs() {
        assertEquals(page(2).hadithIds(), page(2, "   ").hadithIds())
        assertEquals(page(2).groupIds(), page(2, "   ").groupIds())
        assertFalse(page(2, "   ").isSearch)
    }

    @Test
    fun aQueryWithNothingSearchableInItFindsNothing() {
        // Only tashkeel: it normalizes to nothing rather than matching every hadith.
        assertTrue(page(2, "ً").hadiths.isEmpty())
    }

    @Test
    fun theEntityKeepsTheTakhrijAndSahabiOfEachHadith() {
        val item: HadithEntity = page(1).hadiths.first().hadith

        assertEquals("متفق عليه", item.takhrij)
        assertEquals("عمر بن الخطاب", item.sahabi)
        assertEquals(1L, item.groupId)
    }
}
