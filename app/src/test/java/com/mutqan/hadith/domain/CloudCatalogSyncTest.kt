package com.mutqan.hadith.domain

import com.mutqan.hadith.domain.catalog.ApplyResult
import com.mutqan.hadith.domain.catalog.CatalogDelta
import com.mutqan.hadith.domain.catalog.CatalogOutcome
import com.mutqan.hadith.domain.catalog.CatalogStatus
import com.mutqan.hadith.domain.catalog.CatalogStore
import com.mutqan.hadith.domain.catalog.CatalogSyncState
import com.mutqan.hadith.domain.catalog.CatalogSyncer
import com.mutqan.hadith.domain.catalog.CloudHadith
import com.mutqan.hadith.domain.catalog.CloudLink
import com.mutqan.hadith.domain.catalog.InMemoryCatalogSyncState
import com.mutqan.hadith.domain.catalog.RemoteCatalog
import com.mutqan.hadith.domain.catalog.SyncCatalogUseCase
import com.mutqan.hadith.domain.sync.AccountSync
import com.mutqan.hadith.domain.sync.FirstSyncChoice
import com.mutqan.hadith.domain.sync.SyncCoordinator
import com.mutqan.hadith.domain.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bringing the library up to the cloud catalog: when it is asked, what is pulled, and what the screens are told. */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudCatalogSyncTest {

    private class FakeCloud : RemoteCatalog {
        var revision: Long? = 1
        var delta = CatalogDelta()
        var failing = false
        var revisionReads = 0
        val pulledFrom = mutableListOf<Long>()

        override suspend fun revision(): Long? {
            if (failing) throw IllegalStateException("offline")
            revisionReads++
            return revision
        }

        override suspend fun changesSince(from: Long): CatalogDelta {
            if (failing) throw IllegalStateException("offline")
            pulledFrom += from
            return delta
        }
    }

    private class FakeStore(var result: (CatalogDelta) -> ApplyResult = { ApplyResult(it.hadiths.size, 0) }) : CatalogStore {
        val applied = mutableListOf<CatalogDelta>()

        override suspend fun apply(delta: CatalogDelta): ApplyResult {
            applied += delta
            return result(delta)
        }
    }

    private val cloud = FakeCloud()
    private val store = FakeStore()
    private val state = InMemoryCatalogSyncState()
    private var time = 10_000L

    private fun useCase() = SyncCatalogUseCase(cloud, store, state) { time }

    private fun hadith(id: Long, at: Long) = CloudHadith(
        id, "matn", "book", 1, "", "", "collector", "", "sahabi", 1, 1, "", listOf(CloudLink(10, "عن")), at, deleted = false,
    )

    // -- The use case ----------------------------------------------------------------------------

    @Test
    fun aCloudWithNoCatalogLeavesTheBundledOneAlone() = runTest {
        cloud.revision = null

        val outcome = useCase().sync()

        assertEquals(CatalogOutcome.NoCloudCatalog, outcome)
        assertTrue(store.applied.isEmpty())
        assertEquals(10_000L, state.read().checkedAt)
        assertNull(state.read().revision)
    }

    @Test
    fun theFirstPullAppliesEverythingTheCloudHoldsAndRemembersTheRevisionAndTheCursor() = runTest {
        cloud.revision = 3
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 500), hadith(12, 800)))

        val outcome = useCase().sync()

        assertEquals(CatalogOutcome.Updated(applied = 2, skipped = 0), outcome)
        assertEquals(listOf(0L), cloud.pulledFrom)
        assertEquals(3L, state.read().revision)
        assertEquals(800L, state.read().cursor)
        assertEquals(10_000L, state.read().updatedAt)
    }

    @Test
    fun aRevisionThatHasNotMovedCostsOneReadAndPullsNothing() = runTest {
        cloud.revision = 3
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 500)))
        useCase().sync()
        time = 20_000L

        val outcome = useCase().sync()

        assertEquals(CatalogOutcome.UpToDate, outcome)
        assertEquals(1, cloud.pulledFrom.size)
        assertEquals(2, cloud.revisionReads)
        assertEquals(20_000L, state.read().checkedAt)
        assertEquals(10_000L, state.read().updatedAt)
    }

    @Test
    fun aNewRevisionPullsOnlyWhatChangedSinceTheCursor() = runTest {
        cloud.revision = 3
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 500)))
        useCase().sync()

        cloud.revision = 4
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 900)))
        useCase().sync()

        assertEquals(listOf(0L, 500L), cloud.pulledFrom)
        assertEquals(900L, state.read().cursor)
        assertEquals(4L, state.read().revision)
    }

    @Test
    fun aDocumentThatCouldNotBeAppliedKeepsTheCursorBeforeItSoItIsTriedAgain() = runTest {
        cloud.revision = 3
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 500), hadith(12, 800), hadith(13, 900)))
        store.result = { ApplyResult(applied = 2, skipped = 1, earliestSkipped = 800) }

        val outcome = useCase().sync()

        assertEquals(CatalogOutcome.Updated(applied = 2, skipped = 1), outcome)
        assertEquals(800L, state.read().cursor)
        assertEquals(3L, state.read().revision)
    }

    @Test
    fun documentsThePullCouldNotReadAreCountedAsSkipped() = runTest {
        cloud.revision = 3
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 500)), unreadable = 2)

        val outcome = useCase().sync()

        assertEquals(CatalogOutcome.Updated(applied = 1, skipped = 2), outcome)
    }

    @Test
    fun aFailedPullChangesNothingAndTheNextOneStartsAgain() = runTest {
        cloud.revision = 3
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 500)))
        cloud.failing = true

        val failure = runCatching { useCase().sync() }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(CatalogSyncState(), state.read())
        cloud.failing = false
        useCase().sync()
        assertEquals(3L, state.read().revision)
    }

    // -- The syncer ------------------------------------------------------------------------------

    private fun kotlinx.coroutines.test.TestScope.syncer(minInterval: Long = 1_000) =
        CatalogSyncer(useCase(), state, backgroundScope, minInterval) { time }

    @Test
    fun theSyncerChecksOnTheFirstRefreshAndNotAgainWithinTheInterval() = runTest {
        val syncer = syncer()

        syncer.refresh()
        runCurrent()
        time += 500
        syncer.refresh()
        runCurrent()

        assertEquals(1, cloud.revisionReads)
        assertEquals(CatalogStatus.Idle, syncer.status.value)
        assertEquals(10_000L, syncer.checkedAt.value)

        time += 1_000
        syncer.refresh()
        runCurrent()
        assertEquals(2, cloud.revisionReads)
    }

    @Test
    fun aForcedRefreshChecksEvenWithinTheInterval() = runTest {
        val syncer = syncer(minInterval = 60_000)
        syncer.refresh()
        runCurrent()

        syncer.refresh(force = true)
        runCurrent()

        assertEquals(2, cloud.revisionReads)
    }

    @Test
    fun aFailedCheckIsShownAndTheNextRefreshTriesAgain() = runTest {
        cloud.failing = true
        val syncer = syncer()

        syncer.refresh()
        runCurrent()
        assertEquals(CatalogStatus.Failed, syncer.status.value)
        assertNull(syncer.checkedAt.value)

        cloud.failing = false
        syncer.refresh()
        runCurrent()
        assertEquals(CatalogStatus.Idle, syncer.status.value)
        assertEquals(10_000L, syncer.checkedAt.value)
    }

    @Test
    fun theSyncerReportsWhenTheLibraryLastChanged() = runTest {
        cloud.delta = CatalogDelta(hadiths = listOf(hadith(11, 500)))
        val syncer = syncer()

        syncer.refresh()
        runCurrent()

        assertEquals(10_000L, syncer.updatedAt.value)
    }

    @Test
    fun aCallerThatWaitsLearnsWhetherTheCheckGotThrough() = runTest {
        val syncer = syncer()
        cloud.failing = true
        assertEquals(false, syncer.refreshAndAwait(force = true))

        cloud.failing = false
        assertEquals(true, syncer.refreshAndAwait(force = true))
    }

    // -- The coordinator -------------------------------------------------------------------------

    private class FakeAccountSync : AccountSync {
        val current = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
        override val status: StateFlow<SyncStatus> = current
        override val lastSyncedAt: StateFlow<Long?> = MutableStateFlow(null)
        var syncs = 0

        override fun syncNow() {
            syncs++
        }

        override fun choose(choice: FirstSyncChoice) = Unit

        override fun replaceAccountWithThisPhone() = Unit
    }

    @Test
    fun comingToTheForegroundChecksTheLibraryWithoutForcingItAndSyncsTheProgress() = runTest {
        val account = FakeAccountSync()
        val syncer = syncer(minInterval = 60_000)
        val coordinator = SyncCoordinator(account, syncer, backgroundScope)

        coordinator.onForeground()
        runCurrent()
        coordinator.onForeground()
        runCurrent()

        assertEquals(1, cloud.revisionReads)
        assertEquals(2, account.syncs)
    }

    @Test
    fun leavingTheAppSendsTheProgressAtOnce() = runTest {
        val account = FakeAccountSync()
        val coordinator = SyncCoordinator(account, syncer(), backgroundScope)

        coordinator.onBackground()

        assertEquals(1, account.syncs)
        assertEquals(0, cloud.revisionReads)
    }

    @Test
    fun aPullToRefreshForcesBothAndShowsWhileEitherWorks() = runTest {
        val account = FakeAccountSync()
        val syncer = syncer(minInterval = 60_000)
        val coordinator = SyncCoordinator(account, syncer, backgroundScope)
        syncer.refresh()
        runCurrent()

        coordinator.refreshNow()
        runCurrent()

        assertEquals(2, cloud.revisionReads)
        assertEquals(1, account.syncs)
        assertEquals(false, coordinator.refreshing.value)

        account.current.value = SyncStatus.Syncing
        runCurrent()
        assertEquals(true, coordinator.refreshing.value)
    }
}
