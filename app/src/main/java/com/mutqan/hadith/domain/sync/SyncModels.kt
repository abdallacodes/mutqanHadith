package com.mutqan.hadith.domain.sync

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.model.StartingPoint

/**
 * What the account holds, as far as one pull read it. [events] are only the ones uploaded at or after the cursor the
 * pull was asked for; [mastery] is always every record. [eventCursor] is the latest upload time among [events]
 * (the cursor it was asked for when there were none).
 */
data class CloudSnapshot(
    val mastery: List<MasteryRecordEntity>,
    val events: List<NarratorReviewEventEntity>,
    val eventCursor: Long,
    val startingPoint: StartingPoint?,
) {
    val hasProgress: Boolean get() = mastery.isNotEmpty() || events.isNotEmpty()
}

/** The account's copy of the learner's progress; the real one is Firestore, tests use a fake. */
interface RemoteProgress {
    /** Reads the account's records, and the events uploaded at or after [eventsFrom] (epoch millis). */
    suspend fun pull(uid: String, eventsFrom: Long): CloudSnapshot

    /** Writes the given records and events, and the starting point when it is not null. */
    suspend fun push(
        uid: String,
        mastery: List<MasteryRecordEntity>,
        events: List<NarratorReviewEventEntity>,
        startingPoint: StartingPoint?,
    )

    /** Deletes everything the account holds. */
    suspend fun clear(uid: String)
}

/** Writes pulled progress into the phone in one transaction: mastery is upserted, events are added. */
interface ProgressMerge {
    suspend fun merge(mastery: List<MasteryRecordEntity>, events: List<NarratorReviewEventEntity>)
}

/** What was agreed with the account the last time they met, so the next sync only moves what changed since. */
data class SyncState(
    /** The account this phone last synced with; null before the first sync. */
    val uid: String? = null,
    /** Events the account received before this time (epoch millis, server clock) are already on the phone. */
    val eventCursor: Long = 0,
    /** Events of the phone reviewed at or before this time are already in the account. */
    val pushedUntil: Long = 0,
    /** The starting point both sides had at the last sync. */
    val startingPoint: StartingPoint? = null,
    val lastSyncedAt: Long? = null,
    /** This phone's progress must replace the account's copy (after a reset or a restore) and the sync has not done so yet. */
    val replacePending: Boolean = false,
)

interface SyncStateStore {
    fun read(): SyncState

    fun write(state: SyncState)
}

class InMemorySyncState(private var state: SyncState = SyncState()) : SyncStateStore {
    override fun read(): SyncState = state

    override fun write(state: SyncState) {
        this.state = state
    }
}

/** How to bring a phone and an account together when both already hold progress. */
enum class FirstSyncChoice {
    /** Keep the newest of each hadith from both sides, and every narrator event. */
    MERGE,

    /** The account's progress replaces the phone's. */
    USE_ACCOUNT,

    /** The phone's progress replaces the account's. */
    USE_THIS_PHONE,
}

sealed interface SyncOutcome {
    /** [pulled] hadiths changed on the phone, [pushed] changed in the account. */
    data class Done(val pulled: Int, val pushed: Int) : SyncOutcome

    /** First meeting of this phone and this account, and both hold progress: the learner chooses before anything moves. */
    data class NeedsChoice(val onPhone: Int, val inAccount: Int) : SyncOutcome
}
