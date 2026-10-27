package com.mutqan.hadith.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.ui.common.NarratorNameText
import com.mutqan.hadith.ui.common.companionOpener
import com.mutqan.hadith.data.entity.CatalogGroupEntity
import com.mutqan.hadith.domain.model.CollectionAccent
import com.mutqan.hadith.domain.narrator.NarratorAliases
import com.mutqan.hadith.domain.usecase.HadithProgress
import com.mutqan.hadith.domain.usecase.LibraryGroup
import com.mutqan.hadith.domain.usecase.LibraryHadith
import com.mutqan.hadith.domain.usecase.LibraryPage
import com.mutqan.hadith.domain.usecase.LibraryStatus
import com.mutqan.hadith.ui.common.labelRes
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
fun HadithProgress.labelRes(): Int = when (this) {
    HadithProgress.NEW -> R.string.progress_new
    HadithProgress.LEARNING -> R.string.progress_learning
    HadithProgress.MASTERED -> R.string.progress_mastered
}

/** The title of a book or section in the language of the app; the Arabic one when the catalog has none in English. */
@Composable
fun CatalogGroupEntity.displayTitle(): String {
    val english = LocalConfiguration.current.locales[0].language == "en"
    return if (english && titleEn.isNotBlank()) titleEn else title
}

@Composable
fun LibraryRoute(
    viewModel: LibraryViewModel,
    onClose: (() -> Unit)?,
    onOpenHadith: (Long) -> Unit,
    /** Personal collections; a library given none shows only the books. */
    collectionsViewModel: CollectionsViewModel? = null,
    onOpenCollection: (Long) -> Unit = {},
    /** The library and the progress are being brought up to date, so the pull-down shows its spinner. */
    refreshing: Boolean = false,
    /** The user pulled the list down: look for a newer library and sync the progress now. */
    onRefresh: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
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
        query = query,
        onQueryChange = viewModel::onQueryChange,
        onOpenGroup = viewModel::onOpenGroup,
        onUp = { viewModel.onUp() },
        onRetry = viewModel::retry,
        onClose = onClose,
        onOpenHadith = onOpenHadith,
        collections = collections,
        section = section,
        onSelectSection = viewModel::onSelectSection,
        refreshing = refreshing,
        onRefresh = onRefresh,
    )
}

/**
 * The library: the books on a shelf, and a book or a section opened into its own sections and hadiths. A search stays
 * inside the group that is open. Back goes out of a search, then up one group, then leaves the library when it can.
 */
