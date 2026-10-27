package com.mutqan.hadith.domain

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.backup.BackupRepository
import com.mutqan.hadith.domain.backup.LibraryIds
import com.mutqan.hadith.domain.backup.UserData
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.sync.CloudSnapshot
import com.mutqan.hadith.domain.sync.FirstSyncChoice
import com.mutqan.hadith.domain.sync.InMemorySyncState
import com.mutqan.hadith.domain.sync.ProgressMerge
import com.mutqan.hadith.domain.sync.RemoteProgress
import com.mutqan.hadith.domain.sync.SyncOutcome
import com.mutqan.hadith.domain.sync.SyncProgressUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeping the progress on a phone and the progress in an account together. */
class SyncProgressTest {

    /** A phone: the user's data plus the library it ships with. */
    class FakePhone(
        var data: UserData = UserData.Empty,
        var library: LibraryIds = LibraryIds(setOf(1, 2, 3), setOf(10, 11)),
    ) : BackupRepository, ProgressMerge {
        private var nextId = 1L

        override suspend fun readUserData() = data

        override suspend fun libraryIds() = library

        override suspend fun replaceUserData(data: UserData) {
            this.data = UserData(data.mastery, data.events.map { it.copy(id = nextId++) })
        }

        override suspend fun clearUserData() {
            data = UserData.Empty
        }

        override suspend fun merge(mastery: List<MasteryRecordEntity>, events: List<NarratorReviewEventEntity>) {
            val byId = data.mastery.associateBy { it.hadithId } + mastery.associateBy { it.hadithId }
            data = UserData(byId.values.toList(), data.events + events.map { it.copy(id = nextId++) })
        }

        fun review(event: NarratorReviewEventEntity) {
            data = data.copy(events = data.events + event.copy(id = nextId++))
        }
    }

    /** An account: its documents and a server clock that ticks once per push. */
    class FakeAccount : RemoteProgress {
        val mastery = mutableMapOf<Long, MasteryRecordEntity>()
        val events = mutableListOf<Pair<Long, NarratorReviewEventEntity>>()
        var point: StartingPoint? = null
        var clock = 1_000L
        var pushes = 0
        var cleared = 0
        var failing = false

        override suspend fun pull(uid: String, eventsFrom: Long): CloudSnapshot {
            if (failing) throw IllegalStateException("offline")
            val pulled = events.filter { it.first >= eventsFrom }
            return CloudSnapshot(
                mastery.values.toList(), pulled.map { it.second }, pulled.maxOfOrNull { it.first } ?: eventsFrom, point,
            )
        }

        override suspend fun push(uid: String, mastery: List<MasteryRecordEntity>, events: List<NarratorReviewEventEntity>, startingPoint: StartingPoint?) {
            if (failing) throw IllegalStateException("offline")
            pushes++
            clock += 10
            mastery.forEach { this.mastery[it.hadithId] = it }
            events.forEach { e ->
                this.events.removeAll { it.second.sameAs(e) }
                this.events += clock to e.copy(id = 0)
            }
            startingPoint?.let { point = it }
        }

        private fun NarratorReviewEventEntity.sameAs(o: NarratorReviewEventEntity) =
            hadithId == o.hadithId && narratorId == o.narratorId && reviewedAt == o.reviewedAt

        override suspend fun clear(uid: String) {
            if (failing) throw IllegalStateException("offline")
            cleared++
            mastery.clear()
            events.clear()
            point = null
        }
    }

    private val phone = FakePhone()
    private val account = FakeAccount()
    private val prefs = InMemoryStudyPreferences()
    private val state = InMemorySyncState()
    private var time = 5_000L

    private fun useCase(
        phone: FakePhone = this.phone,
        prefs: InMemoryStudyPreferences = this.prefs,
        state: InMemorySyncState = this.state,
    ) = SyncProgressUseCase(phone, phone, account, prefs, state) { time }

    private fun record(id: Long, reviewedAt: Long, repetitions: Int = 1) =
        MasteryRecordEntity(id, 4f, 1f, reviewedAt, reviewedAt, reviewedAt + 86_400_000, repetitions)

    private fun event(hadith: Long, narrator: Long, at: Long, ok: Boolean = true) =
        NarratorReviewEventEntity(0, hadith, narrator, at, ok)

    private fun done(outcome: SyncOutcome) = outcome as SyncOutcome.Done

    // -- A first meeting -------------------------------------------------------------------------

