package com.mutqan.hadith.data.sync

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.WriteBatch
import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.sync.CloudSnapshot
import com.mutqan.hadith.domain.sync.RemoteProgress
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * [RemoteProgress] over Cloud Firestore, under `users/{uid}`:
 *
 * ```
 * mastery/{hadithId}                           the record's fields
 * events/{hadithId}_{narratorId}_{reviewedAt}  one narrator event; `uploadedAt` is the server's clock, for the cursor
 * profile/main                                 { startingPoint }
 * ```
 *
 * Reads go to the server rather than the local cache: a sync that quietly merged with a stale cache would look like it
 * worked. When the server cannot be reached they fail, and the sync is tried again later.
 */
class FirestoreRemoteProgress(database: () -> FirebaseFirestore = FirebaseFirestore::getInstance) : RemoteProgress {
    /** Reached on the first sync, so an app that is never signed in never starts Firestore. */
    private val db by lazy(database)

    private fun user(uid: String) = db.collection(USERS).document(uid)

    override suspend fun pull(uid: String, eventsFrom: Long): CloudSnapshot {
        val user = user(uid)
        val mastery = user.collection(MASTERY).get(Source.SERVER).await().documents.mapNotNull { it.toMastery() }
        val eventDocs = user.collection(EVENTS)
            .whereGreaterThanOrEqualTo(UPLOADED_AT, Timestamp(Date(eventsFrom)))
            .get(Source.SERVER).await().documents
        val events = eventDocs.mapNotNull { it.toEvent() }
        val cursor = eventDocs.mapNotNull { it.getTimestamp(UPLOADED_AT)?.toDate()?.time }.maxOrNull() ?: eventsFrom
        val point = user.collection(PROFILE).document(MAIN).get(Source.SERVER).await()
            .getString(STARTING_POINT)?.let { name -> StartingPoint.entries.firstOrNull { it.name == name } }
        return CloudSnapshot(mastery, events, cursor, point)
    }

    override suspend fun push(
        uid: String,
        mastery: List<MasteryRecordEntity>,
        events: List<NarratorReviewEventEntity>,
        startingPoint: StartingPoint?,
    ) {
        val user = user(uid)
        val writes = ArrayList<(WriteBatch) -> Unit>()
        mastery.forEach { r ->
            writes += { it.set(user.collection(MASTERY).document(r.hadithId.toString()), r.toFields()) }
        }
        events.forEach { e ->
            writes += { it.set(user.collection(EVENTS).document("${e.hadithId}_${e.narratorId}_${e.reviewedAt}"), e.toFields()) }
        }
        if (startingPoint != null) {
            writes += { it.set(user.collection(PROFILE).document(MAIN), mapOf(STARTING_POINT to startingPoint.name)) }
        }
        writes.chunked(BATCH_SIZE).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { it(batch) }
            batch.commit().await()
        }
    }

    override suspend fun clear(uid: String) {
        val user = user(uid)
        listOf(MASTERY, EVENTS, PROFILE).forEach { name ->
            user.collection(name).get(Source.SERVER).await().documents.chunked(BATCH_SIZE).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
        }
    }

    private fun MasteryRecordEntity.toFields(): Map<String, Any?> = mapOf(
        "hadithId" to hadithId,
        "matnStability" to matnStability.toDouble(),
        "sanadStability" to sanadStability.toDouble(),
        "matnLastReviewedAt" to matnLastReviewedAt,
        "sanadLastReviewedAt" to sanadLastReviewedAt,
        "nextReviewDue" to nextReviewDue,
        "repetitionCount" to repetitionCount.toLong(),
    )

    private fun NarratorReviewEventEntity.toFields(): Map<String, Any?> = mapOf(
        "hadithId" to hadithId,
        "narratorId" to narratorId,
        "reviewedAt" to reviewedAt,
        "succeeded" to succeeded,
        UPLOADED_AT to FieldValue.serverTimestamp(),
    )

    /** A document that is not a well-formed record is skipped rather than failing the whole sync. */
    private fun DocumentSnapshot.toMastery(): MasteryRecordEntity? {
        return MasteryRecordEntity(
            hadithId = getLong("hadithId") ?: return null,
            matnStability = getDouble("matnStability")?.toFloat() ?: return null,
            sanadStability = getDouble("sanadStability")?.toFloat() ?: return null,
            matnLastReviewedAt = getLong("matnLastReviewedAt"),
            sanadLastReviewedAt = getLong("sanadLastReviewedAt"),
            nextReviewDue = getLong("nextReviewDue") ?: return null,
            repetitionCount = getLong("repetitionCount")?.toInt() ?: return null,
        )
    }

    private fun DocumentSnapshot.toEvent(): NarratorReviewEventEntity? {
        return NarratorReviewEventEntity(
            hadithId = getLong("hadithId") ?: return null,
            narratorId = getLong("narratorId") ?: return null,
            reviewedAt = getLong("reviewedAt") ?: return null,
            succeeded = getBoolean("succeeded") ?: return null,
        )
    }

    private companion object {
        const val USERS = "users"
        const val MASTERY = "mastery"
        const val EVENTS = "events"
        const val PROFILE = "profile"
        const val MAIN = "main"
        const val STARTING_POINT = "startingPoint"
        const val UPLOADED_AT = "uploadedAt"

        /** A Firestore batch holds at most 500 writes. */
        const val BATCH_SIZE = 400
    }
}
