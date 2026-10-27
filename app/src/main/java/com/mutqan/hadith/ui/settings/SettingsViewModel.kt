package com.mutqan.hadith.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.account.AccountUser
import com.mutqan.hadith.domain.account.AuthRepository
import com.mutqan.hadith.domain.account.NoGoogleAccount
import com.mutqan.hadith.domain.account.SignInCancelled
import com.mutqan.hadith.domain.account.SignedOutAuth
import com.mutqan.hadith.domain.backup.BackupException
import com.mutqan.hadith.domain.backup.BackupFileStore
import com.mutqan.hadith.domain.backup.ExportUserDataUseCase
import com.mutqan.hadith.domain.backup.ImportUserDataUseCase
import com.mutqan.hadith.domain.backup.PreparedBackup
import com.mutqan.hadith.domain.backup.ResetProgressUseCase
import com.mutqan.hadith.domain.catalog.CatalogStatus
import com.mutqan.hadith.domain.catalog.LibraryUpdates
import com.mutqan.hadith.domain.catalog.NoLibraryUpdates
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.domain.settings.InMemoryStudyPreferences
import com.mutqan.hadith.domain.settings.StudyPreferences
import com.mutqan.hadith.domain.sync.AccountSync
import com.mutqan.hadith.domain.sync.DeleteAccountResult
import com.mutqan.hadith.domain.sync.FirstSyncChoice
import com.mutqan.hadith.domain.sync.NoAccountSync
import com.mutqan.hadith.domain.sync.SyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why a backup file was turned down; each has its own wording on screen. */
sealed interface ImportProblem {
    data object NotABackup : ImportProblem
    data object Corrupt : ImportProblem
    data class NewerVersion(val found: Int) : ImportProblem
    data object UnknownContent : ImportProblem
    data object TooLarge : ImportProblem
    data object Unreadable : ImportProblem

    /** The restore itself failed after the file checked out; the transaction left the data as it was. */
    data object RestoreFailed : ImportProblem
}

/** One-off outcomes, shown as a snackbar and then cleared. */
sealed interface SettingsMessage {
    data object Exported : SettingsMessage
    data object ExportFailed : SettingsMessage
    data object Imported : SettingsMessage
    data class ImportRejected(val problem: ImportProblem) : SettingsMessage
    data object ProgressReset : SettingsMessage
    data object ResetFailed : SettingsMessage
    data object SignInFailed : SettingsMessage
    data object NoGoogleAccountOnDevice : SettingsMessage
    data object SignedOut : SettingsMessage
    data object AccountDeleted : SettingsMessage
    data object AccountDeleteFailed : SettingsMessage

    /** The progress in the account is gone but the account itself could not be deleted; the learner was signed out. */
    data object AccountDeletedPartly : SettingsMessage
}

data class SettingsUi(
    val isBusy: Boolean = false,
    val message: SettingsMessage? = null,
    /** A backup that was read and checked and now waits for the user to confirm replacing their data. */
    val pendingImport: PreparedBackup? = null,
    /** The "advanced student" choice: every layer open on every hadith. */
    val unlockAllLayers: Boolean = false,
    /** What the learner said about where they start in the Forty Hadith; null until they have been asked. */
    val startingPoint: StartingPoint? = null,
    /** The Google account signed in, or null: the app works the same without one. */
    val account: AccountUser? = null,
    /** Where the sync of the progress with that account stands. */
    val syncStatus: SyncStatus = SyncStatus.Idle,
    /** When the progress last reached the account (epoch millis); null if it never has. */
    val lastSyncedAt: Long? = null,
    /** Where the check for a newer library stands. */
    val libraryStatus: CatalogStatus = CatalogStatus.Idle,
    /** When the cloud was last asked for a newer library, and when the library last changed because of it. */
    val libraryCheckedAt: Long? = null,
    val libraryUpdatedAt: Long? = null,
)

