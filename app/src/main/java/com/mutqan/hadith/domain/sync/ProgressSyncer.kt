package com.mutqan.hadith.domain.sync

import com.mutqan.hadith.domain.account.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the sync with the account stands, for the screen that shows it. */
sealed interface SyncStatus {
    /** Not signed in, or nothing is happening. */
    data object Idle : SyncStatus

    data object Syncing : SyncStatus

    /** The last try did not get through (no network, the account's service down); the next trigger tries again. */
    data object Failed : SyncStatus

    /** The phone and the account both hold progress and the learner has to say how to combine them. */
    data class NeedsChoice(val onPhone: Int, val inAccount: Int) : SyncStatus
}

/** How deleting the learner's account and the data it holds ended. */
sealed interface DeleteAccountResult {
    data object Deleted : DeleteAccountResult

    data object NotSignedIn : DeleteAccountResult

    /** Something failed; [dataRemoved] says whether the progress in the account had already been removed by then. */
    data class Failed(val dataRemoved: Boolean) : DeleteAccountResult
}

/** What a screen can ask of the sync and read from it. */
interface AccountSync {
    val status: StateFlow<SyncStatus>

    /** When the last sync finished (epoch millis); null if none has. */
    val lastSyncedAt: StateFlow<Long?>

    fun syncNow()

    /** The learner's answer to [SyncStatus.NeedsChoice]. */
    fun choose(choice: FirstSyncChoice)

    /** A reset or a restore just changed this phone's progress: the account's copy has to follow. */
    fun replaceAccountWithThisPhone()

    /**
     * Removes the progress held in the account and then the account itself, after proving with [idToken] (a fresh
     * Google ID token) that it is theirs. The progress on the phone stays.
     */
    suspend fun deleteAccount(idToken: String): DeleteAccountResult
}

/** An [AccountSync] that never does anything; the default for code that is not given the real one. */
class NoAccountSync : AccountSync {
    override val status: StateFlow<SyncStatus> = MutableStateFlow<SyncStatus>(SyncStatus.Idle).asStateFlow()
    override val lastSyncedAt: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()

    override fun syncNow() = Unit

    override fun choose(choice: FirstSyncChoice) = Unit

    override fun replaceAccountWithThisPhone() = Unit

    override suspend fun deleteAccount(idToken: String): DeleteAccountResult = DeleteAccountResult.NotSignedIn
}

/**
 * Keeps the phone and the account of whoever is signed in together. It syncs when someone signs in, when asked, and a
 * little after the learner's progress changes on the phone; one sync runs at a time, and a failed one is only
 * remembered as failed, never retried in a loop.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ProgressSyncer(
    private val auth: AuthRepository,
    private val sync: SyncProgressUseCase,
    private val states: SyncStateStore,
    private val scope: CoroutineScope,
    /** Fires when the progress on the phone changed; the first value is the state at the time of collecting. */
    localChanges: Flow<*>,
    settleMillis: Long = SETTLE_MILLIS,
) : AccountSync {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    private val _lastSyncedAt = MutableStateFlow(states.read().lastSyncedAt)

    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    override val lastSyncedAt: StateFlow<Long?> = _lastSyncedAt.asStateFlow()

    init {
        scope.launch {
            auth.user.map { it?.uid }.distinctUntilChanged().collectLatest { uid ->
                if (uid == null) {
                    _status.value = SyncStatus.Idle
                } else {
                    run(uid, null)
                    localChanges.drop(1).debounce(settleMillis).collect { run(uid, null) }
                }
            }
        }
    }

    override fun syncNow() {
        val uid = auth.user.value?.uid ?: return
        scope.launch { run(uid, null) }
    }

    /** [syncNow] for a caller that has to wait for the result, such as a background job. True unless the sync failed. */
    suspend fun syncAndAwait(): Boolean {
        val uid = auth.user.value?.uid ?: return true
        run(uid, null)
        return _status.value != SyncStatus.Failed
    }

    override fun choose(choice: FirstSyncChoice) {
        val uid = auth.user.value?.uid ?: return
        scope.launch { run(uid, choice, force = true) }
    }

    override fun replaceAccountWithThisPhone() {
        val uid = auth.user.value?.uid ?: return
        sync.replaceAccountWithThisPhone()
        scope.launch { run(uid, null) }
    }

    /**
     * Waits for a sync in progress, then does the deleting while no other sync can start, so that nothing is uploaded
     * again behind it. If it fails once the progress is gone, the learner is signed out, so that the phone does not
     * upload its copy to the account again the moment the next sync fires.
     */
    override suspend fun deleteAccount(idToken: String): DeleteAccountResult {
        val uid = auth.user.value?.uid ?: return DeleteAccountResult.NotSignedIn
        mutex.withLock {
            var dataRemoved = false
            return try {
                auth.reauthenticate(idToken)
                sync.deleteCloudData(uid)
                dataRemoved = true
                auth.deleteAccount()
                _status.value = SyncStatus.Idle
                _lastSyncedAt.value = null
                DeleteAccountResult.Deleted
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (dataRemoved) auth.signOut()
                DeleteAccountResult.Failed(dataRemoved)
            }
        }
    }

    private suspend fun run(uid: String, choice: FirstSyncChoice?, force: Boolean = false) {
        // Waiting for the learner's answer: a trigger from somewhere else must not ask again.
        if (!force && _status.value is SyncStatus.NeedsChoice) return
        if (!mutex.tryLock()) return
        try {
            _status.value = SyncStatus.Syncing
            _status.value = when (val outcome = sync.sync(uid, choice)) {
                is SyncOutcome.Done -> {
                    _lastSyncedAt.value = states.read().lastSyncedAt
                    SyncStatus.Idle
                }
                is SyncOutcome.NeedsChoice -> SyncStatus.NeedsChoice(outcome.onPhone, outcome.inAccount)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = SyncStatus.Failed
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        /** Reviews come in bursts; syncing once the burst is over is one round trip instead of one per card. */
        const val SETTLE_MILLIS = 20_000L
    }
}
