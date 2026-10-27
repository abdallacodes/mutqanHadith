package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.narrator.bioParagraphs
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorPageUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a narrator's own page is made of: who he is, what was written about him, and where he stands in the library. */
class NarratorPageUseCaseTest {

    private val zuhri = narrator(3, "الزهري", tier = "تابعي").copy(isPivot = true)
    private val companion = narrator(4, "أبو هريرة", tier = "صحابي")
    private val unused = narrator(9, "راو غير مستعمل")
    private val reviews = FakeReviewRepository()
    private val library = FakeHadithRepository(
        listOf(hadith(1, listOf(zuhri, companion)), hadith(2, listOf(zuhri)), hadith(3, listOf(companion))),
    )

    private fun page(vararg rows: com.mutqan.hadith.data.entity.NarratorEntity) =
        GetNarratorPageUseCase(FakeNarratorRepository(rows.toList()), reviews, GetHadithsByNarratorUseCase(library, reviews))

    private fun event(hadithId: Long, narratorId: Long, succeeded: Boolean) =
        NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = 1L, succeeded = succeeded)

    @Test
    fun aNarratorsPageHasHisNameTierAndTheHadithsThroughHim() = runBlocking {
        val page = page(zuhri, companion, unused)(3)!!

        assertEquals("الزهري", page.node.name)
        assertEquals("تابعي", page.node.tier)
        assertTrue(page.node.isPivot)
        assertEquals(listOf(1L, 2L), page.hadiths.map { it.hadithId })
        assertEquals(2, page.node.hadithCount)
        assertTrue(page.bio.isEmpty())
    }

    @Test
    fun hisRecordIsTheUsersTallyOverHisRows() = runBlocking {
        reviews.events += listOf(event(1, 3, false), event(1, 3, true), event(2, 3, true), event(1, 4, false))

        val node = page(zuhri, companion)(3)!!.node

        assertEquals(1, node.errorCount)
        assertEquals(2, node.successCount)
    }

    @Test
    fun whatAnAdminWroteComesInParagraphs() = runBlocking {
        val written = zuhri.copy(bio = "الأولى.\n\nالثانية.\r\n \r\nالثالثة.\n\n")

        assertEquals(listOf("الأولى.", "الثانية.", "الثالثة."), page(written, companion)(3)!!.bio)
    }

    @Test
    fun anyRowOfTheSameManLandsOnOnePageWithTheBioOfWhicheverRowHasIt() = runBlocking {
        val kept = narrator(60, "عمر بن الخطاب", tier = "صحابي").copy(bio = "نبذة الأصل")
        val duplicate = narrator(61, "ابن الخطاب", tier = "صحابي").copy(sameAsId = 60)

        val fromDuplicate = page(kept, duplicate)(61)!!
        val fromKept = page(kept, duplicate)(60)!!

        assertEquals(60L, fromDuplicate.node.id)
        assertEquals(fromKept.node.id, fromDuplicate.node.id)
        assertEquals(listOf("نبذة الأصل"), fromDuplicate.bio)
    }

    @Test
    fun aNarratorNoChainUsesYetStillHasAPage() = runBlocking {
        val page = page(zuhri, unused)(9)!!

        assertEquals("راو غير مستعمل", page.node.name)
        assertTrue(page.hadiths.isEmpty())
        assertEquals(0, page.node.hadithCount)
    }

    @Test
    fun anIdNotInTheCatalogHasNoPage() = runBlocking {
        assertNull(page(zuhri)(999))
        assertNotNull(page(zuhri)(3))
    }

    @Test
    fun theParagraphSplitIgnoresBlankText() {
        assertTrue(bioParagraphs("").isEmpty())
        assertTrue(bioParagraphs("\n\n  \n").isEmpty())
        assertEquals(listOf("سطر\nواحد"), bioParagraphs("  سطر\nواحد  "))
    }
}
