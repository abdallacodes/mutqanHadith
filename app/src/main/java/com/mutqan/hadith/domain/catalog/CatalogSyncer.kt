package com.mutqan.hadith.domain.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Where the check for a newer library stands, for the screen that shows it. */
sealed interface CatalogStatus {
    data object Idle : CatalogStatus

    data object Checking : CatalogStatus

    /** The last check did not get through (no network); the next trigger tries again. */
    data object Failed : CatalogStatus
}

/** What a screen can ask of the library updates and read from them. */
interface LibraryUpdates {
    val status: StateFlow<CatalogStatus>

    /** When the cloud was last asked (epoch millis); null if never. */
    val checkedAt: StateFlow<Long?>

    /** When the library last changed because of the cloud; null if it never has. */
    val updatedAt: StateFlow<Long?>

    /** Checks for a newer library, unless one was checked recently; [force] checks regardless. */
    fun refresh(force: Boolean = false)
}

/** [LibraryUpdates] that never does anything; the default for code that is not given the real ones. */
class NoLibraryUpdates : LibraryUpdates {
    override val status: StateFlow<CatalogStatus> = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle).asStateFlow()
    override val checkedAt: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()
    override val updatedAt: StateFlow<Long?> = MutableStateFlow<Long?>(null).asStateFlow()

    override fun refresh(force: Boolean) = Unit
}

/**
 * Checks for a newer library when the app opens, at most once every [minIntervalMillis] so that opening it often costs
 * nothing, and whenever the learner asks. One check at a time; a failure is shown as such and never retried in a loop.
 * The library screens observe the database, so an update appears on its own.
 */
class CatalogSyncer(
    private val sync: SyncCatalogUseCase,
    private val states: CatalogSyncStateStore,
    private val scope: CoroutineScope,
    private val minIntervalMillis: Long = SIX_HOURS,
    private val now: () -> Long = System::currentTimeMillis,
) : LibraryUpdates {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<CatalogStatus>(CatalogStatus.Idle)
    private val _checkedAt = MutableStateFlow(states.read().checkedAt)
    private val _updatedAt = MutableStateFlow(states.read().updatedAt)

    override val status: StateFlow<CatalogStatus> = _status.asStateFlow()
    override val checkedAt: StateFlow<Long?> = _checkedAt.asStateFlow()
    override val updatedAt: StateFlow<Long?> = _updatedAt.asStateFlow()

    override fun refresh(force: Boolean) {
        scope.launch { check(force) }
    }

    /** [refresh] for a caller that has to wait for the answer, such as a background job. True when the check got through. */
    suspend fun refreshAndAwait(force: Boolean = false): Boolean = check(force)

    private suspend fun check(force: Boolean): Boolean {
        val last = _checkedAt.value
        if (!force && last != null && now() - last in 0 until minIntervalMillis) return true
        if (!mutex.tryLock()) return true
        try {
            _status.value = CatalogStatus.Checking
            sync.sync()
            val state = states.read()
            _checkedAt.value = state.checkedAt
            _updatedAt.value = state.updatedAt
            _status.value = CatalogStatus.Idle
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _status.value = CatalogStatus.Failed
            return false
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        const val SIX_HOURS = 6 * 60 * 60 * 1000L
    }
}
