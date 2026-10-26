package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.data.entity.HadithEntity
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.data.entity.SanadEdgeEntity
import com.mutqan.hadith.data.relation.HadithPlacement
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.data.relation.SanadEdgeWithNarrator
import com.mutqan.hadith.data.text.ArabicNormalizer
import com.mutqan.hadith.domain.model.CurriculumOrder
import com.mutqan.hadith.domain.model.HadithOutcomeCount
import com.mutqan.hadith.domain.model.NarratorOutcomeCount
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.repository.NarratorRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.mutqan.hadith.domain.repository.ReviewRepository

const val DAY_MS = 86_400_000L

fun narrator(id: Long, name: String = "راوي $id", tier: String = "تابعي") =
    NarratorEntity(id = id, primaryName = name, fullName = name, tier = tier, isPivot = false)

/**
 * A Hadith whose chain is [narrators] in order (index 0 = the collector's teacher), filed under [groupId] and taught
 * at [position]; both unset, as for a row the catalog has not placed.
 */
fun hadith(id: Long, narrators: List<NarratorEntity>, position: Int = 0, groupId: Long? = null): HadithWithFullSanad =
    HadithWithFullSanad(
        hadith = HadithEntity(
            id, "متن $id", "متن $id", "كتاب", id.toInt(), "باب", "صحيح", "جامع",
            takhrij = "متفق عليه",
            sahabi = narrators.lastOrNull()?.primaryName.orEmpty(),
            groupId = groupId,
            position = position,
        ),
        edges = narrators.mapIndexed { i, n ->
            SanadEdgeWithNarrator(SanadEdgeEntity(id * 100 + i, id, n.id, i, "عن"), n)
        },
    )

class FakeHadithRepository(
    hadiths: List<HadithWithFullSanad>,
    private val groups: List<CatalogGroupEntity> = emptyList(),
) : HadithRepository {
    private val all = hadiths.sortedBy { it.hadith.id }
    private val byId = all.associateBy { it.hadith.id }

    /** Ids that already have a mastery record; wired to [FakeReviewRepository] by the tests. */
    var seenIds: () -> Set<Long> = { emptySet() }

    override suspend fun getWithSanad(id: Long) = byId[id]

    override suspend fun getWithSanad(ids: Collection<Long>) = ids.mapNotNull { byId[it] }

    override suspend fun getUnseen() = all.filter { it.hadith.id !in seenIds() }.sortedWith(compareBy(CurriculumOrder) { it.hadith })

    override fun observeAll(): Flow<List<HadithWithFullSanad>> = flowOf(all)

    override suspend fun getAll() = all

    override suspend fun getThroughNarrator(narratorId: Long) =
        all.filter { h -> h.edges.any { it.narrator.id == narratorId } }.sortedWith(compareBy(CurriculumOrder) { it.hadith })

    override fun observeGroups(): Flow<List<CatalogGroupEntity>> = flowOf(groups.sortedWith(compareBy({ it.position }, { it.id })))

    override fun observePlacements(): Flow<List<HadithPlacement>> =
        flowOf(all.map { it.hadith }.sortedWith(CurriculumOrder).map { HadithPlacement(it.id, it.groupId) })

    override fun getHadithsInGroup(groupId: Long): Flow<List<HadithEntity>> =
        flowOf(all.map { it.hadith }.filter { it.groupId == groupId }.sortedWith(CurriculumOrder))

    override fun searchInGroups(groupIds: Collection<Long>, query: String): Flow<List<HadithEntity>> {
        val needle = ArabicNormalizer.normalize(query)
        return flowOf(
            if (needle.isEmpty()) emptyList()
            else all.map { it.hadith }.filter { it.groupId in groupIds && needle in it.matnClean }.sortedWith(CurriculumOrder),
        )
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

/** [this] with its matn replaced, keeping the clean text searched against in step. */
fun HadithWithFullSanad.withMatn(matn: String): HadithWithFullSanad =
    copy(hadith = hadith.copy(matnWithTashkeel = matn, matnClean = ArabicNormalizer.normalize(matn)))

/**
 * A [FakeReviewRepository] whose mastery can be observed, as the database's can: a saved review is
 * emitted again to whoever follows it. [failSaves] makes saving fail, like a full disk.
 */
class LiveReviewRepository(private val base: FakeReviewRepository = FakeReviewRepository()) : ReviewRepository by base {
    private val saves = MutableStateFlow(0)
    var failSaves = false

    val mastery get() = base.mastery
    val events get() = base.events

    override suspend fun saveReview(record: MasteryRecordEntity, narratorEvents: List<NarratorReviewEventEntity>) {
        if (failSaves) throw IllegalStateException("disk full")
        base.saveReview(record, narratorEvents)
        saves.value += 1
    }

    override fun observeAllMastery(): Flow<List<MasteryRecordEntity>> =
        saves.map { base.mastery.values.toList() }
}