    @Test
    fun aPhoneWithProgressAndAnEmptyAccountUploadsEverything() = runTest {
        phone.data = UserData(listOf(record(1, 100), record(2, 200)), listOf(event(1, 10, 100), event(2, 11, 200)))
        prefs.setStartingPoint(StartingPoint.EXPERT)

        val outcome = done(useCase().sync("u1"))

        assertEquals(2, outcome.pushed)
        assertEquals(setOf(1L, 2L), account.mastery.keys)
        assertEquals(2, account.events.size)
        assertEquals(StartingPoint.EXPERT, account.point)
        assertEquals("u1", state.read().uid)
        assertEquals(5_000L, state.read().lastSyncedAt)
    }

    @Test
    fun anEmptyPhoneAndAnAccountWithProgressDownloadsEverything() = runTest {
        account.mastery[1] = record(1, 100)
        account.events += 1_010L to event(1, 10, 100)
        account.point = StartingPoint.INTERMEDIATE

        val outcome = done(useCase().sync("u1"))

        assertEquals(1, outcome.pulled)
        assertEquals(listOf(1L), phone.data.mastery.map { it.hadithId })
        assertEquals(1, phone.data.events.size)
        assertEquals(StartingPoint.INTERMEDIATE, prefs.startingPoint.value)
    }

    @Test
    fun whenBothHoldProgressTheLearnerIsAskedAndNothingMoves() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        account.mastery[2] = record(2, 200)
        account.mastery[3] = record(3, 300)

        val outcome = useCase().sync("u1")

