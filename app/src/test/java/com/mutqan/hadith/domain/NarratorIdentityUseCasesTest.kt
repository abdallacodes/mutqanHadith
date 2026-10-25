package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.drill.MissingLinkRoundFactory
import com.mutqan.hadith.domain.narrator.NarratorDirectory
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetMasteryAnalyticsUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.ObserveStudyDashboardUseCase
import java.time.ZoneOffset
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the canonical identity changes for everything that counts a narrator: the network has one node for a man the
 * catalog spells two ways, his recall tally is the sum of his rows, and a drill never offers him as his own distractor.
 */
class NarratorIdentityUseCasesTest {

    private val companion = "صحابي"

    // Ibn 'Abbas under both of his names, al-Zuhri under both of his, and Nafi' who has only one.
    private val ibnAbbas = narrator(42, "ابن عباس", companion)
    private val abdAllahIbnAbbas = narrator(58, "عبد الله بن عباس", companion)
    private val ibnShihab = narrator(27, "ابن شهاب", "راوٍ")
    private val zuhri = narrator(40, "الزهري", "راوٍ")
    private val nafi = narrator(5, "نافع", "راوٍ")
    private val umar = narrator(6, "عمر بن الخطاب", companion)

    private val library = listOf(
        hadith(1, listOf(ibnShihab, ibnAbbas), level = 3),
        hadith(2, listOf(zuhri, abdAllahIbnAbbas), level = 3),
        hadith(3, listOf(nafi, abdAllahIbnAbbas), level = 3),
        hadith(4, listOf(umar), level = 1),
    )
    private val hadiths = FakeHadithRepository(library)
    private val reviews = FakeReviewRepository().also { r -> hadiths.seenIds = { r.mastery.keys } }
    private var eventIds = 0L

    private fun event(hadithId: Long, narrator: Long, succeeded: Boolean) {
        reviews.events += NarratorReviewEventEntity(
            id = ++eventIds, hadithId = hadithId, narratorId = narrator, reviewedAt = 1_700_000_000_000L, succeeded = succeeded,
        )
    }

    // -- The network ------------------------------------------------------------------------

    @Test
    fun graph_oneNodeForEveryManNotForEveryRow() = runTest {
        val graph = GetNarratorGraphUseCase(hadiths, reviews)()

        assertEquals(listOf(5L, 6L, 40L, 58L), graph.nodes.map { it.id })
        assertEquals(listOf("نافع", "عمر بن الخطاب", "الزهري", "عبد الله بن عباس"), graph.nodes.map { it.name })
        assertEquals(listOf(1, 1, 2, 3), graph.nodes.map { it.hadithCount })
        assertNull("the alias rows are no nodes of their own", graph.node(42))
        assertNull(graph.node(27))
    }

    @Test
    fun graph_aStepSharedByTwoSpellingsIsOneEdgeWithBothHadiths() = runTest {
        val graph = GetNarratorGraphUseCase(hadiths, reviews)()

        // Ibn 'Abbas -> al-Zuhri is in hadith 1 (Ibn Shihab / Ibn 'Abbas) and hadith 2 (al-Zuhri / 'Abd Allah ibn 'Abbas).
        assertEquals(2, graph.edges.single { it.fromNarratorId == 58L && it.toNarratorId == 40L }.sharedHadithCount)
        assertEquals(1, graph.edges.single { it.fromNarratorId == 58L && it.toNarratorId == 5L }.sharedHadithCount)
        assertEquals(2, graph.edges.size)
        assertEquals(setOf(40L, 5L), graph.neighborsOf(58))
    }

    @Test
    fun graph_aMansRecallTallyIsTheSumOfHisRows() = runTest {
        event(1, 42, succeeded = false)
        event(2, 58, succeeded = false)
        event(3, 58, succeeded = true)

        val node = GetNarratorGraphUseCase(hadiths, reviews)().node(58)!!

        assertEquals(2, node.errorCount)
        assertEquals(1, node.successCount)
    }

    @Test
    fun graph_aNodeCarriesTheOtherNamesOfTheMan() = runTest {
        val graph = GetNarratorGraphUseCase(hadiths, reviews)()

        assertEquals(listOf("ابن عباس"), graph.node(58)!!.aliases)
        assertEquals(listOf("ابن شهاب"), graph.node(40)!!.aliases)
        assertEquals(emptyList<String>(), graph.node(5)!!.aliases)
    }

    @Test
    fun graph_aManKeepsHisIdWhateverHadithsTakePart() = runTest {
        reviews.mastery[1] = MasteryRecordEntity(1, 1f, 0f, 1_700_000_000_000L, null, 1_700_000_000_000L, 1)

        val graph = GetNarratorGraphUseCase(hadiths, reviews)(studiedOnly = true)

        // Only hadith 1 is studied: Ibn 'Abbas as spelled there is still the man the whole library calls 58.
        assertEquals(listOf(40L, 58L), graph.nodes.map { it.id })
        assertEquals(1, graph.node(58)!!.hadithCount)
    }

