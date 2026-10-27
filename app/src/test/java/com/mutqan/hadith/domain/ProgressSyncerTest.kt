package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.domain.account.AccountUser
import com.mutqan.hadith.domain.account.AuthRepository
import com.mutqan.hadith.domain.backup.UserData
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.sync.DeleteAccountResult
import com.mutqan.hadith.domain.sync.FirstSyncChoice
import com.mutqan.hadith.domain.sync.InMemorySyncState
import com.mutqan.hadith.domain.sync.ProgressSyncer
import com.mutqan.hadith.domain.sync.SyncProgressUseCase
import com.mutqan.hadith.domain.sync.SyncState
import com.mutqan.hadith.domain.sync.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the progress is synced, and what the screen is told about it. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgressSyncerTest {

    private class FakeAuth : AuthRepository {
        val current = MutableStateFlow<AccountUser?>(null)
        override val user: StateFlow<AccountUser?> = current

        override suspend fun signInWithGoogle(idToken: String) = Unit

        override fun signOut() {
            current.value = null
        }

        var reauthFails = false
        var deleteFails = false
        var deleted = 0
        val reauths = mutableListOf<String>()

        override suspend fun reauthenticate(idToken: String) {
            if (reauthFails) throw IllegalStateException("another account")
            reauths += idToken
        }

        override suspend fun deleteAccount() {
            if (deleteFails) throw IllegalStateException("could not delete")
            deleted++
            current.value = null
        }
    }

    private val sara = AccountUser("u1", "سارة", "sara@example.com")
    private val auth = FakeAuth()
    private val phone = SyncProgressTest.FakePhone()
    private val account = SyncProgressTest.FakeAccount()
    private val state = InMemorySyncState()
    private val changes = MutableStateFlow(0)

    private fun record(id: Long, at: Long) = MasteryRecordEntity(id, 4f, 1f, at, at, at + 1, 1)

    private fun TestScope.syncer() = ProgressSyncer(
        auth, SyncProgressUseCase(phone, phone, account, InMemoryStudyPreferences(), state) { 7_000L },
        state, backgroundScope, changes, settleMillis = 1_000,
    )

    @Test
    fun nothingHappensWhileNobodyIsSignedIn() = runTest {
        val syncer = syncer()
        runCurrent()

        syncer.syncNow()
        runCurrent()

        assertEquals(SyncStatus.Idle, syncer.status.value)
        assertEquals(0, account.pushes)
    }

    @Test
    fun signingInSyncsTheProgressAndRemembersWhen() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        val syncer = syncer()
        runCurrent()

        auth.current.value = sara
        runCurrent()

        assertEquals(setOf(1L), account.mastery.keys)
        assertEquals(SyncStatus.Idle, syncer.status.value)
        assertEquals(7_000L, syncer.lastSyncedAt.value)
    }

    @Test
    fun bothSidesHoldingProgressAsksAndWaitsForTheAnswer() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        account.mastery[2] = record(2, 200)
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()

        assertEquals(SyncStatus.NeedsChoice(onPhone = 1, inAccount = 1), syncer.status.value)

        // Another trigger must not ask again, nor move anything.
        syncer.syncNow()
        runCurrent()
        assertEquals(SyncStatus.NeedsChoice(1, 1), syncer.status.value)
        assertEquals(0, account.pushes)

        syncer.choose(FirstSyncChoice.MERGE)
        runCurrent()

        assertEquals(SyncStatus.Idle, syncer.status.value)
        assertEquals(setOf(1L, 2L), account.mastery.keys)
        assertEquals(setOf(1L, 2L), phone.data.mastery.map { it.hadithId }.toSet())
    }

    @Test
    fun aFailedSyncIsShownAndTheNextOneTriesAgain() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        account.failing = true
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()
        assertEquals(SyncStatus.Failed, syncer.status.value)
        assertNull(syncer.lastSyncedAt.value)

        account.failing = false
        syncer.syncNow()
        runCurrent()

        assertEquals(SyncStatus.Idle, syncer.status.value)
        assertEquals(setOf(1L), account.mastery.keys)
    }

    @Test
    fun progressMadeOnThePhoneIsSentOnceTheBurstIsOver() = runTest {
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()
        val pushesAfterSignIn = account.pushes

        phone.data = UserData(listOf(record(1, 100)), emptyList())
        changes.value = 1
        runCurrent()
        advanceTimeBy(500)
        changes.value = 2
        advanceTimeBy(900)
        runCurrent()
        assertEquals("still inside the burst", pushesAfterSignIn, account.pushes)

        advanceTimeBy(200)
        runCurrent()

        assertEquals(setOf(1L), account.mastery.keys)
        assertEquals(pushesAfterSignIn + 1, account.pushes)
        assertTrue(syncer.status.value == SyncStatus.Idle)
    }

    @Test
    fun signingOutStopsSyncing() = runTest {
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()

        auth.signOut()
        runCurrent()
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        changes.value = 1
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(SyncStatus.Idle, syncer.status.value)
        assertTrue(account.mastery.isEmpty())
    }

    @Test
    fun aResetWhileSignedInEmptiesTheAccountToo() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()
        assertEquals(setOf(1L), account.mastery.keys)

        phone.data = UserData.Empty
        syncer.replaceAccountWithThisPhone()
        runCurrent()

        assertTrue(account.mastery.isEmpty())
        assertEquals(SyncStatus.Idle, syncer.status.value)
    }

    @Test
    fun aCallerThatWaitsLearnsWhetherTheSyncGotThrough() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        val syncer = syncer()
        runCurrent()
        assertEquals("nobody to sync", true, syncer.syncAndAwait())

        auth.current.value = sara
        runCurrent()
        account.failing = true
        assertEquals(false, syncer.syncAndAwait())

        account.failing = false
        assertEquals(true, syncer.syncAndAwait())
        assertEquals(setOf(1L), account.mastery.keys)
    }

    @Test
    fun aResetWhileSignedOutLeavesTheAccountAlone() = runTest {
        account.mastery[1] = record(1, 100)
        val syncer = syncer()
        runCurrent()

        syncer.replaceAccountWithThisPhone()
        runCurrent()

        assertEquals(setOf(1L), account.mastery.keys)
        assertEquals(false, state.read().replacePending)
    }

    // -- Deleting the account ----------------------------------------------------------------------

    @Test
    fun deletingTheAccountErasesItsProgressAndTheAccountAndLeavesThePhoneAlone() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()
        assertEquals(setOf(1L), account.mastery.keys)

        val result = syncer.deleteAccount("fresh-token")
        runCurrent()

        assertEquals(DeleteAccountResult.Deleted, result)
        assertEquals(listOf("fresh-token"), auth.reauths)
        assertTrue(account.mastery.isEmpty())
        assertEquals(1, auth.deleted)
        assertEquals(setOf(1L), phone.data.mastery.map { it.hadithId }.toSet())
        assertEquals(SyncState(), state.read())
        assertNull(syncer.lastSyncedAt.value)
        assertEquals(SyncStatus.Idle, syncer.status.value)
    }

    @Test
    fun anotherAccountsTokenChangesNothing() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()
        auth.reauthFails = true

        val result = syncer.deleteAccount("someone-else")

        assertEquals(DeleteAccountResult.Failed(dataRemoved = false), result)
        assertEquals(setOf(1L), account.mastery.keys)
        assertEquals(0, auth.deleted)
        assertEquals(sara, auth.current.value)
    }

    @Test
    fun anUnreachableAccountServiceChangesNothing() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()
        account.failing = true

        val result = syncer.deleteAccount("fresh-token")

        assertEquals(DeleteAccountResult.Failed(dataRemoved = false), result)
        assertEquals(0, auth.deleted)
        assertEquals(sara, auth.current.value)
    }

    @Test
    fun ifTheAccountCannotBeDeletedAfterTheProgressIsGoneTheLearnerIsSignedOutSoNothingIsUploadedAgain() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        val syncer = syncer()
        auth.current.value = sara
        runCurrent()
        auth.deleteFails = true
        val pushes = account.pushes

        val result = syncer.deleteAccount("fresh-token")
        runCurrent()
        changes.value = 1
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(DeleteAccountResult.Failed(dataRemoved = true), result)
        assertTrue(account.mastery.isEmpty())
        assertNull(auth.current.value)
        assertEquals("the phone's copy was not sent back", pushes, account.pushes)
    }

    @Test
    fun nobodySignedInHasNothingToDelete() = runTest {
        val syncer = syncer()
        runCurrent()

        assertEquals(DeleteAccountResult.NotSignedIn, syncer.deleteAccount("token"))
        assertEquals(0, auth.deleted)
    }
}
