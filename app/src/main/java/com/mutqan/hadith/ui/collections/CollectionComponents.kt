package com.mutqan.hadith.ui.collections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.CollectionAccent
import com.mutqan.hadith.domain.model.CollectionSummary
import com.mutqan.hadith.domain.model.CollectionTitle
import com.mutqan.hadith.domain.model.TitleProblem
import com.mutqan.hadith.ui.common.BookmarkIcon

/** Why a name was not accepted, in the user's language. */
@Composable
internal fun TitleProblem.message(): String = when (this) {
    TitleProblem.EMPTY -> stringResource(R.string.collection_error_empty)
    TitleProblem.TOO_LONG -> stringResource(R.string.collection_error_too_long, CollectionTitle.MAX_LENGTH)
    TitleProblem.DUPLICATE -> stringResource(R.string.collection_error_duplicate)
}

/** The line under a name field: the name's problem, or a failure to save, or nothing. */
@Composable
private fun editorMessage(editor: EditorState, tooLong: Boolean): String? = when {
    tooLong -> TitleProblem.TOO_LONG.message()
    editor.problem != null -> editor.problem.message()
    editor.failed -> stringResource(R.string.collection_error_failed)
    else -> null
}

/** «٣ أحاديث»: how many hadiths a collection holds. */
@Composable
internal fun hadithCountText(count: Int): String = pluralStringResource(R.plurals.hadith_count, count, count)

/**
 * The name and color of a collection, asked for in a bottom sheet like the one that files a hadith: to make one ([editing]
 * false) or to change one. The name is tidied and checked by the domain; what is wrong with it comes back in [editor] and
 * is said under the field.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionEditorSheet(
    editing: Boolean,
    initialTitle: String,
    initialAccent: Int,
    editor: EditorState,
    onSave: (title: String, accent: Int) -> Unit,
    /** The user is typing again: whatever was refused before no longer stands. */
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var accent by rememberSaveable { mutableIntStateOf(CollectionAccent.coerce(initialAccent)) }
    val tooLong = CollectionTitle.clean(title).length > CollectionTitle.MAX_LENGTH
    val canSave = title.isNotBlank() && !tooLong && !editor.saving
    val message = editorMessage(editor, tooLong)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(if (editing) R.string.collection_editor_edit_title else R.string.collection_editor_new_title),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(MAX_TYPED); onEdited() },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                label = { Text(stringResource(R.string.collection_name_label)) },
                singleLine = true,
                isError = message != null,
                supportingText = message?.let { { Text(it) } },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(title, accent) }),
            )
            Text(stringResource(R.string.collection_accent_title), style = MaterialTheme.typography.labelLarge)
            AccentPicker(accent) { accent = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.collection_cancel)) }
                Button(onClick = { onSave(title, accent) }, enabled = canSave, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (editing) R.string.collection_save else R.string.collection_create))
                }
            }
        }
    }
}

/** More than this is never typed into a name field; the domain's limit is lower, and says so. */
private const val MAX_TYPED = 100

@Composable
private fun AccentPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(CollectionAccent.COUNT) { index ->
            val color = collectionAccentColor(index)
            val label = stringResource(R.string.collection_accent_label, index + 1)
            Box(
                Modifier
                    .size(40.dp)
                    .selectable(selected = index == selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(if (index == selected) 32.dp else 26.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(if (index == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.surface, CircleShape) else Modifier),
                )
            }
        }
    }
}

/** Asked before a collection is deleted: it says what goes (the collection) and what stays (the hadiths and the progress). */
@Composable
fun DeleteCollectionDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.collection_delete_title)) },
        text = { Text(stringResource(R.string.collection_delete_body, title), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.collection_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.collection_cancel)) } },
    )
}

/** A collection in a list: its color, its name and how many hadiths it holds. */
@Composable
fun CollectionCard(collection: CollectionSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = collectionAccentColor(collection.accent)
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(collection.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        hadithCountText(collection.hadithCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BookmarkIcon(filled = collection.hadithCount > 0, tint = accent)
            }
        }
    }
}

/**
 * File one hadith into the user's collections: every collection with a check for the ones it is already in, and a field
 * at the bottom to start a new one that the hadith joins at once. A tap changes the database straight away, so there is
 * nothing to confirm; closing the sheet is just closing it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToCollectionSheet(
    choices: List<CollectionChoice>,
    editor: EditorState,
    onToggle: (collectionId: Long, member: Boolean) -> Unit,
    /** Creates a collection holding this hadith; calls the second function once it is made, so the field can be emptied. */
    onCreate: (title: String, onSaved: () -> Unit) -> Unit,
    onEdited: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val tooLong = CollectionTitle.clean(name).length > CollectionTitle.MAX_LENGTH
    val canCreate = name.isNotBlank() && !tooLong && !editor.saving
    val message = editorMessage(editor, tooLong)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.collect_action), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(if (choices.isEmpty()) R.string.add_to_collection_none else R.string.add_to_collection_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (choices.isNotEmpty()) {
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { choice -> ChoiceRow(choice, onToggle) }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_TYPED); onEdited() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.add_to_collection_new_label)) },
                singleLine = true,
                isError = message != null,
                supportingText = message?.let { { Text(it) } },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate(name) { name = "" } }),
            )
            Button(onClick = { onCreate(name) { name = "" } }, enabled = canCreate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_to_collection_create))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.add_to_collection_done)) }
        }
    }
}

@Composable
private fun ChoiceRow(choice: CollectionChoice, onToggle: (Long, Boolean) -> Unit) {
    val accent = collectionAccentColor(choice.collection.accent)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = choice.isMember, role = Role.Checkbox, onValueChange = { onToggle(choice.collection.id, it) }),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
        Column(Modifier.weight(1f)) {
            Text(choice.collection.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hadithCountText(choice.collection.hadithCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row as a whole is the control; the box only shows its state.
        Checkbox(checked = choice.isMember, onCheckedChange = null)
    }
}

/** The sheet wired to the collections of the database, for the hadith [hadithId]. */
@Composable
fun AddToCollectionHost(hadithId: Long, viewModel: CollectionsViewModel, onDismiss: () -> Unit) {
    val choices by remember(hadithId) { viewModel.choices(hadithId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.clearEditor() } }
    AddToCollectionSheet(
        choices = choices,
        editor = editor,
        onToggle = { collectionId, member -> viewModel.setMember(hadithId, collectionId, member) },
        // Each new collection gets the next tone, so a user's collections tell apart at a glance without anyone choosing.
        onCreate = { title, onSaved ->
            viewModel.create(title, accent = choices.size % CollectionAccent.COUNT, firstHadithId = hadithId, onSaved = { onSaved() })
        },
        onEdited = viewModel::clearEditor,
        onDismiss = onDismiss,
    )
}
