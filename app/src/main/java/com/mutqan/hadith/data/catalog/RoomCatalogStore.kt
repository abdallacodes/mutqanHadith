package com.mutqan.hadith.data.catalog

import androidx.room.withTransaction
import com.mutqan.hadith.data.db.MutqanDatabase
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.text.ArabicNormalizer
import com.mutqan.hadith.domain.catalog.ApplyResult
import com.mutqan.hadith.domain.catalog.CatalogDelta
import com.mutqan.hadith.domain.catalog.CatalogStore

/**
 * Applies the cloud catalog to the library in one transaction, in the order the tables depend on each other: groups,
 * narrators, hadiths with their chains, then deletions. Rows are updated in place, so the learner's progress, their
 * narrator history and their collections stay where they are; a hadith's recalls are kept even when its chain changes
 * (the account holds them too, so dropping them would only bring them back). Only deleting a hadith removes what the
 * learner recorded on it, as retiring one always has.
 */
class RoomCatalogStore(private val db: MutqanDatabase) : CatalogStore {

    override suspend fun apply(delta: CatalogDelta): ApplyResult = db.withTransaction {
        val dao = db.catalogDao()
        var applied = 0
        var skipped = 0
        var earliestSkipped: Long? = null
        fun skip(updatedAt: Long) {
            skipped++
            earliestSkipped = minOf(earliestSkipped ?: updatedAt, updatedAt)
        }

        for (g in delta.groups) {
            if (g.deleted) {
                dao.unfileHadithsOf(g.id)
                dao.deleteGroup(g.id)
            } else {
                dao.upsertGroup(CatalogGroupEntity(g.id, g.parentId, g.title, g.titleEn, g.position, g.updatedAt))
            }
            applied++
        }

        for (n in delta.narrators.filter { !it.deleted }) {
            dao.upsertNarrator(
                NarratorEntity(n.id, n.primaryName, n.fullName, n.tier, n.isPivot, n.updatedAt, sameAsId = n.sameAs?.takeIf { it != n.id }),
            )
            applied++
        }

        val narratorIds = dao.narratorIds().toSet()
        for (h in delta.hadiths.filter { !it.deleted }) {
            if (h.matnWithTashkeel.isBlank() || h.sanad.isEmpty() || h.sanad.any { it.narratorId !in narratorIds }) {
                skip(h.updatedAt)
                continue
            }
            dao.upsertHadith(
                HadithEntity(
                    id = h.id,
                    matnWithTashkeel = h.matnWithTashkeel,
                    matnClean = ArabicNormalizer.normalize(h.matnWithTashkeel),
                    sourceBook = h.sourceBook,
                    hadithNumber = h.hadithNumber,
                    chapter = h.chapter,
                    grade = h.grade,
                    collector = h.collector,
                    takhrij = h.takhrij,
                    sahabi = h.sahabi,
                    level = 1,
                    groupId = h.groupId,
                    position = h.position,
                    sanadSource = h.sanadSource,
                    catalogUpdatedAt = h.updatedAt,
                ),
            )
            dao.deleteEdges(h.id)
            dao.insertEdges(h.sanad.mapIndexed { i, link -> SanadEdgeEntity(hadithId = h.id, narratorId = link.narratorId, orderIndex = i, transmissionTerm = link.transmissionTerm) })
            applied++
        }

        val present = dao.hadithIds().toSet()
        for (h in delta.hadiths.filter { it.deleted && it.id in present }) {
            dao.deleteMastery(h.id)
            dao.deleteEvents(h.id)
            dao.deleteCollectionRefs(h.id)
            dao.deleteEdges(h.id)
            dao.deleteHadith(h.id)
            applied++
        }

        for (n in delta.narrators.filter { it.deleted }) dao.deleteNarratorIfUnused(n.id)

        ApplyResult(applied, skipped, earliestSkipped)
    }
}
