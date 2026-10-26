package com.mutqan.hadith.ui.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.backup.PreparedBackup
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.labelRes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the file picker should offer when choosing a backup to restore; providers label JSON inconsistently. */
private val BACKUP_MIME_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onClose: () -> Unit, onOpenPlacement: () -> Unit = {}) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { viewModel.exportTo(it.toString()) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.readBackup(it.toString()) }
    }
    val context = LocalContext.current
    val languages = remember(context) { AppLanguageController(context) }
    SettingsScreen(
        ui = ui,
        appVersion = remember(context) { appVersionName(context) },
        language = remember(languages) { languages.current() },
        actions = SettingsActions(
            onBack = onClose,
            onExport = { exportLauncher.launch(viewModel.suggestedBackupName()) },
            onImport = { importLauncher.launch(BACKUP_MIME_TYPES) },
            onConfirmImport = viewModel::confirmImport,
            onCancelImport = viewModel::cancelImport,
            onConfirmReset = viewModel::reset,
            onMessageShown = viewModel::messageShown,
            // The activity is recreated by the change, which re-reads every string and the layout direction.
            onLanguageSelected = languages::select,
            onUnlockAllLayersChanged = viewModel::setUnlockAllLayers,
            onOpenPlacement = onOpenPlacement,
        ),
    )
}

/** What the settings screen can ask for; the pickers and the data work live outside it. */
data class SettingsActions(
    val onBack: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
    val onConfirmImport: () -> Unit,
    val onCancelImport: () -> Unit,
    val onConfirmReset: () -> Unit,
    val onMessageShown: () -> Unit,
    val onLanguageSelected: (AppLanguage) -> Unit = {},
    val onUnlockAllLayersChanged: (Boolean) -> Unit = {},
    val onOpenPlacement: () -> Unit = {},
)

@Composable
fun SettingsScreen(ui: SettingsUi, appVersion: String, actions: SettingsActions, language: AppLanguage = AppLanguage.ARABIC) {
    val snackbar = remember { SnackbarHostState() }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    var showingAbout by rememberSaveable { mutableStateOf(false) }
    var choosingLanguage by rememberSaveable { mutableStateOf(false) }

    val message = ui.message?.let { messageText(it) }
    LaunchedEffect(ui.message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            actions.onMessageShown()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(stringResource(R.string.settings_title), onBack = actions.onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.contentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (ui.isBusy) {
                    val busy = stringResource(R.string.settings_busy)
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = busy })
                }
                SettingsGroup(stringResource(R.string.settings_section_general)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_language),
                        supporting = stringResource(language.nameRes),
                        onClick = { choosingLanguage = true },
                    )
                }
                SettingsGroup(stringResource(R.string.settings_section_study)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_starting_point),
                        supporting = ui.startingPoint?.let { stringResource(R.string.settings_starting_point_hint, stringResource(it.labelRes)) }
                            ?: stringResource(R.string.settings_starting_point_unset),
                        onClick = actions.onOpenPlacement,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_unlock_all),
                        supporting = stringResource(R.string.settings_unlock_all_hint),
                        checked = ui.unlockAllLayers,
                        onCheckedChange = actions.onUnlockAllLayersChanged,
                    )
                }
                SettingsGroup(stringResource(R.string.settings_section_backup)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_export),
                        supporting = stringResource(R.string.settings_export_hint),
                        enabled = !ui.isBusy,
                        onClick = actions.onExport,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow(
                        title = stringResource(R.string.settings_import),
                        supporting = stringResource(R.string.settings_import_hint),
                        enabled = !ui.isBusy,
                        onClick = actions.onImport,
                    )
                }
                SettingsGroup(stringResource(R.string.settings_section_data)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_reset),
                        supporting = stringResource(R.string.settings_reset_hint),
                        enabled = !ui.isBusy,
                        destructive = true,
                        onClick = { confirmingReset = true },
                    )
                }
                SettingsGroup(stringResource(R.string.settings_section_about)) {
                    SettingsRow(
                        title = stringResource(R.string.settings_about),
                        supporting = stringResource(R.string.settings_about_hint, appVersion),
                        onClick = { showingAbout = true },
                    )
                }
            }
        }
    }

    if (confirmingReset) {
        ConfirmResetDialog(
            onConfirm = { confirmingReset = false; actions.onConfirmReset() },
            onDismiss = { confirmingReset = false },
        )
    }
    ui.pendingImport?.let { prepared ->
        ConfirmImportDialog(prepared, onConfirm = actions.onConfirmImport, onDismiss = actions.onCancelImport)
    }
    if (showingAbout) AboutDialog(appVersion, onDismiss = { showingAbout = false })
    if (choosingLanguage) {
        LanguageDialog(
            selected = language,
            onSelect = { chosen ->
                choosingLanguage = false
                if (chosen != language) actions.onLanguageSelected(chosen)
            },
            onDismiss = { choosingLanguage = false },
        )
    }
}