@Composable
fun LibraryScreen(
    state: UiState<LibraryPage>,
    query: String,
    onQueryChange: (String) -> Unit,
    onOpenGroup: (Long) -> Unit,
    /** One step back inside the library: out of a search, else up to the group above. */
    onUp: () -> Unit,
    onRetry: () -> Unit,
    /** Null when the library is a tab of its own, which has no page to go back to. */
    onClose: (() -> Unit)?,
    onOpenHadith: (Long) -> Unit,
    /** The user's collections, shown beside the books; null leaves the library as the books alone. */
    collections: LibraryCollections? = null,
    section: LibrarySection = LibrarySection.HADITHS,
    onSelectSection: (LibrarySection) -> Unit = {},
    refreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    val open = (state as? UiState.Success)?.data?.open
    val inBooks = collections == null || section == LibrarySection.HADITHS
    // Inside a group, back is the library's own; at the shelf it is the caller's.
    BackHandler(enabled = inBooks && (open != null || query.isNotEmpty()), onBack = onUp)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MutqanTopBar(
                title = if (inBooks && open != null) open.displayTitle() else stringResource(R.string.library_title),
                onBack = if (inBooks && open != null) onUp else onClose,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(Modifier.contentWidth().fillMaxSize()) {
                if (collections != null) LibrarySectionChips(section, collections.count, onSelectSection)
                if (collections != null && !inBooks) {
                    CollectionsSection(collections, Modifier.weight(1f).fillMaxWidth())
                } else {
                    // The shelf has no search: a search is always inside a book or a section.
                    if (open != null) SearchField(query, onQueryChange)
                    UiStateHost(state, onRetry, Modifier.weight(1f).fillMaxWidth()) { page ->
                        LibraryBody(page, onOpenGroup, onOpenHadith, collections)
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
    page: LibraryPage,
    onOpenGroup: (Long) -> Unit,
    onOpenHadith: (Long) -> Unit,
    collections: LibraryCollections? = null,
) {
    val atShelf = page.open == null
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (page.hasHeader()) item(key = "header") { GroupHeader(page) }
        if (page.groups.isNotEmpty()) {
            item(key = "groups-label") {
                SectionLabel(stringResource(if (atShelf) R.string.library_books_label else R.string.library_sections_label))
            }
            items(page.groups, key = { "group-${it.group.id}" }) { group ->
                GroupCard(group, onClick = { onOpenGroup(group.group.id) })
            }
        }
        when {
            atShelf -> item(key = "more") { MoreBooksCard() }
            page.hadiths.isEmpty() && page.groups.isEmpty() -> item(key = "empty") { EmptyGroup(page) }
            else -> {
                if (page.hadiths.isNotEmpty() && page.groups.isNotEmpty()) {
                    item(key = "hadiths-label") { SectionLabel(stringResource(R.string.library_hadiths_label)) }
                }
                items(page.hadiths, key = { "hadith-${it.hadith.id}" }) { item ->
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
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * What the open group is, above its contents: where it sits when it is a section of a section, and how far the user has
 * come through all of it, or how many hadiths the search matched.
 */
@Composable
private fun GroupHeader(page: LibraryPage) {
    val above = page.trail.dropLast(1).map { it.displayTitle() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (above.isNotEmpty()) {
            Text(
                above.joinToString(" / "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (page.showsMatchCount()) {
            Text(
                pluralStringResource(R.plurals.hadith_count, page.hadiths.size, page.hadiths.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (page.showsProgress()) StatusChips(page.status, page.dueCount)
    }
}

private fun LibraryPage.showsMatchCount() = isSearch && hadiths.isNotEmpty()

private fun LibraryPage.showsProgress() = !isSearch && status.total > 0

/** A group has something to say above its contents when it sits inside another, or has a count or a progress to show. */
private fun LibraryPage.hasHeader() = open != null && (trail.size > 1 || showsMatchCount() || showsProgress())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(status: LibraryStatus, dueCount: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatusChip(stringResource(HadithProgress.NEW.labelRes()), status.unseen)
        StatusChip(stringResource(HadithProgress.LEARNING.labelRes()), status.learning)
        StatusChip(stringResource(HadithProgress.MASTERED.labelRes()), status.mastered)
        if (dueCount > 0) {
            InfoChip(
                stringResource(R.string.library_status_count, stringResource(R.string.badge_due), dueCount),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, count: Int) {
    InfoChip(
        stringResource(R.string.library_status_count, label, count),
        container = MaterialTheme.colorScheme.surfaceVariant,
        content = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A book or a section on the list: its name, how much is in it and how far the user has come through all of it. */
@Composable
private fun GroupCard(group: LibraryGroup, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(group.group.displayTitle(), style = MaterialTheme.typography.titleMedium)
                    Text(
                        groupSize(group),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            if (group.status.total > 0) StatusChips(group.status, group.dueCount)
        }
    }
}

/** «42 hadiths», and «4 sections» too when it has any: the sections' own hadiths are among the count. */
@Composable
private fun groupSize(group: LibraryGroup): String {
    val hadiths = pluralStringResource(R.plurals.hadith_count, group.status.total, group.status.total)
    if (group.sectionCount == 0) return hadiths
    val sections = pluralStringResource(R.plurals.library_section_count, group.sectionCount, group.sectionCount)
    return stringResource(R.string.library_group_size, hadiths, sections)
}

/** The last card of the shelf: the library will grow, and says so. It leads nowhere, so it is not styled as a book. */
@Composable
private fun MoreBooksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.library_more_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.library_more_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A group with nothing to list: nothing is in it yet, or nothing in it matched the search. */
@Composable
private fun EmptyGroup(page: LibraryPage) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!page.isSearch) {
            Text(stringResource(R.string.library_group_empty), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        } else {
            Text(
                stringResource(R.string.library_no_results, page.searched.trim(), page.open?.displayTitle().orEmpty()),
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
                        NarratorNameText(
                            stringResource(R.string.sahabi_label, NarratorAliases.canonicalName(hadith.sahabi)),
                            MaterialTheme.typography.bodyMedium,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            onOpen = companionOpener(hadith.id),
                        )
                    }
                }
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
                item.band?.let { InfoChip(stringResource(R.string.band_chip, stringResource(it.labelRes))) }
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
