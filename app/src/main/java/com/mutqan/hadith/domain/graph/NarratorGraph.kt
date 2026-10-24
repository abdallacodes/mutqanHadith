package com.mutqan.hadith.domain.graph

/**
 * A narrator in the isnad network. [errorCount] and [successCount] sum the user's recall attempts
 * for this narrator over every Hadith, so [errorRate] says how reliably they place him.
 */
data class NarratorNode(
    val id: Long,
    val name: String,
    val fullName: String,
    val tier: String,
    val isPivot: Boolean,
    val errorCount: Int,
    val successCount: Int,
    /** In how many Hadiths of the (possibly filtered) library the narrator appears. */
    val hadithCount: Int,
) {
    val attempts: Int get() = errorCount + successCount

    /** Share of failed attempts in 0..1; 0 for a narrator never tested. */
    val errorRate: Float get() = if (attempts == 0) 0f else errorCount.toFloat() / attempts
}

/**
 * [fromNarratorId] is the teacher and [toNarratorId] the student who received from him, so an edge
 * points down the chain of transmission. [sharedHadithCount] counts the distinct Hadiths that
 * contain exactly this teacher-to-student step.
 */
data class GraphEdge(
    val fromNarratorId: Long,
    val toNarratorId: Long,
    val sharedHadithCount: Int,
)

data class NarratorGraph(
    val nodes: List<NarratorNode>,
    val edges: List<GraphEdge>,
) {
    private val byId by lazy { nodes.associateBy { it.id } }

    fun node(id: Long): NarratorNode? = byId[id]

    fun edgesOf(id: Long): List<GraphEdge> = edges.filter { it.fromNarratorId == id || it.toNarratorId == id }

    /** The narrators directly linked to [id], as teacher or as student. */
    fun neighborsOf(id: Long): Set<Long> =
        edgesOf(id).mapTo(mutableSetOf()) { if (it.fromNarratorId == id) it.toNarratorId else it.fromNarratorId }

    companion object {
        val Empty = NarratorGraph(emptyList(), emptyList())
    }
}
