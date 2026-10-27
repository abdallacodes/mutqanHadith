package com.mutqan.hadith.data.catalog

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.mutqan.hadith.domain.catalog.CatalogDelta
import com.mutqan.hadith.domain.catalog.CloudGroup
import com.mutqan.hadith.domain.catalog.CloudHadith
import com.mutqan.hadith.domain.catalog.CloudLink
import com.mutqan.hadith.domain.catalog.CloudNarrator
import com.mutqan.hadith.domain.catalog.RemoteCatalog
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * [RemoteCatalog] over Cloud Firestore: `meta/catalog` and the collections `catalog_hadith`, `catalog_narrator` and
 * `catalog_group` (written by an admin, or uploaded from the bundled catalog by `admin-tools/upload-seed.js`). The
 * catalog is public to read, so no sign-in is needed. Reads go to the server, never the cache, so that a pull is a real
 * answer; with no network they fail and the next start tries again.
 */
class FirestoreRemoteCatalog(database: () -> FirebaseFirestore = FirebaseFirestore::getInstance) : RemoteCatalog {
    /** Reached on the first pull, so nothing starts Firestore before the catalog is wanted. */
    private val db by lazy(database)

    override suspend fun revision(): Long? =
        db.collection("meta").document("catalog").get(Source.SERVER).await().getLong("revision")

    override suspend fun changesSince(from: Long): CatalogDelta {
        val since = Timestamp(Date(from))
        suspend fun docs(name: String) =
            db.collection(name).whereGreaterThanOrEqualTo("updatedAt", since).get(Source.SERVER).await().documents

        val groupDocs = docs("catalog_group")
        val narratorDocs = docs("catalog_narrator")
        val hadithDocs = docs("catalog_hadith")
        val groups = groupDocs.map { it.toGroup() }
        val narrators = narratorDocs.map { it.toNarrator() }
        val hadiths = hadithDocs.map { it.toHadith() }
        return CatalogDelta(
            hadiths = hadiths.filterNotNull(),
            narrators = narrators.filterNotNull(),
            groups = groups.filterNotNull(),
            unreadable = hadiths.count { it == null } + narrators.count { it == null } + groups.count { it == null },
        )
    }

    private fun DocumentSnapshot.updatedAtMillis(): Long? = getTimestamp("updatedAt")?.toDate()?.time

    /** A document that is not well formed reads as null and is counted, rather than failing the whole pull. */
    private fun DocumentSnapshot.toHadith(): CloudHadith? {
        val links = (get("sanad") as? List<*>)?.map { link ->
            val map = link as? Map<*, *> ?: return null
            CloudLink(
                narratorId = (map["narratorId"] as? Number)?.toLong() ?: return null,
                transmissionTerm = map["transmissionTerm"] as? String ?: return null,
            )
        } ?: return null
        return CloudHadith(
            id = getLong("id") ?: return null,
            matnWithTashkeel = getString("matnWithTashkeel") ?: return null,
            sourceBook = getString("sourceBook") ?: return null,
            hadithNumber = getLong("hadithNumber")?.toInt() ?: return null,
            chapter = getString("chapter") ?: return null,
            grade = getString("grade") ?: return null,
            collector = getString("collector") ?: return null,
            takhrij = getString("takhrij") ?: return null,
            sahabi = getString("sahabi") ?: return null,
            groupId = getLong("groupId"),
            position = getLong("position")?.toInt() ?: return null,
            sanadSource = getString("sanadSource") ?: return null,
            sanad = links,
            updatedAt = updatedAtMillis() ?: return null,
            deleted = getBoolean("deleted") ?: return null,
        )
    }

    private fun DocumentSnapshot.toNarrator(): CloudNarrator? {
        return CloudNarrator(
            id = getLong("id") ?: return null,
            primaryName = getString("primaryName") ?: return null,
            fullName = getString("fullName") ?: return null,
            tier = getString("tier") ?: return null,
            isPivot = getBoolean("isPivot") ?: return null,
            updatedAt = updatedAtMillis() ?: return null,
            deleted = getBoolean("deleted") ?: return null,
            sameAs = getLong("sameAs"),
        )
    }

    private fun DocumentSnapshot.toGroup(): CloudGroup? {
        return CloudGroup(
            id = getLong("id") ?: return null,
            parentId = getLong("parentId"),
            title = getString("title") ?: return null,
            titleEn = getString("titleEn") ?: return null,
            position = getLong("position")?.toInt() ?: return null,
            updatedAt = updatedAtMillis() ?: return null,
            deleted = getBoolean("deleted") ?: return null,
        )
    }
}
