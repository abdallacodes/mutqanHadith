package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.graph.GraphEdge
import com.mutqan.hadith.domain.usecase.ChainHealth
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorChainHeatmapUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphUseCasesTest {

    private val sheikh = "شيخ المصنف"
    private val tabaa = "تابع التابعين"
    private val tabi = "تابعي"
    private val sahabi = "صحابي"

    // n1..n3 recur across chains; n3 is a pivot (مدار).
    private val n1 = narrator(1, "الحميدي", sheikh)
    private val n2 = narrator(2, "سفيان", tabaa)
    private val n3 = narrator(3, "الزهري", tabi).copy(isPivot = true)
    private val n4 = narrator(4, "أبو هريرة", sahabi)
    private val n5 = narrator(5, "مسدد", sheikh)
    private val n6 = narrator(6, "عروة", tabi)

    //   h1: n4 -> n3 -> n2 -> n1        h2: n4 -> n3 -> n2 -> n5        h3: n4 -> n6 -> n1
    private val h1 = hadith(1, listOf(n1, n2, n3, n4))
    private val h2 = hadith(2, listOf(n5, n2, n3, n4))
    private val h3 = hadith(3, listOf(n1, n6, n4))

    private val hadiths = FakeHadithRepository(listOf(h1, h2, h3))
    private val reviews = FakeReviewRepository()
    private val graphUseCase = GetNarratorGraphUseCase(hadiths, reviews)
    private val byNarrator = GetHadithsByNarratorUseCase(hadiths, reviews)

    private fun event(hadithId: Long, narratorId: Long, succeeded: Boolean) =
        NarratorReviewEventEntity(hadithId = hadithId, narratorId = narratorId, reviewedAt = 1L, succeeded = succeeded)

    private fun seedMastery(vararg hadithIds: Long) = hadithIds.forEach {
        reviews.mastery[it] = MasteryRecordEntity(it, 1f, 1f, 1L, 1L, 2L, 1)
    }

    // -- Graph ------------------------------------------------------------------------------

    @Test
    fun graph_hasOneNodePerDistinctNarratorInTheLibrary() = runBlocking {
        val graph = graphUseCase()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), graph.nodes.map { it.id })
        assertEquals(6, graph.nodes.size)
    }

    @Test
    fun graph_nodesCarryNameTierAndPivotFlag() = runBlocking {
        val graph = graphUseCase()
        assertEquals(listOf(3L), graph.nodes.filter { it.isPivot }.map { it.id })
        val zuhri = graph.node(3)!!
        assertEquals("الزهري", zuhri.name)
        assertEquals(tabi, zuhri.tier)
        assertTrue(zuhri.isPivot)
        assertFalse(graph.node(1)!!.isPivot)
    }

    @Test
    fun graph_edgesPointFromTeacherToStudent() = runBlocking {
        val edges = graphUseCase().edges
        // In h1 the chain list is [n1, n2, n3, n4]: n4 taught n3, n3 taught n2, n2 taught n1.
        assertTrue(GraphEdge(4, 3, 2) in edges)
        assertTrue(GraphEdge(2, 1, 1) in edges)
        assertTrue(edges.none { it.fromNarratorId == 1L && it.toNarratorId == 2L })
    }

    @Test
    fun graph_edgesSharedByHadithsAreMergedAndWeighted() = runBlocking {
        val edges = graphUseCase().edges
        assertEquals(
            listOf(
                GraphEdge(2, 1, 1), GraphEdge(2, 5, 1),
                GraphEdge(3, 2, 2),
                GraphEdge(4, 3, 2), GraphEdge(4, 6, 1),
                GraphEdge(6, 1, 1),
            ),
            edges,
        )
        // 3 chains with 3 + 3 + 2 steps = 8 steps, two of them shared: 6 distinct edges.
        assertEquals(6, edges.size)
        assertEquals(edges.size, edges.map { it.fromNarratorId to it.toNarratorId }.distinct().size)
    }

    @Test
    fun graph_aStepRepeatedInsideOneChainCountsOnce() = runBlocking {
        val odd = hadith(9, listOf(n1, n2, n1, n2)) // n2 -> n1 happens twice in this one chain
        val graph = GetNarratorGraphUseCase(FakeHadithRepository(listOf(odd)), reviews)()
        assertEquals(1, graph.edges.single { it.fromNarratorId == 2L && it.toNarratorId == 1L }.sharedHadithCount)
        assertEquals(1, graph.edges.single { it.fromNarratorId == 1L && it.toNarratorId == 2L }.sharedHadithCount)
    }

    @Test
    fun graph_ignoresASelfLink() = runBlocking {
        val graph = GetNarratorGraphUseCase(FakeHadithRepository(listOf(hadith(9, listOf(n1, n1)))), reviews)()
        assertEquals(1, graph.nodes.size)
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun graph_countsHadithsPerNarrator() = runBlocking {
        val graph = graphUseCase()
        assertEquals(3, graph.node(4)!!.hadithCount)
        assertEquals(2, graph.node(3)!!.hadithCount)
        assertEquals(1, graph.node(6)!!.hadithCount)
    }

    @Test
    fun graph_errorRateComesFromTheReviewHistoryOverAllHadiths() = runBlocking {
        reviews.events += listOf(
            event(1, 2, false), event(2, 2, false), event(1, 2, true), event(2, 2, true), // 2 of 4 failed
            event(1, 3, true),
        )
        val graph = graphUseCase()
        assertEquals(0.5f, graph.node(2)!!.errorRate, 0f)
        assertEquals(4, graph.node(2)!!.attempts)
        assertEquals(0f, graph.node(3)!!.errorRate, 0f)
        assertEquals(0, graph.node(1)!!.attempts)
        assertEquals(0f, graph.node(1)!!.errorRate, 0f) // never tested is not "never wrong"
    }

    @Test
    fun graph_studiedOnlyKeepsTheHadithsThatHaveBeenReviewed() = runBlocking {
        seedMastery(3)
        val graph = graphUseCase(studiedOnly = true)
        assertEquals(listOf(1L, 4L, 6L), graph.nodes.map { it.id })
        assertEquals(listOf(GraphEdge(4, 6, 1), GraphEdge(6, 1, 1)), graph.edges)
    }

    @Test
    fun graph_ofAnEmptyLibraryIsEmpty() = runBlocking {
        val graph = GetNarratorGraphUseCase(FakeHadithRepository(emptyList()), reviews)()
        assertTrue(graph.nodes.isEmpty())
        assertTrue(graph.edges.isEmpty())
    }

    @Test
    fun graph_neighborsAreTheLinkedNarrators() = runBlocking {
        val graph = graphUseCase()
        assertEquals(setOf(2L, 4L), graph.neighborsOf(3))
        assertEquals(setOf(2L, 6L), graph.neighborsOf(1))
        assertEquals(2, graph.edgesOf(3).size)
    }

    // -- Hadiths through a narrator -----------------------------------------------------------

    @Test
    fun byNarrator_listsEveryHadithOnTheChainWithThePosition() = runBlocking {
        val result = byNarrator(3)
        assertEquals(listOf(1L, 2L), result.map { it.hadithId })
        assertEquals(listOf(3, 3), result.map { it.position })
        assertEquals(listOf(4, 4), result.map { it.chainLength })
        assertEquals(listOf(1, 2), result.map { it.hadithNumber })
    }

    @Test
    fun byNarrator_positionIsCountedFromTheCollectorsTeacher() = runBlocking {
        assertEquals(1, byNarrator(1).first { it.hadithId == 1L }.position)
        assertEquals(3, byNarrator(4).first { it.hadithId == 3L }.position)
    }

    @Test
    fun byNarrator_carriesTheNarratorsOwnRecordInEachHadith() = runBlocking {
        seedMastery(1)
        reviews.events += listOf(event(1, 4, false), event(1, 4, true), event(1, 4, true), event(3, 4, false), event(1, 2, false))
        val result = byNarrator(4).associateBy { it.hadithId }

        assertEquals(1, result.getValue(1).errorCount)
        assertEquals(2, result.getValue(1).successCount)
        assertEquals(3, result.getValue(1).attempts)
        assertTrue(result.getValue(1).isStudied)
        assertEquals(1, result.getValue(3).errorCount)
        assertFalse(result.getValue(3).isStudied)
        assertEquals(0, result.getValue(2).attempts)
    }

    @Test
    fun byNarrator_unknownNarratorHasNoHadiths() = runBlocking {
        assertTrue(byNarrator(404).isEmpty())
    }

    // -- Chain health -------------------------------------------------------------------------

    @Test
    fun health_boundariesAreExact() {
        assertEquals(ChainHealth.STRONG, ChainHealth.of(errors = 14, successes = 86))
        assertEquals(ChainHealth.FAIR, ChainHealth.of(errors = 15, successes = 85)) // exactly 85% is not "above"
        assertEquals(ChainHealth.FAIR, ChainHealth.of(errors = 40, successes = 60)) // exactly 60% is not "below"
        assertEquals(ChainHealth.FRAGILE, ChainHealth.of(errors = 41, successes = 59))
        assertEquals(ChainHealth.STRONG, ChainHealth.of(errors = 0, successes = 1))
        assertEquals(ChainHealth.FRAGILE, ChainHealth.of(errors = 1, successes = 0))
        assertEquals(ChainHealth.FAIR, ChainHealth.of(errors = 1, successes = 3)) // 75%
    }

    @Test
    fun health_aNarratorNeverTestedIsUntested_andTheWeakestLinkIsAlwaysFragile() {
        assertEquals(ChainHealth.UNTESTED, ChainHealth.of(0, 0))
        assertEquals(ChainHealth.FRAGILE, ChainHealth.of(errors = 1, successes = 99, isWeakestLink = true))
    }

    @Test
    fun heatmap_nodesCarryWhatTheChainViewNeedsToDraw() = runBlocking {
        reviews.events += listOf(event(1, 3, false), event(1, 3, false), event(1, 3, true))
        val map = GetNarratorChainHeatmapUseCase(hadiths, reviews)(1)

        assertEquals("جامع", map.collector)
        val zuhri = map.nodes.first { it.performance.narratorId == 3L }
        assertEquals(tabi, zuhri.tier)
        assertEquals("عن", zuhri.transmissionTerm)
        assertEquals(3, zuhri.attempts)
        assertEquals(ChainHealth.FRAGILE, zuhri.health)
        assertTrue(zuhri.isWeakestLink)
        assertEquals(ChainHealth.UNTESTED, map.nodes.first { it.performance.narratorId == 1L }.health)
    }
}
