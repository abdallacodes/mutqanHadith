package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.model.CollectionAccent
import com.mutqan.hadith.domain.model.CollectionSaved
import com.mutqan.hadith.domain.model.CollectionTitle
import com.mutqan.hadith.domain.model.TitleProblem
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.CreateCollectionUseCase
import com.mutqan.hadith.domain.usecase.DeleteCollectionUseCase
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.ObserveCollectionDetailUseCase
import com.mutqan.hadith.domain.usecase.RenameCollectionUseCase
import com.mutqan.hadith.domain.usecase.SetCollectionMembershipUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Making, renaming, filling and deleting personal collections, and reading one with the user's progress on its hadiths. */
class CollectionsUseCasesTest {

    private var clock = 1_700_000_000_000L
    private val chain = (1L..3L).map { narrator(it) }
    private val library = (1L..6L).map { hadith(it, chain, level = 1) }
    private val hadiths = FakeHadithRepository(library)
    private val collections = FakeCollectionRepository(library)
    private val reviews = FakeReviewRepository()
    private val scheduler = DualDecayScheduler()

    private val create = CreateCollectionUseCase(collections) { clock }
    private val rename = RenameCollectionUseCase(collections)
    private val delete = DeleteCollectionUseCase(collections)
    private val membership = SetCollectionMembershipUseCase(collections) { clock }
    private val detail = ObserveCollectionDetailUseCase(collections, reviews, scheduler) { clock }

    private fun saved(result: CollectionSaved): Long = (result as CollectionSaved.Saved).id

    private fun rejected(result: CollectionSaved): TitleProblem = (result as CollectionSaved.Rejected).problem

    private suspend fun titles() = collections.observeAll().first().map { it.title }

    // -- Creating ---------------------------------------------------------------------------------------------------

    @Test
    fun create_keepsTheNameTidied() = runBlocking {
        create("  صلاة   الجمعة \n")
        create("a  b\tc")

        assertEquals(listOf("a b c", "صلاة الجمعة"), titles().sorted())
    }

    @Test
    fun create_refusesAnEmptyOrWhitespaceOnlyNameAndWritesNothing() = runBlocking {
        listOf("", "   ", "\t\n", "  ", "​", "‏‎", " ​ ").forEach { blank ->
            assertEquals("[$blank]", TitleProblem.EMPTY, rejected(create(blank)))
        }

        assertEquals(0, collections.writes)
        assertTrue(titles().isEmpty())
    }

    @Test
    fun create_acceptsANameOfExactlyTheLimitAndRefusesOneCharacterMore() = runBlocking {
        val longest = "ب".repeat(CollectionTitle.MAX_LENGTH)

        saved(create(longest))
        assertEquals(listOf(longest), titles())

        assertEquals(TitleProblem.TOO_LONG, rejected(create(longest + "ب")))
        assertEquals(1, titles().size)
    }

    @Test
    fun create_theLimitCountsTheTidiedNameNotTheTypedOne() = runBlocking {
        val typed = "  " + "ب".repeat(CollectionTitle.MAX_LENGTH) + "    "

        assertTrue(create(typed) is CollectionSaved.Saved)
    }

    @Test
    fun create_refusesANameAnotherCollectionHasWhateverItsCaseAndSpacing() = runBlocking {
        create("Adhkar")

        assertEquals(TitleProblem.DUPLICATE, rejected(create("adhkar")))
        assertEquals(TitleProblem.DUPLICATE, rejected(create("  ADHKAR  ")))
        assertEquals(1, titles().size)
    }

    @Test
    fun create_aRefusalWritesNothing() = runBlocking {
        create("One")
        val writes = collections.writes

        create("")
        create("one")
        create("ب".repeat(41))

        assertEquals(writes, collections.writes)
    }

    @Test
    fun create_startsWithItsFirstHadith() = runBlocking {
        val id = saved(create("With one", firstHadithId = 3))

        assertEquals(listOf(3L), collections.getHadithIds(id))
    }

    @Test
    fun create_withAHadithTheLibraryLacksMakesNoCollection() = runBlocking {
        val failed = runCatching { create("Ghost", firstHadithId = 999) }

        assertTrue(failed.isFailure)
        assertTrue(titles().isEmpty())
    }

    @Test
    fun create_keepsAnAccentOfThePaletteAndMendsAnyOther() = runBlocking {
        val inside = saved(create("Inside", accent = CollectionAccent.COUNT - 1))
        val outside = saved(create("Outside", accent = 99))
        val negative = saved(create("Negative", accent = -3))

        val accents = collections.observeAll().first().associate { it.id to it.accent }
        assertEquals(CollectionAccent.COUNT - 1, accents.getValue(inside))
        assertEquals(0, accents.getValue(outside))
        assertEquals(0, accents.getValue(negative))
    }

    // -- Renaming ---------------------------------------------------------------------------------------------------

    @Test
    fun rename_changesTheNameAndTheAccentAndKeepsTheHadiths() = runBlocking {
        val id = saved(create("Before", accent = 0, firstHadithId = 2))

        assertEquals(CollectionSaved.Saved(id), rename(id, "  After  ", 4))

        val summary = collections.observeAll().first().single()
        assertEquals(Triple("After", 4, 1), Triple(summary.title, summary.accent, summary.hadithCount))
    }

    @Test
    fun rename_aCollectionMayKeepItsOwnNameWhenOnlyTheColorChanges() = runBlocking {
        val id = saved(create("Same", accent = 0))

        assertEquals(CollectionSaved.Saved(id), rename(id, "same", 3))

        assertEquals(3, collections.observeAll().first().single().accent)
    }

