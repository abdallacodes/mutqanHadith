package com.mutqan.hadith.ui.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.model.CollectionSaved
import com.mutqan.hadith.domain.usecase.CollectionDetail
import com.mutqan.hadith.domain.usecase.DeleteCollectionUseCase
import com.mutqan.hadith.domain.usecase.ObserveCollectionDetailUseCase
import com.mutqan.hadith.domain.usecase.RenameCollectionUseCase
import com.mutqan.hadith.domain.usecase.SetCollectionMembershipUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One collection: its hadiths with the user's progress on them, and what can be done with it. */
class CollectionDetailViewModel(
    private val collectionId: Long,
    observeDetail: ObserveCollectionDetailUseCase,
    private val renameCollection: RenameCollectionUseCase,
    private val deleteCollection: DeleteCollectionUseCase,
    private val setMembership: SetCollectionMembershipUseCase,
) : ViewModel() {

    private val deleting = MutableStateFlow(false)
    private val retryTick = MutableStateFlow(0)

    private val _editor = MutableStateFlow(EditorState())

    /** The rename dialog's save in progress. */
    val editor: StateFlow<EditorState> = _editor.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<UiState<CollectionDetail>> = retryTick
        .flatMapLatest {
            combine(observeDetail(collectionId), deleting) { detail, gone ->
                when {
                    // Between the deletion and the screen closing, the collection is gone; that is not an error.
                    gone -> UiState.Loading
                    detail == null -> UiState.Error("Collection $collectionId not found")
                    else -> UiState.Success(detail)
                }
            }.catch { emit(UiState.Error(it.message.orEmpty())) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    fun retry() = retryTick.update { it + 1 }

    fun clearEditor() {
        _editor.value = EditorState()
    }

    /** Renames the collection and calls [onDone]; a name that is not accepted is reported in [editor]. */
    fun rename(title: String, accent: Int, onDone: () -> Unit = {}) {
        if (_editor.value.saving) return
        _editor.value = EditorState(saving = true)
        viewModelScope.launch {
            try {
                when (val result = renameCollection(collectionId, title, accent)) {
                    is CollectionSaved.Saved -> {
                        _editor.value = EditorState()
                        onDone()
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

    /** Deletes the collection (and only it) and calls [onDeleted] so the screen can close. */
    fun delete(onDeleted: () -> Unit) {
        if (deleting.value) return
        deleting.value = true
        viewModelScope.launch {
            try {
                deleteCollection(collectionId)
                onDeleted()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                deleting.value = false
                _editor.value = EditorState(failed = true)
            }
        }
    }

    /** Takes a hadith out of this collection; the hadith itself and its progress are untouched. */
    fun removeHadith(hadithId: Long) {
        viewModelScope.launch {
            try {
                setMembership(collectionId, hadithId, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editor.value = EditorState(failed = true)
            }
        }
    }
}
