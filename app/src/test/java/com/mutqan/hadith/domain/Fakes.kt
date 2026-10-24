package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadEdgeWithNarrator
import com.mutqan.hadith.data.text.ArabicNormalizer
import com.mutqan.hadith.domain.model.HadithOutcomeCount
import com.mutqan.hadith.domain.model.NarratorOutcomeCount
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.mutqan.hadith.domain.repository.ReviewRepository

const val DAY_MS = 86_400_000L

fun narrator(id: Long, name: String = "راوي $id", tier: String = "تابعي") =
    NarratorEntity(id = id, primaryName = name, fullName = name, tier = tier, isPivot = false)

/** A Hadith of curriculum [level] whose chain is [narrators] in order (index 0 = the collector's teacher). */
fun hadith(id: Long, narrators: List<NarratorEntity>, level: Int = 3): HadithWithFullSanad =
    HadithWithFullSanad(
        hadith = HadithEntity(
            id, "متن $id", "متن $id", "كتاب", id.toInt(), "باب", "صحيح", "جامع",
            takhrij = "متفق عليه",
            sahabi = narrators.lastOrNull()?.primaryName.orEmpty(),
            level = level,
        ),
        edges = narrators.mapIndexed { i, n ->
            SanadEdgeWithNarrator(SanadEdgeEntity(id * 100 + i, id, n.id, i, "عن"), n)
        },
    )

class FakeHadithRepository(hadiths: List<HadithWithFullSanad>) : HadithRepository {
    private val all = hadiths.sortedBy { it.hadith.id }
    private val byId = all.associateBy { it.hadith.id }

    /** Ids that already have a mastery record; wired to [FakeReviewRepository] by the tests. */
    var seenIds: () -> Set<Long> = { emptySet() }

    override suspend fun getWithSanad(id: Long) = byId[id]

    override suspend fun getWithSanad(ids: Collection<Long>) = ids.mapNotNull { byId[it] }

    override suspend fun getUnseen() = all.filter { it.hadith.id !in seenIds() }

    override fun observeAll(): Flow<List<HadithWithFullSanad>> = flowOf(all)

    override suspend fun getAll() = all

    override suspend fun getThroughNarrator(narratorId: Long) =
        all.filter { h -> h.edges.any { it.narrator.id == narratorId } }

    override fun getHadithsByLevel(level: Int): Flow<List<HadithEntity>> =
        flowOf(all.map { it.hadith }.filter { it.level == level })

    override fun countHadithsByLevel(level: Int): Flow<Int> = flowOf(all.count { it.hadith.level == level })

    override fun searchInLevel(level: Int, query: String): Flow<List<HadithEntity>> {
        val needle = ArabicNormalizer.normalize(query)
        return flowOf(if (needle.isEmpty()) emptyList() else all.map { it.hadith }.filter { it.level == level && needle in it.matnClean })
    }
}

class FakeNarratorRepository(private val narrators: List<NarratorEntity>) : NarratorRepository {
    override suspend fun getAll() = narrators
}

class FakeReviewRepository : ReviewRepository {
    val mastery = mutableMapOf<Long, MasteryRecordEntity>()
    val events = mutableListOf<NarratorReviewEventEntity>()

    override suspend fun getMastery(hadithId: Long) = mastery[hadithId]

    override suspend fun getDueMastery(now: Long) = mastery.values.filter { it.nextReviewDue <= now }

    override suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>) {
        mastery[record.hadithId] = record
        events += narratorEvents
    }

    override suspend fun logNarratorEvents(narratorEvents: List<NarratorReviewEventEntity>) {
        events += narratorEvents
    }

    override suspend fun getAllMastery() = mastery.values.toList()

    override fun observeAllMastery(): Flow<List<MasteryRecordEntity>> = flowOf(mastery.values.toList())

    override suspend fun getNarratorOutcomeCounts(hadithId: Long): List<NarratorOutcomeCount> =
        events.filter { it.hadithId == hadithId }
            .groupBy { it.narratorId }
            .map { (id, list) ->
                NarratorOutcomeCount(id, errorCount = list.count { !it.succeeded }, successCount = list.count { it.succeeded })
            }

    override suspend fun getNarratorOutcomeTotals(): List<NarratorOutcomeCount> =
        events.groupBy { it.narratorId }
            .map { (id, list) ->
                NarratorOutcomeCount(id, errorCount = list.count { !it.succeeded }, successCount = list.count { it.succeeded })
            }

    override suspend fun getReviewTimestamps(): List<Long> = events.map { it.reviewedAt }.distinct()

    override suspend fun getOutcomeCountsByHadith(narratorId: Long): List<HadithOutcomeCount> =
        events.filter { it.narratorId == narratorId }
            .groupBy { it.hadithId }
            .map { (id, list) ->
                HadithOutcomeCount(id, errorCount = list.count { !it.succeeded }, successCount = list.count { it.succeeded })
            }
}
