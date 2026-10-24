package com.mutqan.hadith.data.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity

data class SanadEdgeWithNarrator(
    @Embedded val edge: SanadEdgeEntity,
    @Relation(parentColumn = "narratorId", entityColumn = "id")
    val narrator: NarratorEntity,
)

/** A narrator in a reconstructed chain, with the term used to receive from him. */
data class SanadLink(
    val orderIndex: Int,
    val transmissionTerm: String,
    val narrator: NarratorEntity,
)

data class HadithWithFullSanad(
    @Embedded val hadith: HadithEntity,
    @Relation(entity = SanadEdgeEntity::class, parentColumn = "id", entityColumn = "hadithId")
    val edges: List<SanadEdgeWithNarrator>,
) {
    /** The chain ordered by [SanadLink.orderIndex]: collector's teacher first, Sahabi last. */
    val sanad: List<SanadLink>
        get() = edges
            .sortedBy { it.edge.orderIndex }
            .map { SanadLink(it.edge.orderIndex, it.edge.transmissionTerm, it.narrator) }
}
