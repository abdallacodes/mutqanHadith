package com.mutqan.hadith.domain.sync

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.backup.BackupRepository
import com.mutqan.hadith.domain.backup.UserData
import com.mutqan.hadith.domain.settings.StudyPreferences

/**
 * Brings the progress on the phone and the progress in the account together.
 *
 * - A hadith's record is taken from the side that reviewed it last (the repetition count breaks a tie); a hadith only
 *   one side has goes to the other. Neither side is ever overwritten wholesale.
 * - Narrator events only ever get added: both sides end with all of them, matched by hadith, narrator and time.
 * - The starting point follows whoever changed it since the last sync.
 * - Records and events of hadiths or narrators this app does not know (a newer catalog) are left alone, on both sides.
 *
 * The first meeting of a phone and an account that both hold progress asks the learner what to do ([FirstSyncChoice])
 * before anything moves.
 */
class SyncProgressUseCase(
    private val backup: BackupRepository,
    private val local: ProgressMerge,
    private val remote: RemoteProgress,
    private val preferences: StudyPreferences,
    private val states: SyncStateStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(uid: String, choice: FirstSyncChoice? = null): SyncOutcome {
        val before = states.read()
        // A reset or a restore on this phone has to reach the account first, or the next pull would undo it.
        val chosen = if (before.replacePending) FirstSyncChoice.USE_THIS_PHONE else choice
        val newAccount = before.uid != uid
        var start = if (newAccount) SyncState(uid = uid) else before
        if (chosen == FirstSyncChoice.USE_THIS_PHONE) {
            remote.clear(uid)
            start = SyncState(uid = uid)
        }

        val cloud = remote.pull(uid, start.eventCursor)
        val phone = backup.readUserData()
        val library = backup.libraryIds()
        val phoneHasProgress = phone.mastery.isNotEmpty() || phone.events.isNotEmpty()
        if (newAccount && chosen == null && phoneHasProgress && cloud.hasProgress) {
            return SyncOutcome.NeedsChoice(phone.mastery.size, cloud.mastery.size)
        }

        val knownMastery = cloud.mastery.filter { it.hadithId in library.hadithIds }
        val knownEvents = cloud.events.filter { it.hadithId in library.hadithIds && it.narratorId in library.narratorIds }
        val skippedEvents = knownEvents.size < cloud.events.size

        if (chosen == FirstSyncChoice.USE_ACCOUNT) {
            backup.replaceUserData(UserData(knownMastery, knownEvents))
            val point = cloud.startingPoint?.also { preferences.setStartingPoint(it) } ?: preferences.startingPoint.value
            states.write(
                start.copy(
                    eventCursor = if (skippedEvents) start.eventCursor else cloud.eventCursor,
                    pushedUntil = knownEvents.maxOfOrNull { it.reviewedAt } ?: 0,
                    startingPoint = point,
                    lastSyncedAt = now(),
                    replacePending = false,
                ),
            )
            return SyncOutcome.Done(pulled = knownMastery.size, pushed = 0)
        }

        val phoneById = phone.mastery.associateBy { it.hadithId }
        val cloudById = cloud.mastery.associateBy { it.hadithId }
        val toPhone = knownMastery.filter { c -> phoneById[c.hadithId]?.let { c.isNewerThan(it) } ?: true }
        val toCloud = phone.mastery.filter { p -> cloudById[p.hadithId]?.let { p.isNewerThan(it) } ?: true }

        val phoneKeys = phone.events.mapTo(HashSet()) { it.key() }
        val cloudKeys = cloud.events.mapTo(HashSet()) { it.key() }
        val newToPhone = knownEvents.distinctBy { it.key() }.filter { it.key() !in phoneKeys }
        val newToCloud = phone.events
            .filter { it.reviewedAt > start.pushedUntil && it.key() !in cloudKeys }
            .distinctBy { it.key() }

        val phonePoint = preferences.startingPoint.value
        val pushPoint = phonePoint?.takeIf { it != start.startingPoint }
        val adoptPoint = if (pushPoint == null && cloud.startingPoint != null && cloud.startingPoint != phonePoint) cloud.startingPoint else null

        if (toPhone.isNotEmpty() || newToPhone.isNotEmpty()) local.merge(toPhone, newToPhone)
        if (toCloud.isNotEmpty() || newToCloud.isNotEmpty() || pushPoint != null) remote.push(uid, toCloud, newToCloud, pushPoint)
        adoptPoint?.let { preferences.setStartingPoint(it) }

        states.write(
            start.copy(
                // Events of hadiths this app does not know yet stay ahead of the cursor, to be pulled after an update.
                eventCursor = if (skippedEvents) start.eventCursor else cloud.eventCursor,
                pushedUntil = maxOf(start.pushedUntil, phone.events.maxOfOrNull { it.reviewedAt } ?: 0, knownEvents.maxOfOrNull { it.reviewedAt } ?: 0),
                startingPoint = pushPoint ?: adoptPoint ?: start.startingPoint ?: phonePoint,
                lastSyncedAt = now(),
                replacePending = false,
            ),
        )
        return SyncOutcome.Done(pulled = toPhone.size, pushed = toCloud.size)
    }

    /**
     * Removes everything the account holds for [uid] and forgets what was agreed with it, so that a later sign-in is a
     * first meeting again. The progress on the phone is not touched.
     */
    suspend fun deleteCloudData(uid: String) {
        remote.clear(uid)
        states.write(SyncState())
    }

    /** Marks this phone's progress as the one the account has to hold; the next sync does it, now or when it can. */
    fun replaceAccountWithThisPhone() {
        states.write(states.read().copy(replacePending = true))
    }

    /** The newer review wins; the repetition count settles a tie. Equal ranks mean nothing needs to move. */
    private fun MasteryRecordEntity.isNewerThan(other: MasteryRecordEntity): Boolean {
        val mine = lastReviewedAt()
        val theirs = other.lastReviewedAt()
        return mine > theirs || (mine == theirs && repetitionCount > other.repetitionCount)
    }

    private fun MasteryRecordEntity.lastReviewedAt(): Long = maxOf(matnLastReviewedAt ?: 0, sanadLastReviewedAt ?: 0)

    private fun NarratorReviewEventEntity.key() = "${hadithId}_${narratorId}_$reviewedAt"
}
