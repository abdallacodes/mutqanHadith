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
 * Arba'in and 'Umdat al-Ahkam write differently are one man in the network. The table changes no row: the database,
 * the chains and the user's per-narrator history are exactly as they were.
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

    private val arbain = "الأربعون النووية"
    private val umdat = "عمدة الأحكام"
    private val bukhari = "صحيح البخاري"

    // -- The table against the catalog ------------------------------------------------------

    @Test
    fun everyNameInTheTableIsOneTheCatalogCarries() {
        val carried = rows.flatMap { listOf(NarratorNormalizer.key(it.primaryName), NarratorNormalizer.key(it.fullName)) }.toSet()
        // The orthography of a canonical name may be settled («العاص»); every other spelling is the catalog's own.
        val settled = setOf("عبد الله بن عمرو بن العاص")
        NarratorAliases.groups.forEach { group ->
            group.spellings.filter { it !in settled }.forEach {
                assertTrue("«$it» of «${group.name}» is in no catalog row", NarratorNormalizer.key(it) in carried)
            }
        }
    }

    @Test
    fun everyGroupMergesAtLeastTwoRowsOfTheCatalog() {
        NarratorAliases.groups.forEach { group ->
            val merged = directory.narrators.firstOrNull { it.name == group.name }
            assertTrue("«${group.name}» merges nothing in this catalog", merged != null && merged.memberIds.size >= 2)
        }
    }

    @Test
    fun theCatalogHoldsOneHundredAndTenPeopleInOneHundredAndFortyEightRows() {
        assertEquals(148, rows.size)
        assertEquals(110, directory.narrators.size)
        assertEquals(rows.mapTo(mutableSetOf()) { it.id }, directory.narrators.flatMap { it.memberIds }.toSet())
        assertEquals(148, directory.narrators.sumOf { it.memberIds.size })
        assertEquals(27, directory.narrators.count { it.memberIds.size > 1 })
    }

    @Test
    fun exactlyTheseRowsAreMerged() {
        val merged = directory.narrators.filter { it.memberIds.size > 1 }.associate { it.name to it.memberIds }

        assertEquals(
            mapOf(
                "أبو هريرة" to setOf(34L, 52L),
                "عبد الله بن عمر" to setOf(16L, 36L, 47L, 108L),
                "عمر بن الخطاب" to setOf(6L, 12L, 46L),
                "عبد الله بن عباس" to setOf(42L, 58L),
                "أنس بن مالك" to setOf(21L, 54L, 72L),
                "عائشة" to setOf(50L, 74L),
                "جابر بن عبد الله" to setOf(61L, 86L, 109L),
                "أبو سعيد الخدري" to setOf(29L, 70L),
                "عبد الله بن مسعود" to setOf(49L, 55L, 101L),
                "النعمان بن بشير" to setOf(25L, 51L),
                "عبد الله بن عمرو بن العاص" to setOf(71L, 73L, 118L),
                "أبو مسعود الأنصاري" to setOf(59L, 89L),
                "أبو موسى الأشعري" to setOf(107L, 112L, 136L),
                "أبو قتادة الأنصاري" to setOf(76L, 93L, 99L),
                "أبو ذر الغفاري" to setOf(57L, 63L, 64L),
                "عبد الله بن زيد بن عاصم" to setOf(80L, 113L),
                "أم عطية الأنصارية" to setOf(106L, 110L, 130L),
                "سهل بن سعد الساعدي" to setOf(69L, 103L),
                "أبو ثعلبة الخشني" to setOf(68L, 142L),
                "عبد الله بن مالك ابن بحينة" to setOf(92L, 97L),
                "حذيفة بن اليمان" to setOf(77L, 146L),
                "سلمة بن الأكوع" to setOf(104L, 148L),
                "الزهري" to setOf(27L, 40L),
                "يحيى بن سعيد الأنصاري" to setOf(3L, 9L),
                "محمد بن إبراهيم التيمي" to setOf(4L, 10L),
                "علقمة بن وقاص الليثي" to setOf(5L, 11L),
                "عبيد الله بن عبد الله بن عتبة بن مسعود" to setOf(41L, 133L),
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
            "عبد الله بن عمر" to "عبد الله بن عمرو بن العاصي",
            "عبد الله بن عمر" to "عبد الله بن عمرو",
            "سفيان" to "أبو عمرو سفيان بن عبد الله",
            "يحيى" to "يحيى بن سعيد الأنصاري",
            "عبد الله" to "عبد الله بن مسلمة",
            "همام بن منبه" to "همام بن الحارث",
            "عقبة بن عامر" to "عقبة بن الحارث",
            "علي بن أبي طالب" to "أبو محمد الحسن بن علي بن أبي طالب",
            "أبو بكر الصديق" to "أبو بكرة",
            "جندب بن عبد الله البجلي" to "أبو ذر جندب بن جنادة",
            "عبد الله بن عمرو" to "جابر بن عبد الله",
            "ابن شهاب" to "سالم",
        )
        apart.forEach { (a, b) ->
            assertNotEquals("«$a» and «$b»", directory.canonicalId(row(a).id), directory.canonicalId(row(b).id))
        }
    }

    @Test
    fun theTableChangesNoRowAndNoReference() {
        assertEquals(3, SeedLoader.CATALOG_VERSION)
        // Every edge still points at the row it pointed at, and every canonical id is such a row.
        assertEquals(seed.edges.map { it.narratorId }.toSet(), library.flatMap { h -> h.edges.map { it.narrator.id } }.toSet())
        directory.narrators.forEach { assertTrue(it.id in narratorById && it.id in it.memberIds) }
    }

    // -- One man across the books -----------------------------------------------------------

    private fun booksOf(rowIds: Set<Long>) =
        library.filter { h -> h.edges.any { it.narrator.id in rowIds } }.map { it.hadith.sourceBook }.toSet()

    @Test
    fun theCompanionsOfTheArbainAndOfUmdatAlAhkamAreTheSameMen() {
        val inBoth = listOf(
            "أبو هريرة", "عبد الله بن عمر", "عبد الله بن عباس", "أنس بن مالك", "عائشة", "جابر بن عبد الله", "أبو سعيد الخدري",
            "عبد الله بن مسعود", "النعمان بن بشير", "عبد الله بن عمرو بن العاص", "أبو مسعود الأنصاري", "أبو ذر الغفاري",
            "سهل بن سعد الساعدي", "أبو ثعلبة الخشني", "عمر بن الخطاب",
        )
        inBoth.forEach { name ->
            val books = booksOf(directory.narrators.first { it.name == name }.memberIds)
            assertTrue("«$name» is in $books", arbain in books && umdat in books)
        }
    }

    @Test
    fun mergingTheSpellingsMakesTheSharedCompanionsVisible() {
        fun sharing(groups: Collection<Set<Long>>) = groups.count { booksOf(it).containsAll(listOf(arbain, umdat)) }

        val sharedRows = sharing(rows.map { setOf(it.id) })
        val sharedPeople = sharing(directory.narrators.map { it.memberIds })

        // Row by row only a few Companions are found in both books, because each book spells them its own way.
        assertTrue("$sharedPeople people against $sharedRows rows", sharedPeople > 2 * sharedRows)
        assertEquals(15, sharedPeople)
    }

    @Test
    fun theParallelBukhariChainsOfTheIntentionHadithMeetAtEveryLink() {
        val first = library.single { it.hadith.sourceBook == bukhari && it.hadith.hadithNumber == 1 }.sanad.map { directory.canonicalId(it.narrator.id) }
        val second = library.single { it.hadith.sourceBook == bukhari && it.hadith.hadithNumber == 54 }.sanad.map { directory.canonicalId(it.narrator.id) }

        // Both end «يحيى بن سعيد ← محمد بن إبراهيم ← علقمة بن وقاص ← عمر»: the last four links are the same four men.
        assertEquals(first.takeLast(4), second.takeLast(4))
        assertEquals(8, (first + second).toSet().size)
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
    fun oneCompanionGivesOneHintWhicheverBookIsAsked() {
        val hintsByMan = library.groupBy { directory.canonicalId(it.sanad.last().narrator.id) }.mapValues { (_, hadiths) ->
            hadiths.map { companionHint(NarratorAliases.canonicalName(it.hadith.sahabi)) }.toSet()
        }

        hintsByMan.forEach { (id, hints) -> assertEquals("narrator $id: $hints", 1, hints.size) }
        assertTrue(hintsByMan.values.flatten().none { it.isEmpty() })
        // «ابن عمر» (Arba'in) and «عبد الله بن عمر» ('Umdat) used to hint «ا…» and «ع…»: now both are «ع…».
        assertEquals(setOf("عـ…"), hintsByMan.getValue(directory.canonicalId(row("ابن عمر").id)))
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

        assertEquals(110, graph.nodes.size)
        assertEquals(graph.nodes.map { it.id }.toSet(), directory.narrators.map { it.id }.toSet())
        assertTrue(graph.edges.all { graph.node(it.fromNarratorId) != null && graph.node(it.toNarratorId) != null })
        // Abu Hurayra is narrated under two spellings and is one node holding the hadiths of both.
        val abuHurayra = graph.node(person("أبو هريرة").id)!!
        assertEquals(library.count { h -> h.edges.any { it.narrator.id in setOf(34L, 52L) } }, abuHurayra.hadithCount)
        assertEquals("أبو هريرة عبد الرحمن بن صخر", abuHurayra.fullName)
        assertEquals(1, graph.nodes.count { it.name == "أبو هريرة" })
    }
}
