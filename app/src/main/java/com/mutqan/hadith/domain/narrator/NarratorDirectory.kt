package com.mutqan.hadith.domain.narrator

import com.mutqan.hadith.data.entity.NarratorEntity
import com.mutqan.hadith.data.relation.HadithWithFullSanad
import com.mutqan.hadith.domain.graph.TierRank
import com.mutqan.hadith.domain.model.CurriculumOrder
import com.mutqan.hadith.domain.repository.HadithRepository

/**
 * One historical figure, however many narrator rows the catalog spent on him. [id] is the id of one of those rows
 * (the one written as [name] if there is one, else the lowest), so it is always a real narrator and a stored reference
 * to it stays valid; [memberIds] are all the rows, [id] among them.
 */
data class CanonicalNarrator(
    val id: Long,
    val name: String,
    /** The fullest form of the name any row carries, without its title or blessing. */
    val fullName: String,
    val tier: String,
    val isPivot: Boolean,
    val memberIds: Set<Long>,
    /** The other names the catalog gives him. */
    val aliases: List<String>,
    /** Honorific titles that stood before his name («أمير المؤمنين»), kept for display and out of the name. */
    val titles: List<String>,
) {
    /**
     * Whether the name says who he is. A bare «سفيان», «يحيى» or «عبد الله» (see [NarratorAliases.isAmbiguous]) does
     * not, unless a row carries the longer name; a drill that asks for him has no single right answer.
     */
    val isResolved: Boolean get() = !NarratorAliases.isAmbiguous(fullName)
}

/**
 * Who is who among a set of narrator rows: which rows are one person, and the identity they share. Two rows are one
 * person when the curated name table ([NarratorAliases]) says so, or when an admin said so in the cloud catalog (a row's
 * `sameAsId`) and both rows are among the ones asked about. Nothing is stored or changed in the database by this: the
 * rows, the chains that point at them and the user's per-narrator history stay as they are, and everything that wants
 * "this narrator" rather than "this row" asks here.
 */
class NarratorDirectory private constructor(
    /** One entry per person, in order of their canonical id. */
    val narrators: List<CanonicalNarrator>,
) {
    private val byMember: Map<Long, CanonicalNarrator> =
        narrators.flatMap { n -> n.memberIds.map { it to n } }.toMap()

    /** The person the narrator row [narratorId] is; null for a row this directory was not built from. */
    fun canonicalOf(narratorId: Long): CanonicalNarrator? = byMember[narratorId]

    /** The id that stands for the person of row [narratorId]: the row's own when it is nobody's alias or unknown. */
    fun canonicalId(narratorId: Long): Long = byMember[narratorId]?.id ?: narratorId

    /** Every row of the person [narratorId] (a canonical id or any of his rows) belongs to. */
    fun membersOf(narratorId: Long): Set<Long> = byMember[narratorId]?.memberIds ?: setOf(narratorId)

    companion object {
        fun of(narrators: Collection<NarratorEntity>): NarratorDirectory {
            val rows = narrators.distinctBy { it.id }.sortedBy { it.id }
            val parent = rows.associate { it.id to it.id }.toMutableMap()
            fun find(id: Long): Long {
                var root = id
                while (parent.getValue(root) != root) root = parent.getValue(root)
                return root
            }
            fun join(a: Long, b: Long) {
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
            }
            rows.groupBy { aliasGroupOf(it)?.key }.forEach { (key, members) ->
                if (key != null) members.drop(1).forEach { join(members.first().id, it.id) }
            }
            // A pointer to a row that is not among these, to itself, or in a loop can only group rows, never break anything.
            rows.forEach { row -> row.sameAsId?.takeIf { it in parent }?.let { join(row.id, it) } }
            val buckets = rows.groupBy { find(it.id) }
            return NarratorDirectory(
                buckets.values.map { members -> canonical(members, members.firstNotNullOfOrNull { aliasGroupOf(it) }) }.sortedBy { it.id },
            )
        }

        /** The directory of everyone who narrates in [library]. */
        fun ofLibrary(library: Collection<HadithWithFullSanad>): NarratorDirectory =
            of(library.flatMap { hadith -> hadith.edges.map { it.narrator } })

        private fun aliasGroupOf(row: NarratorEntity): AliasGroup? {
            val group = NarratorAliases.groupOf(row.primaryName) ?: NarratorAliases.groupOf(row.fullName) ?: return null
            // A bare «أنس» or «عمر» is a Companion; the same word in the chain of a later generation is somebody else.
            return group.takeIf { !it.isCompanion || TierRank.of(row.tier) == 0 }
        }

        /** [words] holds all of [nameWords], in order, and may hold more between and around them. */
        private fun extendsName(nameWords: List<String>, words: List<String>): Boolean {
            var next = 0
            for (word in words) if (next < nameWords.size && word == nameWords[next]) next++
            return next == nameWords.size
        }

        private fun canonical(members: List<NarratorEntity>, group: AliasGroup?): CanonicalNarrator {
            val written = group?.let { g -> members.firstOrNull { NarratorNormalizer.key(it.primaryName) == g.key } }
            // The row an admin pointed the others at is the person; it is shown under its name and stands for him.
            val pointedAt = members.firstOrNull { m -> m.sameAsId == null && members.any { it.sameAsId == m.id } }
            val head = written ?: pointedAt ?: members.first()
            val name = group?.name ?: NarratorNormalizer.displayName(head.primaryName)
            val names = members.flatMap { listOf(it.primaryName, it.fullName) }.map(NarratorNormalizer::displayName).distinct()
            // The fullest name is a fuller form of the same name («أبو هريرة عبد الرحمن بن صخر»), not another name («ابن شهاب»).
            val nameWords = NarratorNormalizer.key(name).split(" ")
            val fullName = names
                .filter { extendsName(nameWords, NarratorNormalizer.key(it).split(" ")) }
                .maxByOrNull { NarratorNormalizer.key(it).length } ?: name
            return CanonicalNarrator(
                id = head.id,
                name = name,
                fullName = fullName,
                tier = head.tier,
                isPivot = members.any { it.isPivot },
                memberIds = members.mapTo(sortedSetOf()) { it.id },
                aliases = names.filter { it != name && it != fullName },
                titles = members.flatMap { listOf(it.primaryName, it.fullName) }
                    .flatMap { NarratorNormalizer.parse(it).titles }.distinct(),
            )
        }
    }
}

/**
 * The hadiths whose chain passes through the person [narratorId] (a canonical id or any of his rows) names, under any
 * of his spellings, in curriculum order.
 */
suspend fun HadithRepository.getThroughPerson(narratorId: Long): List<HadithWithFullSanad> {
    val library = getAll()
    val rows = NarratorDirectory.ofLibrary(library).membersOf(narratorId)
    return library
        .filter { hadith -> hadith.edges.any { it.narrator.id in rows } }
        .sortedWith(compareBy(CurriculumOrder) { it.hadith })
}
