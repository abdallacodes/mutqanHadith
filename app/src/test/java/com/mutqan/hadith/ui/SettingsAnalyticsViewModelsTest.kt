package com.mutqan.hadith.ui

import com.mutqan.hadith.data.entity.MasteryRecordEntity
import com.mutqan.hadith.data.entity.NarratorReviewEventEntity
import com.mutqan.hadith.domain.FakeHadithRepository
import com.mutqan.hadith.domain.FakeNarratorRepository
import com.mutqan.hadith.domain.FakeReviewRepository
import com.mutqan.hadith.domain.backup.BackupCodec
import com.mutqan.hadith.domain.backup.BackupException
import com.mutqan.hadith.domain.backup.BackupFileStore
import com.mutqan.hadith.domain.backup.BackupRepository
import com.mutqan.hadith.domain.backup.ExportUserDataUseCase
import com.mutqan.hadith.domain.backup.ImportUserDataUseCase
import com.mutqan.hadith.domain.backup.LibraryIds
import com.mutqan.hadith.domain.backup.ResetProgressUseCase
import com.mutqan.hadith.domain.backup.UserData
import com.mutqan.hadith.domain.hadith
import com.mutqan.hadith.domain.narrator
import com.mutqan.hadith.domain.repository.HadithRepository
import com.mutqan.hadith.domain.scheduler.DualDecayScheduler
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.usecase.GetMasteryAnalyticsUseCase
import com.mutqan.hadith.ui.analytics.AnalyticsViewModel
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.settings.ImportProblem
import com.mutqan.hadith.ui.settings.SettingsMessage
import com.mutqan.hadith.ui.settings.SettingsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException

