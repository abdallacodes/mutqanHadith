package com.mutqan.hadith.domain.graph

/** Where one narrator is drawn: [x] and [y] are the centre of a [width] by [height] box, in dp. */
data class NodePlacement(
    val id: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val level: Int,
) {
    val left: Float get() = x - width / 2
    val top: Float get() = y - height / 2
}

/** A horizontal strip holding one generation, for the tier label and background. */
data class TierBand(val tier: String, val top: Float, val bottom: Float)

data class GraphLayout(
    val placements: Map<Long, NodePlacement>,
    val width: Float,
    val height: Float,
    val bands: List<TierBand>,
) {
    companion object {
        val Empty = GraphLayout(emptyMap(), 0f, 0f, emptyList())
    }
}

/** Generation of a narrator, from the Sahabi (0) down to the collectors' teachers (3). */
object TierRank {
    fun of(tier: String): Int? = when {
        "صحاب" in tier -> 0
        "أتباع" in tier || "اتباع" in tier || "تابع التابعين" in tier -> 2
        "تابع" in tier -> 1
        "شيخ" in tier -> 3
        else -> null
    }
}

/**
 * Lays the isnad network out in generation bands, earliest generation on top, so that every
 * teacher sits above the students who received from him and edges run downwards.
 *
 * - A narrator's band comes from his tier; a narrator of unknown tier follows his teachers.
 * - Inside a band, a chain of same-tier narrators is stacked into sub-rows so no edge is horizontal.
 * - Rows are ordered by repeated barycentre sweeps to cut edge crossings; pivots (المدارات) are
 *   pulled towards the middle of their row and drawn larger.
 */