    @Test
    fun graph_aNoOpEdgeBetweenTwoSpellingsOfOneManIsDropped() = runTest {
        // A chain that names the same man twice in a row does not make him his own teacher.
        val twice = FakeHadithRepository(listOf(hadith(1, listOf(ibnShihab, zuhri), level = 3)))

        val graph = GetNarratorGraphUseCase(twice, FakeReviewRepository())()

        assertEquals(1, graph.nodes.size)
        assertTrue(graph.edges.isEmpty())
    }

    // -- A man's hadiths --------------------------------------------------------------------

    @Test
    fun hadithsOfAMan_areThoseOfAllHisSpellingsWithTheirTalliesAdded() = runTest {
        event(1, 42, succeeded = false)
        event(2, 58, succeeded = true)

        val through = GetHadithsByNarratorUseCase(hadiths, reviews)(58)

        assertEquals(listOf(1L, 2L, 3L), through.map { it.hadithId })
        assertEquals(listOf(2, 2, 2), through.map { it.position })
        assertEquals(1, through[0].errorCount)
        assertEquals(1, through[1].successCount)
        assertEquals(0, through[2].attempts)
    }

    @Test
    fun hadithsOfAMan_canBeAskedForByAnyOfHisRowsAndAnUnknownOneHasNone() = runTest {
        val useCase = GetHadithsByNarratorUseCase(hadiths, reviews)

        assertEquals(useCase(58).map { it.hadithId }, useCase(42).map { it.hadithId })
        assertEquals(emptyList<Long>(), useCase(404).map { it.hadithId })
    }

    // -- The weakest narrators --------------------------------------------------------------

    @Test
    fun weakestNarrators_oneEntryPerManWithTheRowsAddedUp() = runTest {
        // Each row alone is a single miss, too few to say anything; the man they name has missed twice in two tries.
        event(1, 42, succeeded = false)
        event(2, 58, succeeded = false)
        val analytics = GetMasteryAnalyticsUseCase(
            hadiths, FakeNarratorRepository(library.flatMap { h -> h.sanad.map { it.narrator } }.distinctBy { it.id }),
            reviews, DualDecayScheduler(), { 1_700_000_000_000L }, { ZoneOffset.UTC },
        )

        val weakest = analytics().weakestNarrators

        assertEquals(1, weakest.size)
        assertEquals(58L, weakest.single().narratorId)
        assertEquals("عبد الله بن عباس", weakest.single().name)
        assertEquals(2, weakest.single().errorCount)
        assertEquals(2, weakest.single().attempts)
    }

    // -- The drills -------------------------------------------------------------------------

    @Test
    fun missingLink_noOptionIsTheAnswerUnderAnotherName() {
        val everyone = library.flatMap { h -> h.sanad.map { it.narrator } }.distinctBy { it.id }
        // The chain of hadith 2 names Ibn 'Abbas as «عبد الله بن عباس»; row 42 is the same man.
        repeat(60) { seed ->
            val round = MissingLinkRoundFactory.create(library[1], everyone, Random(seed), optionCount = 4)

            val options = round.options.map { it.id }
            assertFalse("seed $seed offered Ibn 'Abbas beside himself: $options", 42L in options)
            assertFalse("seed $seed offered al-Zuhri beside himself: $options", 27L in options)
            assertEquals(options.size, options.toSet().size)
        }
    }

    @Test
    fun missingLink_theDistractorsAreDistinctMen() {
        val pool = listOf(
            narrator(1, "ابن عمر", companion), narrator(2, "عبد الله بن عمر", companion),
            narrator(3, "عمر بن الخطاب", companion), narrator(4, "أنس", companion), narrator(5, "أنس بن مالك", companion),
        )
        val chain = hadith(9, listOf(narrator(7, "نافع", "راوٍ"), narrator(8, "عبد الله بن عباس", companion)), level = 3)
        val directory = NarratorDirectory.of(pool + chain.sanad.map { it.narrator })

        repeat(40) { seed ->
            val options = MissingLinkRoundFactory.create(chain, pool, Random(seed), optionCount = 4).options
            val people = options.map { directory.canonicalId(it.id) }
            assertEquals("seed $seed: $options", people.size, people.toSet().size)
            assertEquals(4, options.size)
        }
    }

    // -- The home ---------------------------------------------------------------------------

    @Test
    fun dashboard_namesTheNextCompanionByHisCanonicalName() = runTest {
        val repo = FakeHadithRepository(listOf(hadith(1, listOf(narrator(16, "ابن عمر", companion)), level = 1)))

        val dashboard = ObserveStudyDashboardUseCase(repo, FakeReviewRepository(), DualDecayScheduler(), { ZoneOffset.UTC }, { 0L })().first()

        assertEquals("عبد الله بن عمر", dashboard.nextToLearn!!.sahabiName)
        assertEquals("عبد الله بن عمر", dashboard.items.single().sahabiName)
    }
}
