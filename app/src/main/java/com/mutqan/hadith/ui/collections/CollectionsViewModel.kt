package com.mutqan.hadith.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.model.CollectionSaved
import com.mutqan.hadith.domain.model.CollectionSummary
import com.mutqan.hadith.domain.model.TitleProblem
import com.mutqan.hadith.domain.repository.CollectionRepository
import com.mutqan.hadith.domain.usecase.CreateCollectionUseCase
import com.mutqan.hadith.domain.usecase.SetCollectionMembershipUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What a name dialog or the "add to collection" sheet shows about the save in progress: busy, refused, or failed. */
data class EditorState(
    val saving: Boolean = false,
    val problem: TitleProblem? = null,
    val failed: Boolean = false,
)

/** A collection offered to a hadith: [isMember] says the hadith is in it already. */
data class CollectionChoice(val collection: CollectionSummary, val isMember: Boolean)

/**
 * The user's collections as the library lists them and as the "add to collection" sheet offers them. One of these lives
 * in every screen that can file a hadith, and all of them read the same database, so they agree.
 */
class CollectionsViewModel(
    private val collections: CollectionRepository,
    private val createCollection: CreateCollectionUseCase,
    private val setMembership: SetCollectionMembershipUseCase,
) : ViewModel() {

    val list: StateFlow<UiState<List<CollectionSummary>>> = collections.observeAll()
        .map<List<CollectionSummary>, UiState<List<CollectionSummary>>> { UiState.Success(it) }
        .catch { emit(UiState.Error(it.message.orEmpty())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** Every hadith that is in some collection, so a list can mark the ones already filed. */
    val collected: StateFlow<Set<Long>> = collections.observeCollectedHadithIds()
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _editor = MutableStateFlow(EditorState())
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    /** Every collection, newest first, each marked with whether [hadithId] is in it. */
    fun choices(hadithId: Long): Flow<List<CollectionChoice>> =
        combine(collections.observeAll(), collections.observeCollectionIdsOf(hadithId)) { all, member ->
            all.map { CollectionChoice(it, it.id in member) }
        }

    /** Forgets the last refusal or failure, e.g. when the dialog is closed or the name is being retyped. */
    fun clearEditor() {
        _editor.value = EditorState()
    }

    /**
     * Makes a collection, with [firstHadithId] in it if given, and calls [onSaved] with its id. A name that is not
     * accepted is reported in [editor] and nothing is made.
     */
    fun create(title: String, accent: Int, firstHadithId: Long? = null, onSaved: (Long) -> Unit = {}) {
        if (_editor.value.saving) return
        _editor.value = EditorState(saving = true)
        viewModelScope.launch {
            try {
                when (val result = createCollection(title, accent, firstHadithId)) {
                    is CollectionSaved.Saved -> {
                        _editor.value = EditorState()
                        onSaved(result.id)
                    }
                    is CollectionSaved.Rejected -> _editor.value = EditorState(problem = result.problem)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editor.value = EditorState(failed = true)
            }
        }
    }

    /** Puts [hadithId] in [collectionId] or takes it out; the sheet follows from the database. */
    fun setMember(hadithId: Long, collectionId: Long, member: Boolean) {
        viewModelScope.launch {
            try {
                setMembership(collectionId, hadithId, member)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editor.value = EditorState(failed = true)
            }
        }
    }
}
