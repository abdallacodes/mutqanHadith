package com.mutqan.hadith.domain

import com.mutqan.hadith.domain.graph.GraphEdge
import com.mutqan.hadith.domain.graph.GraphLayout
import com.mutqan.hadith.domain.graph.GraphLayoutEngine
import com.mutqan.hadith.domain.graph.NarratorGraph
import com.mutqan.hadith.domain.graph.NarratorNode
import com.mutqan.hadith.domain.graph.TierRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLayoutTest {

    private val engine = GraphLayoutEngine()

    private fun node(id: Long, tier: String, pivot: Boolean = false) =
        NarratorNode(id, "راوي $id", "راوي $id", tier, pivot, errorCount = 0, successCount = 0, hadithCount = 1)

    private fun graph(nodes: List<NarratorNode>, vararg edges: Pair<Long, Long>) =
        NarratorGraph(nodes, edges.map { GraphEdge(it.first, it.second, 1) })

    /** A realistic network: Sahabi -> two Tabi'in -> Tabi' al-Tabi'in -> collector's teachers. */
    private val sample = graph(
        listOf(
            node(1, "صحابي", pivot = true), node(2, "صحابي"),
            node(3, "تابعي", pivot = true), node(4, "تابعي"), node(5, "تابعي"),
            node(6, "تابع التابعين"), node(7, "تابع التابعين"),
            node(8, "شيخ المصنف"), node(9, "شيخ المصنف"), node(10, "شيخ المصنف"),
        ),
        1L to 3L, 1L to 4L, 2L to 5L, 3L to 6L, 4L to 6L, 5L to 7L, 6L to 8L, 6L to 9L, 7L to 10L,
    )

    private fun GraphLayout.p(id: Long) = placements.getValue(id)

    @Test
    fun everyNodeIsPlaced() {
        val layout = engine.layout(sample)
        assertEquals(sample.nodes.map { it.id }.toSet(), layout.placements.keys)
    }

    @Test
    fun anEmptyGraphHasAnEmptyLayout() {
        assertEquals(GraphLayout.Empty, engine.layout(NarratorGraph.Empty))
    }

    @Test
    fun generationsRunTopToBottom() {
        val layout = engine.layout(sample)
        assertTrue(layout.p(1).y < layout.p(3).y)
        assertTrue(layout.p(3).y < layout.p(6).y)
        assertTrue(layout.p(6).y < layout.p(8).y)
        assertEquals(layout.p(3).y, layout.p(4).y, 0f) // same generation, same row
        assertEquals(listOf("صحابي", "تابعي", "تابع التابعين", "شيخ المصنف"), layout.bands.map { it.tier })
    }

    @Test
    fun everyTeacherSitsAboveTheStudent() {
        val layout = engine.layout(sample)
        sample.edges.forEach { e ->
            assertTrue("edge $e", layout.p(e.fromNarratorId).y < layout.p(e.toNarratorId).y)
        }
    }

    @Test
    fun aChainInsideOneGenerationIsStackedSoNoEdgeIsHorizontal() {
        val g = graph(
            listOf(node(1, "صحابي"), node(2, "تابعي"), node(3, "تابعي"), node(4, "تابعي"), node(5, "شيخ المصنف")),
            1L to 2L, 2L to 3L, 3L to 4L, 4L to 5L,
        )
        val layout = engine.layout(g)
        g.edges.forEach { e -> assertTrue("edge $e", layout.p(e.fromNarratorId).y < layout.p(e.toNarratorId).y) }
        assertEquals(1, layout.bands.count { it.tier == "تابعي" })
        // The three Tabi'in share one band that is taller than a single row.
        val band = layout.bands.first { it.tier == "تابعي" }
        assertTrue(layout.p(2).y >= band.top && layout.p(4).y <= band.bottom)
    }

    @Test
    fun nodesInARowNeverOverlap() {
        val layout = engine.layout(sample)
        layout.placements.values.groupBy { it.level }.values.forEach { row ->
            val sorted = row.sortedBy { it.left }
            sorted.zipWithNext().forEach { (a, b) -> assertTrue("${a.id} overlaps ${b.id}", a.left + a.width <= b.left) }
        }
    }

    @Test
    fun everythingFitsInsideTheCanvas() {
        val layout = engine.layout(sample)
        layout.placements.values.forEach {
            assertTrue(it.left >= 0f && it.left + it.width <= layout.width)
            assertTrue(it.top >= 0f && it.top + it.height <= layout.height)
        }
    }

    @Test
    fun pivotsAreDrawnLarger() {
        val layout = engine.layout(sample)
        assertTrue(layout.p(1).width > layout.p(2).width)
        assertTrue(layout.p(3).height > layout.p(4).height)
    }

    @Test
    fun aPivotIsPulledTowardsTheMiddleOfItsRow() {
        // The pivot's only link is to the leftmost chain; without centering it would hug that side.
        val nodes = listOf(
            node(1, "صحابي"), node(2, "صحابي"), node(3, "صحابي"), node(4, "صحابي"),
            node(5, "تابعي", pivot = true), node(6, "تابعي"), node(7, "تابعي"), node(8, "تابعي"),
        )
        val g = graph(nodes, 1L to 5L, 2L to 6L, 3L to 7L, 4L to 8L)
        val layout = engine.layout(g)
        val rowCenter = layout.width / 2
        val pivotOffset = kotlin.math.abs(layout.p(5).x - rowCenter)
        val maxOffset = listOf(5L, 6L, 7L, 8L).maxOf { kotlin.math.abs(layout.p(it).x - rowCenter) }
        assertTrue("pivot offset $pivotOffset vs row spread $maxOffset", pivotOffset < maxOffset)
    }

    @Test
    fun aNarratorOfUnknownTierFollowsHisTeacher() {
        val g = graph(listOf(node(1, "صحابي"), node(2, "غير معروف"), node(3, "تابعي")), 1L to 2L, 2L to 3L)
        val layout = engine.layout(g)
        // Unknown tier joins the Sahabi's generation but is stacked under him; the Tabi'i stays below both.
        assertTrue(layout.p(1).y < layout.p(2).y)
        assertTrue(layout.p(2).y < layout.p(3).y)
        assertNotNull(layout.placements[2])
    }

    @Test
    fun malformedCyclesDoNotHangOrDropNodes() {
        val g = graph(
            listOf(node(1, "تابعي"), node(2, "تابعي"), node(3, "غير معروف"), node(4, "غير معروف")),
            1L to 2L, 2L to 1L, 3L to 4L, 4L to 3L,
        )
        val layout = engine.layout(g)
        assertEquals(setOf(1L, 2L, 3L, 4L), layout.placements.keys)
    }

    @Test
    fun layoutIsDeterministic() {
        assertEquals(engine.layout(sample), engine.layout(sample))
    }

    @Test
    fun tierRankOrdersTheGenerations() {
        assertEquals(0, TierRank.of("صحابي"))
        assertEquals(1, TierRank.of("تابعي"))
        assertEquals(1, TierRank.of("كبار التابعين"))
        assertEquals(2, TierRank.of("تابع التابعين"))
        assertEquals(2, TierRank.of("أتباع التابعين"))
        assertEquals(3, TierRank.of("شيخ المصنف"))
        assertNull(TierRank.of("غير معروف"))
    }

    @Test
    fun aPlainNarratorOfNoKnownTierGetsABandOfHisOwnBelowTheCompanionNotInHisBand() {
        // The shape of the bundled catalog: the compiler's teacher, a few plain «راوٍ», and the Companion.
        val plain = graph(
            listOf(node(1, "صحابي"), node(2, "راوٍ"), node(3, "راوٍ"), node(4, "شيخ المصنف")),
            1L to 2L, 2L to 3L, 3L to 4L,
        )

        val layout = engine.layout(plain)

        assertEquals(listOf("صحابي", "راوٍ", "شيخ المصنف"), layout.bands.map { it.tier })
        assertTrue(layout.p(1).y < layout.p(2).y)
        assertTrue(layout.p(3).y < layout.p(4).y)
    }

    @Test
    fun plainNarratorsBetweenTwoKnownTiersSitBetweenThem() {
        val mixed = graph(
            listOf(node(1, "صحابي"), node(2, "راوٍ"), node(3, "تابعي"), node(4, "شيخ المصنف")),
            1L to 2L, 2L to 3L, 3L to 4L,
        )

        val layout = engine.layout(mixed)

        assertEquals(listOf("صحابي", "راوٍ", "تابعي", "شيخ المصنف"), layout.bands.map { it.tier })
    }
}
