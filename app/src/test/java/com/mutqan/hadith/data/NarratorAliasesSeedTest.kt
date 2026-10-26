package com.mutqan.hadith.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadEdgeWithNarrator
import com.mutqan.hadith.data.seed.SeedLoader
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.graph.TierRank
import com.mutqan.hadith.domain.narrator.NarratorAliases
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.narrator.NarratorNormalizer
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.ui.review.companionHint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The curated alias table held to the catalog it describes: every name in it is one the catalog really carries, nothing
 * is merged that should stay apart, no duplicate is left that the table could have caught, and the Companions the
 * Forty Hadith write in two ways are one man in the network. The table changes no row: the database, the chains and
 * the user's per-narrator history are exactly as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NarratorAliasesSeedTest {

    private val seed = SeedLoader.load(ApplicationProvider.getApplicationContext<Context>())
    private val rows = seed.narrators
    private val rowByName = rows.associateBy { it.primaryName }
    private val directory = NarratorDirectory.of(rows)

    private val narratorById = rows.associateBy { it.id }
    private val library: List<HadithWithFullSanad> = seed.hadiths.map { h ->
        HadithWithFullSanad(
            h,
            seed.edges.filter { it.hadithId == h.id }.map { SanadEdgeWithNarrator(it, narratorById.getValue(it.narratorId)) },
        )
    }

    private fun row(name: String) = rowByName.getValue(name)

    private fun person(name: String) = directory.canonicalOf(row(name).id)!!

    // -- The table against the catalog ------------------------------------------------------

    @Test
    fun theGroupsTheCatalogTouchesAreTheFifteenOfTheFortyHadithAndTheRestAreInert() {
        // The table also holds the groups of the books retired in catalog 4; they name no row today and change nothing.
        val live = directory.narrators.map { it.name }.toSet().intersect(NarratorAliases.groups.map { it.name }.toSet())
        assertEquals(
            setOf(
                "أبو هريرة", "عبد الله بن عمر", "عمر بن الخطاب", "عبد الله بن عباس", "أنس بن مالك", "عائشة", "جابر بن عبد الله",
                "أبو سعيد الخدري", "عبد الله بن مسعود", "النعمان بن بشير", "عبد الله بن عمرو بن العاص", "أبو مسعود الأنصاري",
                "أبو ذر الغفاري", "سهل بن سعد الساعدي", "أبو ثعلبة الخشني",
            ),
            live,
        )
        // Every row a live group names is filed under that group's name, so the app shows and hints from one clean name.
        NarratorAliases.groups.filter { it.name in live }.forEach { group ->
            rows.filter { NarratorAliases.groupOf(it.primaryName) === group }.forEach {
                assertEquals(group.name, directory.canonicalOf(it.id)!!.name)
            }
        }
    }

    @Test
    fun theCatalogHoldsTwentyThreePeopleInThirtyTwoRows() {
        assertEquals(32, rows.size)
        assertEquals(23, directory.narrators.size)
        assertEquals(rows.mapTo(mutableSetOf()) { it.id }, directory.narrators.flatMap { it.memberIds }.toSet())
        assertEquals(32, directory.narrators.sumOf { it.memberIds.size })
        assertEquals(8, directory.narrators.count { it.memberIds.size > 1 })
    }

    @Test
    fun exactlyTheseRowsAreMerged() {
        val merged = directory.narrators.filter { it.memberIds.size > 1 }.associate { it.name to it.memberIds }

        assertEquals(
            mapOf(
                "أبو هريرة" to setOf(34L, 52L),
                "عبد الله بن عمر" to setOf(16L, 47L),
                "عمر بن الخطاب" to setOf(12L, 46L),
                "عبد الله بن عباس" to setOf(42L, 58L),
                "أنس بن مالك" to setOf(54L, 72L),
                "أبو سعيد الخدري" to setOf(29L, 70L),
                "عبد الله بن مسعود" to setOf(49L, 55L),
                "أبو ذر الغفاري" to setOf(57L, 63L, 64L),
            ),
            merged,
        )
    }

    @Test
    fun noDuplicateIsLeftThatTheNormalizerCouldHaveCaught() {
        // Rows whose names are one name in different spelling must be one person; if this fails, a group is missing.
        listOf<(com.mutqan.hadith.data.entity.NarratorEntity) -> String>({ it.primaryName }, { it.fullName }).forEach { nameOf ->
            rows.groupBy { NarratorNormalizer.key(nameOf(it)) }.filterValues { it.size > 1 }.forEach { (key, same) ->
                assertEquals("«$key» is $same", 1, same.map { directory.canonicalId(it.id) }.toSet().size)
            }
        }
        // A Companion's short name that is another Companion's full name is the same man (the table's own grounds).
        val companions = rows.filter { TierRank.of(it.tier) == 0 }
        val byFullName = companions.groupBy { NarratorNormalizer.key(it.fullName) }
        companions.forEach { short ->
            byFullName[NarratorNormalizer.key(short.primaryName)]?.forEach { full ->
                assertEquals("«${short.primaryName}»", directory.canonicalId(short.id), directory.canonicalId(full.id))
            }
        }
    }

    @Test
    fun menWhoOnlyLookAlikeAreNotMerged() {
        val apart = listOf(
            "ابن عمر" to "أبو محمد عبد الله بن عمرو بن العاص",
            "ابن عباس" to "ابن مسعود",
            "عمر" to "أبو عمرو سفيان بن عبد الله",
            "أبو ذر" to "أبو ثعلبة الخشني جرثوم بن ناشر",
            "أبو سعيد الخدري" to "أبو مسعود عقبة بن عمرو الأنصاري البدري",
            "أبو محمد الحسن بن علي بن أبي طالب" to "عمر",
            "أبو عبد الله جابر بن عبد الله الأنصاري" to "أبو عبد الله النعمان بن بشير",
        )
        apart.forEach { (a, b) ->
            assertNotEquals("«$a» and «$b»", directory.canonicalId(row(a).id), directory.canonicalId(row(b).id))
        }
    }

    @Test
    fun theTableChangesNoRowAndNoReference() {
        // Every edge still points at the row it pointed at, and every canonical id is such a row.
        assertEquals(seed.edges.map { it.narratorId }.toSet(), library.flatMap { h -> h.edges.map { it.narrator.id } }.toSet())
        directory.narrators.forEach { assertTrue(it.id in narratorById && it.id in it.memberIds) }
    }

    // -- One man under two spellings ----------------------------------------------------------

    private fun numbersOf(rowIds: Set<Long>) =
        library.filter { h -> h.edges.any { it.narrator.id in rowIds } }.map { it.hadith.hadithNumber }.sorted()

    @Test
    fun aCompanionWrittenInTwoWaysIsOneManHoldingTheHadithsOfBoth() {
        // Abu Hurayra is «أبو هريرة» in eight hadiths and «أبو هريرة عبد الرحمن بن صخر» in hadith 9.
        val abuHurayra = directory.narrators.first { it.name == "أبو هريرة" }.memberIds
        assertEquals(listOf(9, 10, 12, 15, 16, 26, 35, 36, 38), numbersOf(abuHurayra))
        // Ibn 'Umar is «ابن عمر» (8 and 40) and «أبو عبد الرحمن عبد الله بن عمر بن الخطاب» (3).
        assertEquals(listOf(3, 8, 40), numbersOf(directory.narrators.first { it.name == "عبد الله بن عمر" }.memberIds))
    }

    // -- The hint ---------------------------------------------------------------------------

    @Test
    fun everyHadithHintsFromTheCanonicalNameOfItsCompanion() {
        library.forEach { h ->
            val last = h.sanad.last().narrator
            assertEquals("hadith ${h.hadith.id}", directory.canonicalOf(last.id)!!.name, NarratorAliases.canonicalName(h.hadith.sahabi))
        }
    }

    @Test
    fun oneCompanionGivesOneHintWhicheverSpellingIsAsked() {
        val hintsByMan = library.groupBy { directory.canonicalId(it.sanad.last().narrator.id) }.mapValues { (_, hadiths) ->
            hadiths.map { companionHint(NarratorAliases.canonicalName(it.hadith.sahabi)) }.toSet()
        }

        hintsByMan.forEach { (id, hints) -> assertEquals("narrator $id: $hints", 1, hints.size) }
        assertTrue(hintsByMan.values.flatten().none { it.isEmpty() })
        // «ابن عمر» and «أبو عبد الرحمن عبد الله بن عمر بن الخطاب» would hint «ا…» and «أ…»: both are «ع…».
        assertEquals(setOf("عـ…"), hintsByMan.getValue(directory.canonicalId(row("ابن عمر").id)))
        assertEquals(setOf("عـ…"), hintsByMan.getValue(directory.canonicalId(row("أبو عبد الرحمن عبد الله بن عمر بن الخطاب").id)))
        assertEquals(setOf("أ…"), hintsByMan.getValue(directory.canonicalId(row("أبو هريرة").id)))
    }

    @Test
    fun theHintsOfAllHadithsAreOneLetterOfTheirCompanionsName() {
        library.forEach { h ->
            val name = NarratorAliases.canonicalName(h.hadith.sahabi)
            assertTrue(h.hadith.id.toString(), companionHint(name).startsWith(name.first().toString()))
        }
    }

    // -- The network ------------------------------------------------------------------------

    @Test
    fun theNetworkOfTheCatalogHasOneNodePerMan() = runTest {
        val graph = GetNarratorGraphUseCase(FakeHadithRepository(library), FakeReviewRepository())()

        assertEquals(23, graph.nodes.size)
        assertEquals(graph.nodes.map { it.id }.toSet(), directory.narrators.map { it.id }.toSet())
        assertTrue(graph.edges.all { graph.node(it.fromNarratorId) != null && graph.node(it.toNarratorId) != null })
        // Abu Hurayra is narrated under two spellings and is one node holding the hadiths of both.
        val abuHurayra = graph.node(person("أبو هريرة").id)!!
        assertEquals(library.count { h -> h.edges.any { it.narrator.id in setOf(34L, 52L) } }, abuHurayra.hadithCount)
        assertEquals("أبو هريرة عبد الرحمن بن صخر", abuHurayra.fullName)
        assertEquals(1, graph.nodes.count { it.name == "أبو هريرة" })
    }
}
