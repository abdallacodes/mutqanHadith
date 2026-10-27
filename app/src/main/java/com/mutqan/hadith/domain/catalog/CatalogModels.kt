package com.mutqan.hadith.domain.catalog

/** One link of a chain as the cloud writes it: who, and the term by which the next link received from them. */
data class CloudLink(val narratorId: Long, val transmissionTerm: String)

data class CloudHadith(
    val id: Long,
    val matnWithTashkeel: String,
    val sourceBook: String,
    val hadithNumber: Int,
    val chapter: String,
    val grade: String,
    val collector: String,
    val takhrij: String,
    val sahabi: String,
    val groupId: Long?,
    val position: Int,
    val sanadSource: String,
    /** From the collector's side: index 0 is his teacher, the last link is the Companion. */
    val sanad: List<CloudLink>,
    /** When the cloud last wrote it (epoch millis, the server's clock). */
    val updatedAt: Long,
    /** Marked deleted by an admin: the document stays, the hadith goes. */
    val deleted: Boolean,
)

data class CloudNarrator(
    val id: Long,
    val primaryName: String,
    val fullName: String,
    val tier: String,
    val isPivot: Boolean,
    val updatedAt: Long,
    val deleted: Boolean,
    /** The narrator this one is the same person as, if an admin said so. */
    val sameAs: Long? = null,
    /** What the admin wrote about the narrator; empty when nothing was. */
    val bio: String = "",
)

data class CloudGroup(
    val id: Long,
    val parentId: Long?,
    val title: String,
    val titleEn: String,
    val position: Int,
    val updatedAt: Long,
    val deleted: Boolean,
)

/** The catalog documents changed since a cursor. A document the pull could not read at all is counted in [unreadable]. */
data class CatalogDelta(
    val hadiths: List<CloudHadith> = emptyList(),
    val narrators: List<CloudNarrator> = emptyList(),
    val groups: List<CloudGroup> = emptyList(),
    val unreadable: Int = 0,
) {
    val isEmpty: Boolean get() = hadiths.isEmpty() && narrators.isEmpty() && groups.isEmpty()

    /** The latest write among the documents. */
    val latest: Long
        get() = maxOf(
            hadiths.maxOfOrNull { it.updatedAt } ?: 0,
            narrators.maxOfOrNull { it.updatedAt } ?: 0,
            groups.maxOfOrNull { it.updatedAt } ?: 0,
        )
}

/** The cloud catalog; the real one is Firestore, tests use a fake. */
interface RemoteCatalog {
    /** The revision in `meta/catalog`, which changes whenever an admin saves; null when the cloud has no catalog yet. */
    suspend fun revision(): Long?

    /** The documents written at or after [from] (epoch millis), deleted ones included. */
    suspend fun changesSince(from: Long): CatalogDelta
}

/** What applying a delta did: [applied] documents changed the library, [skipped] were not valid and left out. */
data class ApplyResult(val applied: Int, val skipped: Int, val earliestSkipped: Long? = null)

/** Writes a delta into the phone's library in one transaction; the learner's progress is never touched except by a deletion. */
interface CatalogStore {
    suspend fun apply(delta: CatalogDelta): ApplyResult
}

/** What the phone has absorbed of the cloud catalog. */
data class CatalogSyncState(
    /** The `meta/catalog` revision last absorbed; null before the first pull. */
    val revision: Long? = null,
    /** Documents written before this time (epoch millis, server clock) are already in the library. */
    val cursor: Long = 0,
    /** When the cloud was last asked (epoch millis); null if never. */
    val checkedAt: Long? = null,
    /** When the library last changed because of it. */
    val updatedAt: Long? = null,
)

interface CatalogSyncStateStore {
    fun read(): CatalogSyncState

    fun write(state: CatalogSyncState)
}

class InMemoryCatalogSyncState(private var state: CatalogSyncState = CatalogSyncState()) : CatalogSyncStateStore {
    override fun read(): CatalogSyncState = state

    override fun write(state: CatalogSyncState) {
        this.state = state
    }
}

sealed interface CatalogOutcome {
    /** The cloud has no catalog yet; the bundled one stays. */
    data object NoCloudCatalog : CatalogOutcome

    /** Nothing changed since the last pull. */
    data object UpToDate : CatalogOutcome

    data class Updated(val applied: Int, val skipped: Int) : CatalogOutcome
}
