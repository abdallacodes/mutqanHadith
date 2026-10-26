package com.mutqan.hadith.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.usecase.CollectionDetail
import com.mutqan.hadith.ui.common.BookmarkIcon
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.library.LibraryHadithCard

@Composable
fun CollectionDetailRoute(
    viewModel: CollectionDetailViewModel,
    onClose: () -> Unit,
    onOpenHadith: (Long) -> Unit,
    /** Starts the practice of the whole collection. */
    onReview: () -> Unit,
    onBrowseLibrary: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    CollectionDetailScreen(
        state = state,
        editor = editor,
        onRetry = viewModel::retry,
        onClose = onClose,
        onOpenHadith = onOpenHadith,
        onReview = onReview,
        onBrowseLibrary = onBrowseLibrary,
        onRename = { title, accent, done -> viewModel.rename(title, accent, done) },
        onEdited = viewModel::clearEditor,
        onDelete = { viewModel.delete(onClose) },
        onRemoveHadith = viewModel::removeHadith,
    )
}

/** One collection: its hadiths, the way to practise them together, and the way to rename or delete it. */
@Composable
fun CollectionDetailScreen(
    state: UiState<CollectionDetail>,
    editor: EditorState,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onOpenHadith: (Long) -> Unit,
    onReview: () -> Unit,
    onBrowseLibrary: () -> Unit,
    /** Saves a new name and color; the last function closes the dialog once it is saved. */
    onRename: (title: String, accent: Int, onDone: () -> Unit) -> Unit,
    onEdited: () -> Unit,
    onDelete: () -> Unit,
    onRemoveHadith: (Long) -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val loaded = (state as? UiState.Success)?.data
    if (loaded != null && renaming) {
        CollectionEditorSheet(
            editing = true,
            initialTitle = loaded.collection.title,
            initialAccent = loaded.collection.accent,
            editor = editor,
            onSave = { title, accent -> onRename(title, accent) { renaming = false } },
            onEdited = onEdited,
            onDismiss = { renaming = false; onEdited() },
        )
    }
    if (loaded != null && deleting) {
        DeleteCollectionDialog(loaded.collection.title, onConfirm = { deleting = false; onDelete() }, onDismiss = { deleting = false })
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MutqanTopBar(
                title = loaded?.collection?.title.orEmpty(),
                onBack = onClose,
                actions = {
                    if (loaded != null) {
                        val edit = stringResource(R.string.collection_edit)
                        val delete = stringResource(R.string.collection_delete)
                        IconButton(onClick = { renaming = true }, modifier = Modifier.semantics { contentDescription = edit }) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                        IconButton(onClick = { deleting = true }, modifier = Modifier.semantics { contentDescription = delete }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { detail ->
                CollectionDetailContent(detail, onOpenHadith, onReview, onBrowseLibrary, onRemoveHadith, Modifier.contentWidth())
            }
        }
    }
}

@Composable
private fun CollectionDetailContent(
    detail: CollectionDetail,
    onOpenHadith: (Long) -> Unit,
    onReview: () -> Unit,
    onBrowseLibrary: () -> Unit,
    onRemoveHadith: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (detail.isEmpty) {
        EmptyCollection(detail, onBrowseLibrary, modifier)
        return
    }
    val remove = stringResource(R.string.collection_remove_hadith)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") { CollectionHeader(detail, onReview) }
        items(detail.hadiths, key = { it.hadith.id }) { item ->
            LibraryHadithCard(
                item,
                onClick = { onOpenHadith(item.hadith.id) },
                trailing = {
                    IconButton(onClick = { onRemoveHadith(item.hadith.id) }, modifier = Modifier.semantics { contentDescription = remove }) {
                        Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
}

/** The collection's size and the one thing to do with it as a whole. */
@Composable
private fun CollectionHeader(detail: CollectionDetail, onReview: () -> Unit) {
    val accent = collectionAccentColor(detail.collection.accent)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
            Text(
                hadithCountText(detail.hadiths.size),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onReview, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.collection_review), modifier = Modifier.padding(start = 8.dp))
        }
        Text(
            stringResource(R.string.collection_review_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A collection with nothing in it yet: where hadiths come from, and a way to go and find some. */
@Composable
private fun EmptyCollection(detail: CollectionDetail, onBrowseLibrary: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        BookmarkIcon(filled = false, tint = collectionAccentColor(detail.collection.accent), size = 56.dp)
        Text(stringResource(R.string.collection_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.collection_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onBrowseLibrary) { Text(stringResource(R.string.collection_browse_library)) }
    }
}
