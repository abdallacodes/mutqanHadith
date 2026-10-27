package com.mutqan.hadith.domain.sync

import com.mutqan.hadith.domain.catalog.CatalogStatus
import com.mutqan.hadith.domain.catalog.LibraryUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The one place the app says "now is a good time to sync": it brings the library and the learner's progress up to date
 * together. Nobody has to open Settings for it; the Settings rows and the pull-to-refresh on Home are only there for the
 * learner who wants to be sure.
 */
class SyncCoordinator(
    private val progress: AccountSync,
    private val library: LibraryUpdates,
    scope: CoroutineScope,
) {
    /** True while either is working, for a pull-to-refresh indicator. */
    val refreshing: StateFlow<Boolean> = combine(progress.status, library.status) { account, catalog ->
        account == SyncStatus.Syncing || catalog == CatalogStatus.Checking
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The app came to the foreground: a newer library is looked for (not more than every few hours), progress is synced. */
    fun onForeground() {
        library.refresh()
        progress.syncNow()
    }

    /** The app is being left: what was reviewed goes out now instead of waiting for the next time it opens. */
    fun onBackground() {
        progress.syncNow()
    }

    /** The learner asked for it: both, regardless of when they were last done. */
    fun refreshNow() {
        library.refresh(force = true)
        progress.syncNow()
    }
}