/** The settings (backup, restore, reset) and analytics ViewModels over fakes. Backup text needs org.json, hence Robolectric. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAnalyticsViewModelsTest {

    private class FakeBackupRepository(
        var data: UserData = UserData.Empty,
        val library: LibraryIds = LibraryIds(hadithIds = setOf(1, 2, 3), narratorIds = setOf(10, 11)),
    ) : BackupRepository {
        var replaceCalls = 0
        var clearCalls = 0
        var failReplace = false
        var failClear = false

        override suspend fun readUserData() = data
        override suspend fun libraryIds() = library
        override suspend fun replaceUserData(data: UserData) {
            replaceCalls++
            if (failReplace) throw IOException("disk full")
            this.data = data
        }

        override suspend fun clearUserData() {
            clearCalls++
            if (failClear) throw IOException("locked")
            data = UserData.Empty
        }
    }

    private class FakeFileStore : BackupFileStore {
        val files = mutableMapOf<String, String>()
        var failWrite = false
        var readError: Exception? = null
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun write(target: String, text: String) {
            if (failWrite) throw IOException("read-only")
            files[target] = text
        }

        override suspend fun read(source: String): String {
            gate?.await()
            readError?.let { throw it }
            return files[source] ?: throw FileNotFoundException(source)
        }
    }

    private val now = 1_760_000_000_000L
    private val mastery = listOf(
        MasteryRecordEntity(1, 3.5f, 2.25f, now - 1000, now - 500, now + 10_000, 3),
        MasteryRecordEntity(2, 1f, 0f, now - 2000, null, now, 1),
    )
    private val events = listOf(
        NarratorReviewEventEntity(id = 1, hadithId = 1, narratorId = 10, reviewedAt = now - 500, succeeded = true),
        NarratorReviewEventEntity(id = 2, hadithId = 1, narratorId = 11, reviewedAt = now - 500, succeeded = false),
    )

    private val backup = FakeBackupRepository(UserData(mastery, events))
    private val files = FakeFileStore()
    private val importer = ImportUserDataUseCase(backup)
    private val vm get() = viewModel

    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        viewModel = SettingsViewModel(ExportUserDataUseCase(backup, { now }), importer, ResetProgressUseCase(backup), files)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun backupText(data: UserData = UserData(mastery, events)) = BackupCodec.encode(data, now)

    // -- The advanced student's choice ------------------------------------------------------

    private fun viewModelOver(preferences: InMemoryStudyPreferences) =
        SettingsViewModel(ExportUserDataUseCase(backup, { now }), importer, ResetProgressUseCase(backup), files, preferences)

    @Test
    fun advanced_isOffUntilChosenAndTheChoiceIsKept() {
        val preferences = InMemoryStudyPreferences()
        val settings = viewModelOver(preferences)
        assertFalse(settings.state.value.unlockAllLayers)

        settings.setUnlockAllLayers(true)

        assertTrue(settings.state.value.unlockAllLayers)
        assertTrue("the scheduler reads the same stored choice", preferences.unlockAllLayers.value)
        assertTrue("a screen opened later starts from it", viewModelOver(preferences).state.value.unlockAllLayers)

        settings.setUnlockAllLayers(false)

        assertFalse(settings.state.value.unlockAllLayers)
        assertFalse(preferences.unlockAllLayers.value)
    }

    @Test
    fun advanced_resettingProgressLeavesTheChoiceAlone() = runTest {
        val preferences = InMemoryStudyPreferences().also { it.setUnlockAllLayers(true) }
        val settings = viewModelOver(preferences)

        settings.reset()

        assertTrue(settings.state.value.unlockAllLayers)
        assertTrue(preferences.unlockAllLayers.value)
    }

    // -- Export -----------------------------------------------------------------------------

    @Test
    fun export_writesTheBackupToTheChosenDocument() = runTest {
        vm.exportTo("content://picked/backup.json")

        val ui = vm.state.value
        assertEquals(SettingsMessage.Exported, ui.message)
        assertFalse(ui.isBusy)
        val written = BackupCodec.decode(files.files.getValue("content://picked/backup.json"))
        assertEquals(UserData(mastery, events), written.data)
        assertEquals(now, written.exportedAt)
    }

    @Test
    fun export_aFailingWriteIsReportedNotThrown() = runTest {
        files.failWrite = true

        vm.exportTo("content://picked/backup.json")

        assertEquals(SettingsMessage.ExportFailed, vm.state.value.message)
        assertFalse(vm.state.value.isBusy)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun export_offersADatedFileName() {
        assertEquals("mutqan-hadith-backup-", vm.suggestedBackupName().take(21))
        assertTrue(vm.suggestedBackupName().endsWith(".json"))
    }

    // -- Import -----------------------------------------------------------------------------

    @Test
    fun import_aValidFileWaitsForConfirmationAndChangesNothing() = runTest {
        backup.data = UserData.Empty
        files.files["src"] = backupText()

        vm.readBackup("src")

        val pending = vm.state.value.pendingImport!!
        assertEquals(2, pending.masteryCount)
        assertEquals(2, pending.eventCount)
        assertEquals(now, pending.exportedAt)
        assertNull(vm.state.value.message)
        assertEquals(0, backup.replaceCalls)
        assertEquals(UserData.Empty, backup.data)
    }

    @Test
    fun import_confirmingReplacesTheDataOnce() = runTest {
        backup.data = UserData.Empty
        files.files["src"] = backupText()
        vm.readBackup("src")

        vm.confirmImport()

        assertEquals(UserData(mastery, events), backup.data)
        assertEquals(1, backup.replaceCalls)
        assertEquals(SettingsMessage.Imported, vm.state.value.message)
        assertNull(vm.state.value.pendingImport)
        assertFalse(vm.state.value.isBusy)

        vm.confirmImport() // nothing pending any more
        assertEquals(1, backup.replaceCalls)
    }

    @Test
    fun import_cancellingDropsThePendingBackup() = runTest {
        files.files["src"] = backupText()
        vm.readBackup("src")

        vm.cancelImport()

        assertNull(vm.state.value.pendingImport)
        assertEquals(0, backup.replaceCalls)
        vm.confirmImport()
        assertEquals(0, backup.replaceCalls)
    }

    @Test
    fun import_aFailedRestoreLeavesTheDataAndSaysSo() = runTest {
        files.files["src"] = backupText(UserData(listOf(mastery[0].copy(repetitionCount = 99)), emptyList()))
        vm.readBackup("src")
        backup.failReplace = true

        vm.confirmImport()

        assertEquals(SettingsMessage.ImportRejected(ImportProblem.RestoreFailed), vm.state.value.message)
        assertEquals(UserData(mastery, events), backup.data)
        assertNull(vm.state.value.pendingImport)
        assertFalse(vm.state.value.isBusy)
    }

    private fun rejection(text: String): ImportProblem {
        files.files["src"] = text
        vm.readBackup("src")
        assertNull("nothing may wait for confirmation after a rejection", vm.state.value.pendingImport)
        return (vm.state.value.message as SettingsMessage.ImportRejected).problem
    }

    @Test
    fun import_everyKindOfBadFileGetsItsOwnReason() = runTest {
        assertEquals(ImportProblem.NotABackup, rejection("hello"))
        vm.messageShown()
        assertEquals(ImportProblem.NotABackup, rejection(""))
        vm.messageShown()

        val newer = JSONObject(backupText()).put("version", 7).toString()
        assertEquals(ImportProblem.NewerVersion(7), rejection(newer))
        vm.messageShown()

        val damaged = JSONObject(backupText()).apply { getJSONArray("masteryRecords").getJSONObject(0).put("hadithId", "one") }.toString()
        assertEquals(ImportProblem.Corrupt, rejection(damaged))
        vm.messageShown()

        val foreign = backupText(UserData(listOf(mastery[0].copy(hadithId = 500)), emptyList()))
        assertEquals(ImportProblem.UnknownContent, rejection(foreign))
        assertEquals(0, backup.replaceCalls)
    }

    @Test
    fun import_aFileThatCannotBeReadIsReportedAsSuch() = runTest {
        vm.readBackup("missing")
        assertEquals(SettingsMessage.ImportRejected(ImportProblem.Unreadable), vm.state.value.message)
        vm.messageShown()

        files.readError = SecurityException("permission revoked")
        vm.readBackup("missing")
        assertEquals(SettingsMessage.ImportRejected(ImportProblem.Unreadable), vm.state.value.message)
        vm.messageShown()

        files.readError = BackupException.TooLarge(1)
        vm.readBackup("missing")
        assertEquals(SettingsMessage.ImportRejected(ImportProblem.TooLarge), vm.state.value.message)
        assertFalse(vm.state.value.isBusy)
    }

    // -- Reset ------------------------------------------------------------------------------

    @Test
    fun reset_clearsTheUserData() = runTest {
        vm.reset()

        assertEquals(1, backup.clearCalls)
        assertEquals(UserData.Empty, backup.data)
        assertEquals(SettingsMessage.ProgressReset, vm.state.value.message)
        assertFalse(vm.state.value.isBusy)
    }

    @Test
    fun reset_aFailureIsReportedAndTheDataStays() = runTest {
        backup.failClear = true

        vm.reset()

        assertEquals(SettingsMessage.ResetFailed, vm.state.value.message)
        assertEquals(UserData(mastery, events), backup.data)
    }

    // -- Messages and exclusivity -----------------------------------------------------------

    @Test
    fun messageShownClearsTheMessage() = runTest {
        vm.reset()
        assertEquals(SettingsMessage.ProgressReset, vm.state.value.message)

        vm.messageShown()

        assertNull(vm.state.value.message)
    }

    @Test
    fun whileOneOperationRunsOthersAreIgnored() = runTest {
        files.files["src"] = backupText()
        files.gate = CompletableDeferred()

        vm.readBackup("src") // suspends on the gate
        assertTrue(vm.state.value.isBusy)
        vm.reset()
        vm.exportTo("target")
        assertEquals(0, backup.clearCalls)
        assertFalse(files.files.containsKey("target"))

        files.gate!!.complete(Unit)

        assertFalse(vm.state.value.isBusy)
        assertEquals(2, vm.state.value.pendingImport!!.masteryCount)
        assertEquals(0, backup.clearCalls)
    }

    // -- Analytics --------------------------------------------------------------------------

    private val library = (1L..3L).map { hadith(it, listOf(narrator(10, "عروة"), narrator(11, "نافع"))) }
    private val reviews = FakeReviewRepository()

    private fun analyticsVm(hadiths: HadithRepository = FakeHadithRepository(library)) = AnalyticsViewModel(
        GetMasteryAnalyticsUseCase(
            hadiths, FakeNarratorRepository(listOf(narrator(10, "عروة"), narrator(11, "نافع"))), reviews, DualDecayScheduler(), { now },
        ),
    )

    @Test
    fun analytics_loadsTheFiguresAndRefreshesThem() = runTest {
        val vm = analyticsVm()
        val first = (vm.state.value as UiState.Success).data
        assertEquals(3, first.status.unseen)

        reviews.mastery[1] = MasteryRecordEntity(1, 2f, 2f, now, now, now + 1, 1)
        vm.refresh()

        val second = (vm.state.value as UiState.Success).data
        assertEquals(2, second.status.unseen)
        assertEquals(1, second.status.learning)
    }

    @Test
    fun analytics_aFailureIsAnErrorStateAndRetryRecovers() = runTest {
        var broken = true
        val flaky = object : HadithRepository by FakeHadithRepository(library) {
            override suspend fun getAll() = if (broken) error("db closed") else FakeHadithRepository(library).getAll()
        }
        val vm = analyticsVm(flaky)
        assertEquals(UiState.Error("db closed"), vm.state.value)

        broken = false
        vm.retry()

        assertTrue(vm.state.value is UiState.Success)
    }

    @Test
    fun analytics_aFailedRefreshKeepsTheFiguresOnScreen() = runTest {
        var broken = false
        val flaky = object : HadithRepository by FakeHadithRepository(library) {
            override suspend fun getAll() = if (broken) error("db closed") else FakeHadithRepository(library).getAll()
        }
        val vm = analyticsVm(flaky)
        val before = vm.state.value
        assertTrue(before is UiState.Success)

        broken = true
        vm.refresh()

        assertEquals(before, vm.state.value)
    }
}
