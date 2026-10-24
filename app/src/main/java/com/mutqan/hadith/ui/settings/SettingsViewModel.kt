package com.mutqan.hadith.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.backup.BackupException
import com.mutqan.hadith.domain.backup.BackupFileStore
import com.mutqan.hadith.domain.backup.ExportUserDataUseCase
import com.mutqan.hadith.domain.backup.ImportUserDataUseCase
import com.mutqan.hadith.domain.backup.PreparedBackup
import com.mutqan.hadith.domain.backup.ResetProgressUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

data class SettingsUi(
    val isBusy: Boolean = false,
    val message: SettingsMessage? = null,
    /** A backup that was read and checked and now waits for the user to confirm replacing their data. */
    val pendingImport: PreparedBackup? = null,
)

class SettingsViewModel(
    private val exportUserData: ExportUserDataUseCase,
    private val importUserData: ImportUserDataUseCase,
    private val resetProgress: ResetProgressUseCase,
    private val files: BackupFileStore,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUi())
    val state: StateFlow<SettingsUi> = _state.asStateFlow()

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
            SettingsMessage.ProgressReset
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SettingsMessage.ResetFailed
        }
        _state.update { it.copy(message = message) }
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
