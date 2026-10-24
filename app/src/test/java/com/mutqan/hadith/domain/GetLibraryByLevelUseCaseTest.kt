package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.model.HadithLevel
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetLibraryByLevelUseCase
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.LibraryLevel
import com.mutqan.hadith.domain.usecase.LibraryStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GetLibraryByLevelUseCaseTest {

    private val now = 1_700_000_000_000L
    private val scheduler = DualDecayScheduler()
    private val companion = narrator(1, "عمر بن الخطاب", tier = "صحابي")
    private val teacher = narrator(2, "الحميدي", tier = "شيخ المصنف")

    private fun withMatn(h: HadithWithFullSanad, matn: String): HadithWithFullSanad =
        h.copy(hadith = h.hadith.copy(matnWithTashkeel = matn, matnClean = com.mutqan.hadith.data.text.ArabicNormalizer.normalize(matn)))

    // Level 1: ids 1-2 (a single link), level 3: ids 10-12; nothing yet at level 2.
    private val library = listOf(
        withMatn(hadith(1, listOf(companion), level = 1), "إِنَّمَا الْأَعْمَالُ بِالنِّيَّاتِ"),
        withMatn(hadith(2, listOf(companion), level = 1), "الدِّينُ النَّصِيحَةُ"),
        withMatn(hadith(10, listOf(teacher, companion), level = 3), "إِنَّمَا الْأَعْمَالُ بِالنِّيَّةِ"),
        withMatn(hadith(11, listOf(teacher, companion), level = 3), "بُنِيَ الْإِسْلَامُ عَلَى خَمْسٍ"),
        withMatn(hadith(12, listOf(teacher, companion), level = 3), "لَا يُؤْمِنُ أَحَدُكُمْ"),
    )
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository()
    private val useCase = GetLibraryByLevelUseCase(hadiths, reviews, scheduler) { now }

    private fun record(id: Long, matn: Float, sanad: Float, due: Long) =
        MasteryRecordEntity(id, matn, sanad, matnLastReviewedAt = now, sanadLastReviewedAt = now, nextReviewDue = due, repetitionCount = 3)

    private fun levels(query: String = ""): List<LibraryLevel> = runBlocking { useCase(query).first() }

    private fun List<LibraryLevel>.of(level: HadithLevel) = single { it.level == level }

    private fun LibraryLevel.ids() = hadiths.map { it.hadith.id }

    @Test
    fun everyLevelIsListedInOrderEvenWhenItHoldsNothing() {
        val all = levels()

        assertEquals(listOf(HadithLevel.FOUNDATION, HadithLevel.AHKAM, HadithLevel.MUSNAD), all.map { it.level })
        assertTrue(all.of(HadithLevel.AHKAM).hadiths.isEmpty())
        assertEquals(0, all.of(HadithLevel.AHKAM).totalInLevel)
    }

    @Test
    fun hadithsAreGroupedByTheirLevelInCurriculumOrder() {
        val all = levels()

        assertEquals(listOf(1L, 2L), all.of(HadithLevel.FOUNDATION).ids())
        assertEquals(listOf(10L, 11L, 12L), all.of(HadithLevel.MUSNAD).ids())
        assertEquals(2, all.of(HadithLevel.FOUNDATION).totalInLevel)
        assertEquals(3, all.of(HadithLevel.MUSNAD).totalInLevel)
    }

    @Test
    fun aHadithWithoutAReviewIsNewAndCarriesNoMetrics() {
        val item = levels().of(HadithLevel.FOUNDATION).hadiths.first()

        assertNull(item.metrics)
        assertFalse(item.isDue)
        assertEquals(HadithProgress.NEW, item.progress)
    }

    @Test
    fun masteryIsAttachedToTheHadithItBelongsTo() {
        reviews.mastery[11] = record(11, matn = 2f, sanad = 1f, due = now + DAY_MS)

        val item = levels().of(HadithLevel.MUSNAD).hadiths.single { it.hadith.id == 11L }

        assertEquals(scheduler.metrics(reviews.mastery.getValue(11)), item.metrics)
        assertEquals(HadithProgress.LEARNING, item.progress)
        assertFalse("not due until tomorrow", item.isDue)
        // The same id at another level would be someone else's progress: nothing leaks across hadiths.
        assertTrue(levels().of(HadithLevel.FOUNDATION).hadiths.all { it.metrics == null })
    }

    @Test
    fun aHadithHeldForWeeksOnBothSidesIsMastered() {
        reviews.mastery[10] = record(10, matn = 365f, sanad = 365f, due = now + 30 * DAY_MS)

        val item = levels().of(HadithLevel.MUSNAD).hadiths.first()

        assertTrue(item.metrics!!.overallMastery >= 0.7f)
        assertEquals(HadithProgress.MASTERED, item.progress)
    }

    @Test
    fun dueMeansTheNextReviewIsAtOrBeforeNow() {
        reviews.mastery[10] = record(10, 2f, 1f, due = now)
        reviews.mastery[11] = record(11, 2f, 1f, due = now - 1)
        reviews.mastery[12] = record(12, 2f, 1f, due = now + 1)

        val musnad = levels().of(HadithLevel.MUSNAD)

        assertEquals(listOf(true, true, false), musnad.hadiths.map { it.isDue })
        assertEquals(2, musnad.dueCount)
    }

    @Test
    fun theStatusSplitsALevelByProgress() {
        reviews.mastery[10] = record(10, 365f, 365f, due = now + 30 * DAY_MS)
        reviews.mastery[11] = record(11, 2f, 1f, due = now)

        val musnad = levels().of(HadithLevel.MUSNAD)

        assertEquals(LibraryStatus(total = 3, unseen = 1, learning = 1, mastered = 1), musnad.status)
        assertEquals(LibraryStatus(total = 2, unseen = 2, learning = 0, mastered = 0), levels().of(HadithLevel.FOUNDATION).status)
    }

    @Test
    fun aSearchNarrowsEachLevelButNotItsTotal() {
        val found = levels("الأعمال بالنيات")

        assertEquals(listOf(1L), found.of(HadithLevel.FOUNDATION).ids())
        assertEquals(2, found.of(HadithLevel.FOUNDATION).totalInLevel)
        assertEquals(emptyList<Long>(), found.of(HadithLevel.MUSNAD).ids()) // "بالنيات" is not "بالنية"
        assertEquals(3, found.of(HadithLevel.MUSNAD).totalInLevel)
    }

    @Test
    fun aSearchIgnoresTashkeelAndKeepsEachMatchWithItsMastery() {
        reviews.mastery[11] = record(11, 2f, 1f, due = now)

        val found = levels("بُنِيَ الإِسْلاَمُ")

        val match = found.of(HadithLevel.MUSNAD).hadiths.single()
        assertEquals(11L, match.hadith.id)
        assertNotNull(match.metrics)
        assertTrue(found.of(HadithLevel.FOUNDATION).hadiths.isEmpty())
    }

    @Test
    fun aBlankQueryListsEverything() {
        assertEquals(levels().map { it.ids() }, levels("   ").map { it.ids() })
    }

    @Test
    fun aQueryWithNothingSearchableInItFindsNothing() {
        // Only tashkeel: it normalizes to nothing rather than matching every hadith.
        assertTrue(levels("ً").all { it.hadiths.isEmpty() })
    }

    @Test
    fun aHadithOutsideTheKnownLevelsIsNotListed() {
        val stray = withMatn(hadith(99, listOf(companion), level = 7), "نص")
        val useCase = GetLibraryByLevelUseCase(FakeHadithRepository(library + stray), reviews, scheduler) { now }

        val all = runBlocking { useCase().first() }

        assertTrue(all.flatMap { it.hadiths }.none { it.hadith.id == 99L })
        assertEquals(library.size, all.sumOf { it.hadiths.size })
    }

    @Test
    fun theEntityKeepsTheTakhrijAndSahabiOfEachHadith() {
        val item: HadithEntity = levels().of(HadithLevel.FOUNDATION).hadiths.first().hadith

        assertEquals("متفق عليه", item.takhrij)
        assertEquals("عمر بن الخطاب", item.sahabi)
        assertEquals(HadithLevel.FOUNDATION.value, item.level)
    }

    @Test
    fun levelsMapToTheirStoredNumbers() {
        assertEquals(listOf(1, 2, 3), HadithLevel.entries.map { it.value })
        assertEquals(HadithLevel.AHKAM, HadithLevel.of(2))
        assertNull(HadithLevel.of(0))
        assertNull(HadithLevel.of(4))
    }
}