class SettingsViewModel(
    private val exportUserData: ExportUserDataUseCase,
    private val importUserData: ImportUserDataUseCase,
    private val resetProgress: ResetProgressUseCase,
    private val files: BackupFileStore,
    private val studyPreferences: StudyPreferences = InMemoryStudyPreferences(),
    private val auth: AuthRepository = SignedOutAuth(),
    private val accountSync: AccountSync = NoAccountSync(),
    private val libraryUpdates: LibraryUpdates = NoLibraryUpdates(),
) : ViewModel() {

    private val _state = MutableStateFlow(
        SettingsUi(
            unlockAllLayers = studyPreferences.unlockAllLayers.value,
            startingPoint = studyPreferences.startingPoint.value,
            account = auth.user.value,
            syncStatus = accountSync.status.value,
            lastSyncedAt = accountSync.lastSyncedAt.value,
            libraryStatus = libraryUpdates.status.value,
            libraryCheckedAt = libraryUpdates.checkedAt.value,
            libraryUpdatedAt = libraryUpdates.updatedAt.value,
        ),
    )
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

    init {
        // The starting point is changed on a screen of its own, above this one; coming back shows the new answer.
        studyPreferences.startingPoint.onEach { point -> _state.update { it.copy(startingPoint = point) } }.launchIn(viewModelScope)
        auth.user.onEach { account -> _state.update { it.copy(account = account) } }.launchIn(viewModelScope)
        accountSync.status.onEach { status -> _state.update { it.copy(syncStatus = status) } }.launchIn(viewModelScope)
        accountSync.lastSyncedAt.onEach { at -> _state.update { it.copy(lastSyncedAt = at) } }.launchIn(viewModelScope)
        libraryUpdates.status.onEach { status -> _state.update { it.copy(libraryStatus = status) } }.launchIn(viewModelScope)
        libraryUpdates.checkedAt.onEach { at -> _state.update { it.copy(libraryCheckedAt = at) } }.launchIn(viewModelScope)
        libraryUpdates.updatedAt.onEach { at -> _state.update { it.copy(libraryUpdatedAt = at) } }.launchIn(viewModelScope)
    }

    /** Looks for a newer library now, instead of waiting for the next time the app opens. */
    fun checkLibrary() = libraryUpdates.refresh(force = true)

    /** Syncs the progress with the account now, instead of waiting for the next trigger. */
    fun syncNow() = accountSync.syncNow()

    /** The learner's answer when the phone and the account both hold progress. */
    fun chooseSync(choice: FirstSyncChoice) = accountSync.choose(choice)

    /** The name the file picker offers for a new backup. */
    fun suggestedBackupName(): String = exportUserData.suggestedFileName()

    /** Writes a backup to the document the user chose with the file picker. */
    fun exportTo(target: String) = runExclusively {
        val message = try {
            val backup = exportUserData()
            files.write(target, backup.json)
            SettingsMessage.Exported
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SettingsMessage.ExportFailed
        }
        _state.update { it.copy(message = message) }
    }

    /** Reads and checks the document the user chose; on success the screen asks for confirmation. */
    fun readBackup(source: String) = runExclusively {
        val text = try {
            files.read(source)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupException.TooLarge) {
            return@runExclusively reject(ImportProblem.TooLarge)
        } catch (e: Exception) {
            return@runExclusively reject(ImportProblem.Unreadable)
        }
        try {
            val prepared = importUserData.prepare(text)
            _state.update { it.copy(pendingImport = prepared) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reject(problemOf(e))
        }
    }

    fun cancelImport() = _state.update { it.copy(pendingImport = null) }

    /** Replaces the user's data with the backup that was read, all or nothing. */
    fun confirmImport() {
        val prepared = _state.value.pendingImport ?: return
        runExclusively {
            _state.update { it.copy(pendingImport = null) }
            try {
                importUserData.apply(prepared)
                accountSync.replaceAccountWithThisPhone()
                _state.update { it.copy(message = SettingsMessage.Imported) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reject(problemOf(e))
            }
        }
    }

    fun reset() = runExclusively {
        val message = try {
            resetProgress()
            accountSync.replaceAccountWithThisPhone()
            SettingsMessage.ProgressReset
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SettingsMessage.ResetFailed
        }
        _state.update { it.copy(message = message) }
    }

    /** Opens all the layers of every hadith, or goes back to opening them as the memory holds. */
    fun setUnlockAllLayers(enabled: Boolean) {
        studyPreferences.setUnlockAllLayers(enabled)
        _state.update { it.copy(unlockAllLayers = enabled) }
    }

    /**
     * Signs in with the Google account [chooseToken] gets from the person (it opens the system's account chooser).
     * Leaving the chooser is not an error and says nothing.
     */
    fun signIn(chooseToken: suspend () -> String) = runExclusively {
        val message = try {
            auth.signInWithGoogle(chooseToken())
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInCancelled) {
            null
        } catch (e: NoGoogleAccount) {
            SettingsMessage.NoGoogleAccountOnDevice
        } catch (e: Exception) {
            SettingsMessage.SignInFailed
        }
        if (message != null) _state.update { it.copy(message = message) }
    }

    /**
     * Deletes the progress held in the account and then the account, after the person picks their Google account again
     * with [chooseToken] to prove it is theirs. The progress on the phone stays where it is.
     */
    fun deleteAccount(chooseToken: suspend () -> String) = runExclusively {
        val message = try {
            when (val result = accountSync.deleteAccount(chooseToken())) {
                DeleteAccountResult.Deleted -> SettingsMessage.AccountDeleted
                DeleteAccountResult.NotSignedIn -> null
                is DeleteAccountResult.Failed ->
                    if (result.dataRemoved) SettingsMessage.AccountDeletedPartly else SettingsMessage.AccountDeleteFailed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SignInCancelled) {
            null
        } catch (e: NoGoogleAccount) {
            SettingsMessage.NoGoogleAccountOnDevice
        } catch (e: Exception) {
            SettingsMessage.AccountDeleteFailed
        }
        if (message != null) _state.update { it.copy(message = message) }
    }

    /** Signs out; the progress on the phone stays where it is. */
    fun signOut() {
        auth.signOut()
        _state.update { it.copy(message = SettingsMessage.SignedOut) }
    }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun reject(problem: ImportProblem) = _state.update { it.copy(message = SettingsMessage.ImportRejected(problem)) }

    private fun problemOf(e: Exception): ImportProblem = when (e) {
        is BackupException.NotABackup -> ImportProblem.NotABackup
        is BackupException.Corrupt -> ImportProblem.Corrupt
        is BackupException.UnsupportedVersion -> ImportProblem.NewerVersion(e.found)
        is BackupException.UnknownContent -> ImportProblem.UnknownContent
        is BackupException.TooLarge -> ImportProblem.TooLarge
        else -> ImportProblem.RestoreFailed
    }

    /** One operation at a time: taps on a second action while one runs are ignored. */
    private fun runExclusively(block: suspend () -> Unit) {
        if (_state.value.isBusy) return
        _state.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            try {
                block()
            } finally {
                _state.update { it.copy(isBusy = false) }
            }
        }
    }
}