class GraphLayoutEngine(
    private val normalSize: Pair<Float, Float> = 132f to 56f,
    private val pivotSize: Pair<Float, Float> = 160f to 66f,
    private val columnGap: Float = 20f,
    private val rowStep: Float = 120f,
    private val bandGap: Float = 28f,
    /** Room at the top of every band for its tier label, above the first row. */
    private val bandHeader: Float = 34f,
    private val margin: Float = 40f,
    private val sweeps: Int = 4,
    /** 0 keeps a pivot where crossing reduction puts it, 1 pins it to the middle of its row. */
    private val pivotCentering: Float = 0.6f,
) {
    fun layout(graph: NarratorGraph): GraphLayout {
        if (graph.nodes.isEmpty()) return GraphLayout.Empty

        val ids = graph.nodes.map { it.id }
        val edges = graph.edges.filter { it.fromNarratorId != it.toNarratorId }
        val teachersOf = edges.groupBy({ it.toNarratorId }, { it.fromNarratorId })
        val neighbors = mutableMapOf<Long, MutableList<Long>>()
        for (e in edges) {
            neighbors.getOrPut(e.fromNarratorId) { mutableListOf() } += e.toNarratorId
            neighbors.getOrPut(e.toNarratorId) { mutableListOf() } += e.fromNarratorId
        }

        val rank = ranks(graph, teachersOf)
        val depth = depths(ids, edges, rank)

        // Bands in generation order, each as tall as its deepest stack of same-tier narrators.
        val bandRanks = ids.map { rank.getValue(it) }.distinct().sorted()
        val bandStart = mutableMapOf<Int, Int>()
        var next = 0
        for (r in bandRanks) {
            bandStart[r] = next
            next += 1 + ids.filter { rank.getValue(it) == r }.maxOf { depth.getValue(it) }
        }
        val levelOf = ids.associateWith { bandStart.getValue(rank.getValue(it)) + depth.getValue(it) }
        val levelCount = next

        val sizeOf = graph.nodes.associate { it.id to if (it.isPivot) pivotSize else normalSize }
        val isPivot = graph.nodes.filter { it.isPivot }.mapTo(mutableSetOf()) { it.id }

        // Rows, then crossing reduction on x positions measured in "slots" around 0.
        val rows = Array(levelCount) { l -> ids.filter { levelOf.getValue(it) == l }.toMutableList() }
        val slotX = mutableMapOf<Long, Float>()
        fun assignSlots() = rows.forEach { row ->
            row.forEachIndexed { i, id -> slotX[id] = i - (row.size - 1) / 2f }
        }
        assignSlots()
        repeat(sweeps) { pass ->
            val order = if (pass % 2 == 0) rows.indices else rows.indices.reversed()
            for (l in order) {
                val row = rows[l]
                val key = row.associateWith { id ->
                    val ns = neighbors[id].orEmpty()
                    val bary = if (ns.isEmpty()) slotX.getValue(id) else ns.sumOf { slotX.getValue(it).toDouble() }.toFloat() / ns.size
                    if (id in isPivot) bary * (1 - pivotCentering) else bary
                }
                row.sortWith(compareBy<Long> { key.getValue(it) }.thenBy { it })
                row.forEachIndexed { i, id -> slotX[id] = i - (row.size - 1) / 2f }
            }
        }

        // Real coordinates: rows are laid out left to right with the real box widths and centred.
        val rowWidths = rows.map { row -> row.sumOf { sizeOf.getValue(it).first.toDouble() }.toFloat() + columnGap * (row.size - 1).coerceAtLeast(0) }
        val contentWidth = rowWidths.max()
        val width = contentWidth + margin * 2
        val maxHeight = maxOf(normalSize.second, pivotSize.second)

        val bandIndexOfLevel = IntArray(levelCount)
        for ((bi, r) in bandRanks.withIndex()) {
            val start = bandStart.getValue(r)
            val end = if (bi + 1 < bandRanks.size) bandStart.getValue(bandRanks[bi + 1]) else levelCount
            for (l in start until end) bandIndexOfLevel[l] = bi
        }
        val levelY = FloatArray(levelCount) { l ->
            margin + bandHeader + maxHeight / 2 + l * rowStep + bandIndexOfLevel[l] * (bandGap + bandHeader)
        }

        val placements = mutableMapOf<Long, NodePlacement>()
        rows.forEachIndexed { l, row ->
            var cursor = (width - rowWidths[l]) / 2
            for (id in row) {
                val (w, h) = sizeOf.getValue(id)
                placements[id] = NodePlacement(id, cursor + w / 2, levelY[l], w, h, l)
                cursor += w + columnGap
            }
        }

        val bands = bandRanks.mapIndexed { bi, r ->
            val start = bandStart.getValue(r)
            val end = (if (bi + 1 < bandRanks.size) bandStart.getValue(bandRanks[bi + 1]) else levelCount) - 1
            val members = graph.nodes.filter { rank.getValue(it.id) == r }
            TierBand(
                tier = members.groupingBy { it.tier }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .first().key,
                top = levelY[start] - maxHeight / 2 - bandHeader,
                bottom = levelY[end] + maxHeight / 2 + bandGap / 3,
            )
        }
        return GraphLayout(placements, width, levelY.last() + maxHeight / 2 + margin, bands)
    }

    /** Generation per narrator: from the tier when known, otherwise that of the latest-generation teacher. */
    private fun ranks(graph: NarratorGraph, teachersOf: Map<Long, List<Long>>): Map<Long, Int> {
        val known = graph.nodes.associate { it.id to TierRank.of(it.tier) }
        val rank = known.mapValues { it.value ?: 0 }.toMutableMap()
        // Bounded relaxation so a malformed cycle of unknown tiers cannot loop forever.
        repeat(graph.nodes.size) {
            var changed = false
            for (n in graph.nodes) {
                if (known.getValue(n.id) != null) continue
                val inherited = teachersOf[n.id].orEmpty().maxOfOrNull { rank.getValue(it) } ?: 0
                if (inherited != rank.getValue(n.id)) {
                    rank[n.id] = inherited
                    changed = true
                }
            }
            if (!changed) return rank
        }
        return rank
    }

    /** Length of the longest same-generation teacher chain above each narrator, capped for cyclic data. */
    private fun depths(ids: List<Long>, edges: List<GraphEdge>, rank: Map<Long, Int>): Map<Long, Int> {
        val sameBand = edges.filter { rank.getValue(it.fromNarratorId) == rank.getValue(it.toNarratorId) }
        val depth = ids.associateWith { 0 }.toMutableMap()
        repeat(ids.size) {
            var changed = false
            for (e in sameBand) {
                val candidate = (depth.getValue(e.fromNarratorId) + 1).coerceAtMost(ids.size)
                if (candidate > depth.getValue(e.toNarratorId)) {
                    depth[e.toNarratorId] = candidate
                    changed = true
                }
            }
            if (!changed) return depth
        }
        return depth
    }
}
