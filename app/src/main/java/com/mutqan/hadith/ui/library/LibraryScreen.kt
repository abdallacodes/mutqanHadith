package com.mutqan.hadith.ui.library

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.CollectionAccent
import com.mutqan.hadith.domain.model.HadithLevel
import com.mutqan.hadith.domain.narrator.NarratorAliases
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.LibraryHadith
import com.mutqan.hadith.domain.usecase.LibraryLevel
import com.mutqan.hadith.ui.collections.AddToCollectionHost
import com.mutqan.hadith.ui.collections.CollectButton
import com.mutqan.hadith.ui.collections.CollectionEditorSheet
import com.mutqan.hadith.ui.collections.CollectionsSection
import com.mutqan.hadith.ui.collections.CollectionsViewModel
import com.mutqan.hadith.ui.collections.LibraryCollections
import com.mutqan.hadith.ui.collections.LibrarySection
import com.mutqan.hadith.ui.collections.LibrarySectionChips
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.MasteryRing
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.theme.MatnCompactTextStyle
import com.mutqan.hadith.ui.theme.MutqanTheme
import kotlin.math.roundToInt

@StringRes
fun HadithLevel.titleRes(): Int = when (this) {
    HadithLevel.FOUNDATION -> R.string.level_1_title
    HadithLevel.AHKAM -> R.string.level_2_title
    HadithLevel.MUSNAD -> R.string.level_3_title
}

@StringRes
fun HadithLevel.descriptionRes(): Int = when (this) {
    HadithLevel.FOUNDATION -> R.string.level_1_desc
    HadithLevel.AHKAM -> R.string.level_2_desc
    HadithLevel.MUSNAD -> R.string.level_3_desc
}

@StringRes
fun HadithProgress.labelRes(): Int = when (this) {
    HadithProgress.NEW -> R.string.progress_new
    HadithProgress.LEARNING -> R.string.progress_learning
    HadithProgress.MASTERED -> R.string.progress_mastered
}

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onClose: (() -> Unit)?,
    onOpenHadith: (Long) -> Unit,
    /** Personal collections; a library given none shows only the levels. */
    collectionsViewModel: CollectionsViewModel? = null,
    onOpenCollection: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()
    var filing by rememberSaveable { mutableStateOf<Long?>(null) }
    var naming by rememberSaveable { mutableStateOf(false) }

    var collections: LibraryCollections? = null
    if (collectionsViewModel != null) {
        val list by collectionsViewModel.list.collectAsStateWithLifecycle()
        val collected by collectionsViewModel.collected.collectAsStateWithLifecycle()
        val editor by collectionsViewModel.editor.collectAsStateWithLifecycle()
        collections = LibraryCollections(
            state = list,
            collected = collected,
            onCollect = { filing = it },
            onNew = { naming = true },
            onOpen = onOpenCollection,
        )
        filing?.let { hadithId -> AddToCollectionHost(hadithId, collectionsViewModel, onDismiss = { filing = null }) }
        if (naming) {
            CollectionEditorSheet(
                editing = false,
                initialTitle = "",
                // The next tone after the ones in use, as in the sheet, so collections tell apart without being asked.
                initialAccent = ((list as? UiState.Success)?.data?.size ?: 0) % CollectionAccent.COUNT,
                editor = editor,
                onSave = { title, accent -> collectionsViewModel.create(title, accent) { naming = false } },
                onEdited = collectionsViewModel::clearEditor,
                onDismiss = { naming = false; collectionsViewModel.clearEditor() },
            )
        }
    }
    LibraryScreen(
        state = state,
        selected = selected,
        query = query,
        onSelectLevel = viewModel::onSelectLevel,
        onQueryChange = viewModel::onQueryChange,
        onRetry = viewModel::retry,
        onClose = onClose,
        onOpenHadith = onOpenHadith,
        collections = collections,
        section = section,
        onSelectSection = viewModel::onSelectSection,
    )
}

