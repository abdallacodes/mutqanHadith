package com.mutqan.hadith.ui.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.CollectionSummary
import com.mutqan.hadith.ui.common.BookmarkIcon
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost

/** What the library shows besides the levels: the user's collections, and the way to file a hadith into one. */
enum class LibrarySection { HADITHS, COLLECTIONS }

/**
 * The library's side of personal collections. A library given none behaves as it always did, with only the levels.
 * [collected] are the hadiths already in some collection, which the lists mark.
 */
data class LibraryCollections(
    val state: UiState<List<CollectionSummary>>,
    val collected: Set<Long>,
    val onCollect: (hadithId: Long) -> Unit,
    val onNew: () -> Unit,
    val onOpen: (collectionId: Long) -> Unit,
    val onRetry: () -> Unit = {},
) {
    /** How many collections there are, once they are loaded. */
    val count: Int? get() = (state as? UiState.Success)?.data?.size
}

/** The switch between the hadiths of the levels and the user's collections. */
@Composable
fun LibrarySectionChips(selected: LibrarySection, collectionCount: Int?, onSelect: (LibrarySection) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == LibrarySection.HADITHS,
            onClick = { onSelect(LibrarySection.HADITHS) },
            label = { Text(stringResource(R.string.library_section_hadiths)) },
        )
        FilterChip(
            selected = selected == LibrarySection.COLLECTIONS,
            onClick = { onSelect(LibrarySection.COLLECTIONS) },
            label = {
                val name = stringResource(R.string.library_section_collections)
                Text(if (collectionCount != null && collectionCount > 0) "$name · $collectionCount" else name)
            },
        )
    }
}

/** The bookmark on a hadith card: outlined until the hadith is in some collection, filled after. */
@Composable
fun CollectButton(filed: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (filed) R.string.collect_action_filed else R.string.collect_action)
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
        BookmarkIcon(
            filled = filed,
            tint = if (filed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The collections: one card each, and, while there are none, an invitation to make the first. */
@Composable
fun CollectionsSection(collections: LibraryCollections, modifier: Modifier = Modifier) {
    UiStateHost(collections.state, collections.onRetry, modifier.fillMaxSize()) { list ->
        if (list.isEmpty()) {
            EmptyCollections(collections.onNew)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "intro") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.collections_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = collections.onNew, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.collections_new), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                items(list, key = { it.id }) { collection ->
                    CollectionCard(collection, onClick = { collections.onOpen(collection.id) })
                }
            }
        }
    }
}

/** No collections yet: a warm start, not an empty page. */
@Composable
private fun EmptyCollections(onNew: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        BookmarkIcon(filled = false, tint = MaterialTheme.colorScheme.primary, size = 56.dp)
        Text(stringResource(R.string.collections_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.collections_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onNew) { Text(stringResource(R.string.collections_new)) }
    }
}