    @Test
    fun rename_refusesAnotherCollectionsNameAndAnEmptyOne() = runBlocking {
        create("Other")
        val id = saved(create("Mine"))

        assertEquals(TitleProblem.DUPLICATE, rejected(rename(id, "other", 0)))
        assertEquals(TitleProblem.EMPTY, rejected(rename(id, "   ", 0)))
        assertEquals(TitleProblem.TOO_LONG, rejected(rename(id, "ب".repeat(41), 0)))
        assertEquals(setOf("Mine", "Other"), titles().toSet())
    }

    // -- Membership -------------------------------------------------------------------------------------------------

    @Test
    fun membership_addingAndRemovingAHadith() = runBlocking {
        val id = saved(create("Mine"))

        membership(id, 4, member = true)
        clock += 1_000
        membership(id, 2, member = true)
        assertEquals(listOf(4L, 2L), collections.getHadithIds(id))
        membership(id, 4, member = false)

        assertEquals(listOf(2L), collections.getHadithIds(id))
    }

    @Test
    fun membership_isSafeToRepeatInBothDirections() = runBlocking {
        val id = saved(create("Mine"))

        repeat(3) { membership(id, 5, member = true) }
        assertEquals(listOf(5L), collections.getHadithIds(id))

        repeat(3) { membership(id, 5, member = false) }
        membership(id, 6, member = false)
        assertTrue(collections.getHadithIds(id).isEmpty())
    }

    @Test
    fun membership_theHadithsComeInTheOrderTheyJoined() = runBlocking {
        val id = saved(create("Ordered"))

        listOf(5L, 1L, 3L).forEach { clock += 1_000; membership(id, it, member = true) }

        assertEquals(listOf(5L, 1L, 3L), collections.getHadithIds(id))
        assertEquals(listOf(5L, 1L, 3L), collections.observeHadiths(id).first().map { it.id })
    }

    @Test
    fun membership_oneHadithCanBeInSeveralCollections() = runBlocking {
        val a = saved(create("A", firstHadithId = 2))
        val b = saved(create("B", firstHadithId = 2))

        assertEquals(setOf(a, b), collections.observeCollectionIdsOf(2).first())

        delete(a)

        assertEquals(setOf(b), collections.observeCollectionIdsOf(2).first())
    }

    // -- Deleting ---------------------------------------------------------------------------------------------------

    @Test
    fun delete_removesTheCollectionAndLeavesTheLibraryAndTheOthersAlone() = runBlocking {
        val doomed = saved(create("Doomed", firstHadithId = 1))
        val kept = saved(create("Kept", firstHadithId = 1))
        val libraryBefore = hadiths.getAll()

        delete(doomed)

        assertEquals(listOf("Kept"), titles())
        assertEquals(listOf(1L), collections.getHadithIds(kept))
        assertEquals(libraryBefore, hadiths.getAll())
        assertNull(collections.observe(doomed).first())
    }

    @Test
    fun delete_neverTouchesTheUsersProgress() = runBlocking {
        reviews.mastery[1] = record(1, matn = 12f, sanad = 4f)
        reviews.mastery[2] = record(2, matn = 3f, sanad = 1f)
        val before = reviews.mastery.toMap()
        val id = saved(create("With progress", firstHadithId = 1))
        membership(id, 2, member = true)

        delete(id)

        assertEquals(before, reviews.mastery)
        assertTrue(reviews.events.isEmpty())
    }

    // -- Reading a collection ---------------------------------------------------------------------------------------

    private fun record(id: Long, matn: Float, sanad: Float, due: Long = clock + 86_400_000L) = MasteryRecordEntity(
        id, matn, sanad, matnLastReviewedAt = clock, sanadLastReviewedAt = clock, nextReviewDue = due, repetitionCount = 2,
    )

    @Test
    fun detail_listsTheHadithsInTheOrderAddedEachWithItsProgress() = runBlocking {
        reviews.mastery[3] = record(3, matn = 2f, sanad = 1f, due = clock - 1)
        val id = saved(create("Reading"))
        listOf(3L, 5L).forEach { clock += 10; membership(id, it, member = true) }

        val shown = detail(id).first()!!

        assertEquals("Reading", shown.collection.title)
        assertEquals(listOf(3L, 5L), shown.hadiths.map { it.hadith.id })
        assertEquals(HadithProgress.LEARNING, shown.hadiths[0].progress)
        assertTrue("it is due in the user's schedule", shown.hadiths[0].isDue)
        assertEquals(HadithProgress.NEW, shown.hadiths[1].progress)
        assertFalse(shown.hadiths[1].isDue)
        assertEquals(1, shown.dueCount)
        assertFalse(shown.isEmpty)
    }

    @Test
    fun detail_anEmptyCollectionIsEmptyNotAnError() = runBlocking {
        val id = saved(create("Nothing yet"))

        val shown = detail(id).first()!!

        assertTrue(shown.isEmpty)
        assertEquals(0, shown.dueCount)
    }

    @Test
    fun detail_isNullOnceTheCollectionIsGone() = runBlocking {
        val id = saved(create("Short lived"))
        delete(id)

        assertNull(detail(id).first())
        assertNull(detail(424_242).first())
    }

    @Test
    fun detail_readsProgressWithoutWritingIt() = runBlocking {
        reviews.mastery[1] = record(1, matn = 5f, sanad = 2f)
        val readOnly = ObserveCollectionDetailUseCase(collections, ReadOnlyReviewRepository(reviews), scheduler) { clock }
        val id = saved(create("Reading", firstHadithId = 1))

        assertEquals(1, readOnly(id).first()!!.hadiths.size)
    }
}