/** The library: a level at a time, with a search that stays inside the level being read. */
@Composable
fun LibraryScreen(
    state: UiState<LibraryUi>,
    selected: HadithLevel,
    query: String,
    onSelectLevel: (HadithLevel) -> Unit,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    /** Null when the library is a tab of its own, which has no page to go back to. */
    onClose: (() -> Unit)?,
    onOpenHadith: (Long) -> Unit,
    /** The user's collections, shown beside the levels; null leaves the library as the levels alone. */
    collections: LibraryCollections? = null,
    section: LibrarySection = LibrarySection.HADITHS,
    onSelectSection: (LibrarySection) -> Unit = {},
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(title = stringResource(R.string.library_title), onBack = onClose) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.contentWidth().fillMaxSize()) {
                if (collections != null) LibrarySectionChips(section, collections.count, onSelectSection)
                if (collections != null && section == LibrarySection.COLLECTIONS) {
                    CollectionsSection(collections, Modifier.weight(1f).fillMaxWidth())
                } else {
                    SearchField(query, onQueryChange)
                    UiStateHost(state, onRetry, Modifier.weight(1f).fillMaxWidth()) { ui ->
                        LibraryBody(ui, selected, onSelectLevel, onOpenHadith, collections)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = { Text(stringResource(R.string.library_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.library_search_clear))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

@Composable
private fun LibraryBody(
    ui: LibraryUi,
    selected: HadithLevel,
    onSelectLevel: (HadithLevel) -> Unit,
    onOpenHadith: (Long) -> Unit,
    collections: LibraryCollections? = null,
) {
    val level = ui.level(selected)
    Column(Modifier.fillMaxSize()) {
        LevelTabs(ui, selected, onSelectLevel)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header") { LevelHeader(selected, level) }
            if (level == null || level.hadiths.isEmpty()) {
                item(key = "empty") { EmptyLevel(searched = ui.searched) }
            } else {
                items(level.hadiths, key = { it.hadith.id }) { item ->
                    LibraryHadithCard(
                        item,
                        onClick = { onOpenHadith(item.hadith.id) },
                        trailing = collections?.let { c ->
                            { CollectButton(filed = item.hadith.id in c.collected, onClick = { c.onCollect(item.hadith.id) }) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelTabs(ui: LibraryUi, selected: HadithLevel, onSelectLevel: (HadithLevel) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = selected.ordinal,
        edgePadding = 16.dp,
        containerColor = Color.Transparent,
    ) {
        HadithLevel.entries.forEach { level ->
            Tab(
                selected = level == selected,
                onClick = { onSelectLevel(level) },
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(level.titleRes()), style = MaterialTheme.typography.titleSmall)
                        // The number of hadiths the level shows: the whole level, or the ones a search matched.
                        InfoChip(
                            (ui.level(level)?.hadiths?.size ?: 0).toString(),
                            container = MaterialTheme.colorScheme.surfaceVariant,
                            content = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LevelHeader(selected: HadithLevel, level: LibraryLevel?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(selected.descriptionRes()),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val status = level?.status
        if (status != null && status.total > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(HadithProgress.NEW, status.unseen)
                StatusChip(HadithProgress.LEARNING, status.learning)
                StatusChip(HadithProgress.MASTERED, status.mastered)
            }
        }
    }
}

@Composable
private fun StatusChip(progress: HadithProgress, count: Int) {
    InfoChip(
        stringResource(R.string.library_status_count, stringResource(progress.labelRes()), count),
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A level with nothing to list: not written yet, or nothing in it matched the search. */
@Composable
private fun EmptyLevel(searched: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searched.isBlank()) {
            Text(stringResource(R.string.library_level_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.library_level_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                stringResource(R.string.library_no_results, searched.trim()),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.library_no_results_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun LibraryHadithCard(
    item: LibraryHadith,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** A control at the end of the header, e.g. the bookmark that files the hadith into a collection. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val hadith = item.hadith
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(44.dp).clip(CircleShape),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(hadith.hadithNumber.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(hadith.sourceBook, style = MaterialTheme.typography.titleMedium)
                    if (hadith.sahabi.isNotBlank()) {
                        Text(
                            stringResource(R.string.sahabi_label, NarratorAliases.canonicalName(hadith.sahabi)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                InfoChip(
                    stringResource(R.string.level_badge, hadith.level),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trailing?.invoke()
            }
            Text(
                hadith.matnWithTashkeel,
                style = MatnCompactTextStyle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (hadith.takhrij.isNotBlank()) InfoChip(hadith.takhrij)
                if (item.isDue) {
                    InfoChip(
                        stringResource(R.string.badge_due),
                        container = MaterialTheme.colorScheme.primaryContainer,
                        content = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Box(Modifier.weight(1f))
                ProgressBadge(item.progress, item.metrics?.overallMastery)
            }
        }
    }
}

/** Where the user stands with a hadith: New, Learning or Mastered, with the mastery ring once it is being learned. */
@Composable
fun ProgressBadge(progress: HadithProgress, mastery: Float?, modifier: Modifier = Modifier) {
    val colors = MutqanTheme.colors
    val color = when (progress) {
        HadithProgress.NEW -> MaterialTheme.colorScheme.outline
        HadithProgress.LEARNING -> colors.masteryMid
        HadithProgress.MASTERED -> colors.masteryHigh
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress != HadithProgress.NEW) MasteryRing(progress = mastery ?: 0f, color = color, size = 16.dp, strokeWidth = 2.5.dp)
        Text(stringResource(progress.labelRes()), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        if (progress != HadithProgress.NEW && mastery != null) {
            Text(
                stringResource(R.string.mastery_percent, (mastery * 100).roundToInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
