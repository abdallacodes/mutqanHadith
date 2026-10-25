package com.mutqan.hadith.domain.drill

import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.narrator.NarratorDirectory

/** The drills on the chain of one hadith, and the fewest narrators each needs in it. */
enum class SanadDrill(val minLinks: Int) {
    /** Name the narrator who was taken out of the chain. */
    MISSING_NARRATOR(2),

    /** Put the narrators of the chain in the order they received the hadith, and have it checked. */
    ORDER_CHAIN(3),

    /** Build the chain slot by slot, with each placement checked at once. */
    SANAD_BUILDER(2),
}

/** Whether a drill can be played on a hadith, and if not, why. */
sealed interface DrillAccess {
    val isOpen: Boolean get() = this === Open

    data object Open : DrillAccess

    sealed interface Closed : DrillAccess

    /** The Sanad tree (layer 2) is not open for the hadith yet, and the advanced student's override is off. */
    data object LayerLocked : Closed

    /** A chain of [links] narrators has too little in it for a drill that needs [needed]. */
    data class ChainTooShort(val needed: Int, val links: Int) : Closed

    /** Every narrator of the chain is known by a bare name that fits several men, so no one can be asked for. */
    data object NoIdentifiedNarrator : Closed
}

/**
 * Which Sanad drills a hadith offers. The drills are on the chain, so they open with the Sanad tree: layer 2 of the
 * hadith, or every hadith in study when the advanced student has opened all layers (the layer already says so, so the
 * policy only reads [LayerStatus]). Then they need a chain with enough in it to ask about.
 */
object SanadDrillPolicy {

    /** [layer] is null for a hadith with no record, one that has not been taken into study. */
    fun access(drill: SanadDrill, layer: LayerStatus?, chain: List<SanadLink>): DrillAccess = when {
        layer == null || !layer.layer.opensSanad -> DrillAccess.LayerLocked
        chain.size < drill.minLinks -> DrillAccess.ChainTooShort(drill.minLinks, chain.size)
        drill == SanadDrill.MISSING_NARRATOR && identifiedIndices(chain).isEmpty() -> DrillAccess.NoIdentifiedNarrator
        else -> DrillAccess.Open
    }

    /** The drills open on this hadith now, in the order they are offered. */
    fun openDrills(layer: LayerStatus?, chain: List<SanadLink>): List<SanadDrill> =
        SanadDrill.entries.filter { access(it, layer, chain).isOpen }

    /**
     * The places of [chain] whose narrator is known by a name that says who he is, and so can be the answer of a
     * question; a bare «سفيان» or «يحيى» cannot.
     */
    fun identifiedIndices(chain: List<SanadLink>): List<Int> {
        val directory = NarratorDirectory.of(chain.map { it.narrator })
        return chain.indices.filter { directory.canonicalOf(chain[it].narrator.id)?.isResolved == true }
    }
}