@Composable
private fun messageText(message: SettingsMessage): String = when (message) {
    SettingsMessage.Exported -> stringResource(R.string.settings_msg_exported)
    SettingsMessage.ExportFailed -> stringResource(R.string.settings_msg_export_failed)
    SettingsMessage.Imported -> stringResource(R.string.settings_msg_imported)
    SettingsMessage.ProgressReset -> stringResource(R.string.settings_msg_reset)
    SettingsMessage.ResetFailed -> stringResource(R.string.settings_msg_reset_failed)
    is SettingsMessage.ImportRejected -> when (val problem = message.problem) {
        ImportProblem.NotABackup -> stringResource(R.string.settings_err_not_backup)
        ImportProblem.Corrupt -> stringResource(R.string.settings_err_corrupt)
        is ImportProblem.NewerVersion -> stringResource(R.string.settings_err_newer, problem.found)
        ImportProblem.UnknownContent -> stringResource(R.string.settings_err_unknown)
        ImportProblem.TooLarge -> stringResource(R.string.settings_err_too_large)
        ImportProblem.Unreadable -> stringResource(R.string.settings_err_unreadable)
        ImportProblem.RestoreFailed -> stringResource(R.string.settings_err_restore_failed)
    }
}

// -- List ------------------------------------------------------------------------------------

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) { Column { content() } }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val titleColor = if (destructive) scheme.error else scheme.onSurface
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) titleColor else titleColor.copy(alpha = 0.45f),
            )
            Text(
                supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.outline,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** A setting that is on or off: the whole row is the switch, so it is easy to hit and reads as one control. */
@Composable
private fun SettingsSwitchRow(title: String, supporting: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            Text(supporting, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

// -- Dialogs ---------------------------------------------------------------------------------

@Composable
private fun ConfirmResetDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reset_title)) },
        text = { Text(stringResource(R.string.settings_reset_body), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_reset_confirm), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun ConfirmImportDialog(prepared: PreparedBackup, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.settings_import_body, prepared.masteryCount, prepared.eventCount, formatDay(prepared.exportedAt)),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (prepared.skippedHadiths > 0) {
                    Text(
                        stringResource(R.string.settings_import_skipped, prepared.skippedHadiths),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.settings_import_confirm), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun LanguageDialog(selected: AppLanguage, onSelect: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { language ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = language == selected, role = Role.RadioButton, onClick = { onSelect(language) })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The row is the one selectable element; the button only shows its state.
                        RadioButton(selected = language == selected, onClick = null)
                        Text(stringResource(language.nameRes), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** The about dialog: the app, the suite it belongs to, the method it is built on and where its hadiths come from. */
@Composable
fun AboutDialog(appVersion: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { AboutTitle() },
        text = { AboutBody(appVersion) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_ok)) } },
    )
}

@Composable
internal fun AboutTitle() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.app_name))
        Text(stringResource(R.string.about_suite), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

/** The dialog's text without its window, so it can be rendered on its own. */
@Composable
internal fun AboutBody(appVersion: String) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            stringResource(R.string.about_version, appVersion),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AboutSection(stringResource(R.string.about_vision_title), listOf(stringResource(R.string.about_vision_body)))
        AboutSection(
            stringResource(R.string.about_method_title),
            listOf(
                stringResource(R.string.about_method_layers),
                stringResource(R.string.about_method_recall),
                stringResource(R.string.about_method_decay),
                stringResource(R.string.about_method_pivots),
                stringResource(R.string.about_method_privacy),
            ),
        )
        AboutSection(
            stringResource(R.string.about_sources_title),
            listOf(
                stringResource(R.string.about_sources_lk),
                stringResource(R.string.about_sources_text),
                stringResource(R.string.about_sources_credits),
            ),
        )
    }
}

@Composable
private fun AboutSection(title: String, paragraphs: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

// -- Helpers ---------------------------------------------------------------------------------

/** `2026-10-24`: fixed Latin digits like the rest of the app, whatever the device locale prints. */
private fun formatDay(epochMillis: Long): String =
    DateTimeFormatter.ISO_LOCAL_DATE.withLocale(Locale.ROOT).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** The installed version name, or a dash where the package manager cannot say. */
fun appVersionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
} catch (e: Exception) {
    "—"
}