        assertEquals(SyncOutcome.NeedsChoice(onPhone = 1, inAccount = 2), outcome)
        assertEquals(listOf(1L), phone.data.mastery.map { it.hadithId })
        assertEquals(setOf(2L, 3L), account.mastery.keys)
        assertEquals(0, account.pushes)
        assertNull(state.read().uid)
    }

    @Test
    fun mergingKeepsTheNewestRecordOfEachHadithFromBothSides() = runTest {
        phone.data = UserData(listOf(record(1, 500), record(2, 100)), emptyList())
        account.mastery[1] = record(1, 300)
        account.mastery[2] = record(2, 400)
        account.mastery[3] = record(3, 50)

        val outcome = done(useCase().sync("u1", FirstSyncChoice.MERGE))

        assertEquals(500L, account.mastery.getValue(1).matnLastReviewedAt)
        assertEquals(400L, phone.data.mastery.single { it.hadithId == 2L }.matnLastReviewedAt)
        assertEquals(setOf(1L, 2L, 3L), phone.data.mastery.map { it.hadithId }.toSet())
        assertEquals(2, outcome.pulled)
        assertEquals(1, outcome.pushed)
    }

    @Test
    fun theRepetitionCountSettlesATieInTime() = runTest {
        phone.data = UserData(listOf(record(1, 100, repetitions = 5)), emptyList())
        account.mastery[1] = record(1, 100, repetitions = 3)

        useCase().sync("u1", FirstSyncChoice.MERGE)

        assertEquals(5, account.mastery.getValue(1).repetitionCount)
    }

    @Test
    fun usingTheAccountReplacesThePhone() = runTest {
        phone.data = UserData(listOf(record(1, 100)), listOf(event(1, 10, 100)))
        account.mastery[2] = record(2, 200)
        account.events += 1_010L to event(2, 11, 200)

        useCase().sync("u1", FirstSyncChoice.USE_ACCOUNT)

        assertEquals(listOf(2L), phone.data.mastery.map { it.hadithId })
        assertEquals(listOf(2L), phone.data.events.map { it.hadithId })
        assertEquals(setOf(2L), account.mastery.keys)
        assertEquals(0, account.cleared)
    }

    @Test
    fun usingThePhoneReplacesTheAccount() = runTest {
        phone.data = UserData(listOf(record(1, 100)), listOf(event(1, 10, 100)))
        account.mastery[2] = record(2, 200)
        account.events += 1_010L to event(2, 11, 200)

        useCase().sync("u1", FirstSyncChoice.USE_THIS_PHONE)

        assertEquals(1, account.cleared)
        assertEquals(setOf(1L), account.mastery.keys)
        assertEquals(listOf(1L), account.events.map { it.second.hadithId })
        assertEquals(listOf(1L), phone.data.mastery.map { it.hadithId })
    }

    // -- Later syncs -----------------------------------------------------------------------------

    @Test
    fun narratorEventsOnBothSidesEndUpOnBoth() = runTest {
        phone.data = UserData(listOf(record(1, 100)), listOf(event(1, 10, 100), event(1, 11, 100)))
        account.events += 1_010L to event(2, 10, 150)

        useCase().sync("u1", FirstSyncChoice.MERGE)

        assertEquals(3, phone.data.events.size)
        assertEquals(3, account.events.size)
    }

    @Test
    fun aSecondSyncWithNothingNewMovesNothing() = runTest {
        phone.data = UserData(listOf(record(1, 100)), listOf(event(1, 10, 100)))
        useCase().sync("u1")
        val pushes = account.pushes

        val outcome = done(useCase().sync("u1"))

        assertEquals(0, outcome.pulled)
        assertEquals(0, outcome.pushed)
        assertEquals(pushes, account.pushes)
    }

    @Test
    fun onlyEventsReviewedSinceTheLastSyncAreSent() = runTest {
        phone.data = UserData(listOf(record(1, 100)), listOf(event(1, 10, 100)))
        useCase().sync("u1")

        phone.review(event(1, 10, 900))
        phone.data = phone.data.copy(mastery = listOf(record(1, 900, repetitions = 2)))
        useCase().sync("u1")

        assertEquals(setOf(100L, 900L), account.events.map { it.second.reviewedAt }.toSet())
        assertEquals(2, account.mastery.getValue(1).repetitionCount)
    }

    @Test
    fun anEventPulledFromAnotherDeviceIsNotSentBackAgain() = runTest {
        val other = FakePhone(UserData(listOf(record(1, 100)), listOf(event(1, 10, 100))))
        val otherState = InMemorySyncState()
        useCase(other, InMemoryStudyPreferences(), otherState).sync("u1")

        useCase().sync("u1")
        val pushes = account.pushes
        useCase().sync("u1")

        assertEquals(1, phone.data.events.size)
        assertEquals(pushes, account.pushes)
    }

    @Test
    fun recordsAndEventsOfContentThisAppDoesNotKnowAreLeftAlone() = runTest {
        account.mastery[99] = record(99, 100)
        account.events += 1_010L to event(99, 10, 100)
        account.events += 1_020L to event(1, 77, 100)
        account.mastery[1] = record(1, 100)
        val state = this@SyncProgressTest.state

        useCase().sync("u1")

        assertEquals(listOf(1L), phone.data.mastery.map { it.hadithId })
        assertTrue(phone.data.events.isEmpty())
        assertEquals(setOf(99L, 1L), account.mastery.keys)
        assertEquals(0L, state.read().eventCursor)
    }

    @Test
    fun aFailedSyncChangesNothingOnThePhoneOrInTheState() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        account.failing = true

        val failure = runCatching { useCase().sync("u1") }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(state.read().uid)
        assertEquals(listOf(1L), phone.data.mastery.map { it.hadithId })
    }

    // -- The starting point ----------------------------------------------------------------------

    @Test
    fun aStartingPointChangedOnThePhoneReachesTheAccount() = runTest {
        prefs.setStartingPoint(StartingPoint.BEGINNER)
        useCase().sync("u1")

        prefs.setStartingPoint(StartingPoint.EXPERT)
        useCase().sync("u1")

        assertEquals(StartingPoint.EXPERT, account.point)
    }

    @Test
    fun aStartingPointChangedInTheAccountReachesThePhone() = runTest {
        prefs.setStartingPoint(StartingPoint.BEGINNER)
        useCase().sync("u1")

        account.point = StartingPoint.INTERMEDIATE
        useCase().sync("u1")

        assertEquals(StartingPoint.INTERMEDIATE, prefs.startingPoint.value)
    }

    // -- Reset and restore -----------------------------------------------------------------------

    @Test
    fun aResetOnThePhoneEmptiesTheAccountToo() = runTest {
        phone.data = UserData(listOf(record(1, 100)), listOf(event(1, 10, 100)))
        useCase().sync("u1")

        phone.data = UserData.Empty
        useCase().replaceAccountWithThisPhone()
        useCase().sync("u1")

        assertTrue(account.mastery.isEmpty())
        assertTrue(account.events.isEmpty())
        assertTrue(phone.data.mastery.isEmpty())
        assertEquals(false, state.read().replacePending)
    }

    @Test
    fun aResetThatCouldNotReachTheAccountIsRememberedAndDoneOnTheNextSync() = runTest {
        phone.data = UserData(listOf(record(1, 100)), emptyList())
        useCase().sync("u1")
        phone.data = UserData.Empty
        useCase().replaceAccountWithThisPhone()
        account.failing = true
        runCatching { useCase().sync("u1") }
        assertEquals(true, state.read().replacePending)
        assertEquals(setOf(1L), account.mastery.keys)

        account.failing = false
        useCase().sync("u1")

        assertTrue(account.mastery.isEmpty())
        assertTrue(phone.data.mastery.isEmpty())
    }
}
