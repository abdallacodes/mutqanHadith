package com.mutqan.hadith.ui.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.graph.GraphLayout
import com.mutqan.hadith.domain.graph.GraphLayoutEngine
import com.mutqan.hadith.domain.graph.NarratorGraph
import com.mutqan.hadith.domain.graph.NarratorNode
import com.mutqan.hadith.domain.usecase.GetHadithsByNarratorUseCase
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.domain.usecase.NarratorHadith
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the profile sheet shows; [hadiths] is empty and [isLoading] true until the list arrives. */
data class NarratorProfile(
    val node: NarratorNode,
    val hadiths: List<NarratorHadith> = emptyList(),
    val isLoading: Boolean = true,
)

data class NarratorGraphUi(
    val graph: NarratorGraph,
    val layout: GraphLayout,
    val studiedOnly: Boolean = false,
    /** The narrator whose edges are highlighted. Survives closing the profile sheet. */
    val selectedId: Long? = null,
    /** Non-null while the profile sheet is open. */
    val profile: NarratorProfile? = null,
)

sealed interface NarratorGraphEvent {
    data class SelectNode(val id: Long) : NarratorGraphEvent

    /** Tap on empty canvas: drops the selection and the sheet. */
    data object ClearSelection : NarratorGraphEvent

    /** The sheet was closed; the highlight stays. */
    data object DismissSheet : NarratorGraphEvent

    data class SetStudiedOnly(val studiedOnly: Boolean) : NarratorGraphEvent
}

class NarratorGraphViewModel(
    private val getGraph: GetNarratorGraphUseCase,
    private val getHadithsByNarrator: GetHadithsByNarratorUseCase,
    private val layoutEngine: GraphLayoutEngine = GraphLayoutEngine(),
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<NarratorGraphUi>>(UiState.Loading)
    val state: StateFlow<UiState<NarratorGraphUi>> = _state.asStateFlow()

    init {
        load(studiedOnly = false)
    }

    fun load(studiedOnly: Boolean = current()?.studiedOnly ?: false) {
        if (_state.value !is UiState.Success) _state.value = UiState.Loading
        viewModelScope.launch {
            try {
                val graph = getGraph(studiedOnly)
                val previous = current()
                val selected = previous?.selectedId?.takeIf { graph.node(it) != null }
                _state.value = UiState.Success(
                    NarratorGraphUi(
                        graph = graph,
                        layout = layoutEngine.layout(graph),
                        studiedOnly = studiedOnly,
                        selectedId = selected,
                        // The open profile keeps its Hadith list but shows the narrator's fresh counts.
                        profile = previous?.profile
                            ?.takeIf { selected != null && it.node.id == selected }
                            ?.let { it.copy(node = graph.node(it.node.id) ?: it.node) },
                    ),
                )
            } catch (e: Exception) {
                if (_state.value !is UiState.Success) _state.value = UiState.Error(e.message.orEmpty())
            }
        }
    }

    fun onEvent(event: NarratorGraphEvent) {
        when (event) {
            is NarratorGraphEvent.SelectNode -> select(event.id)
            NarratorGraphEvent.ClearSelection -> update { it.copy(selectedId = null, profile = null) }
            NarratorGraphEvent.DismissSheet -> update { it.copy(profile = null) }
            is NarratorGraphEvent.SetStudiedOnly -> if (event.studiedOnly != current()?.studiedOnly) load(event.studiedOnly)
        }
    }

    private fun select(id: Long) {
        val node = current()?.graph?.node(id) ?: return
        update { it.copy(selectedId = id, profile = NarratorProfile(node)) }
        viewModelScope.launch {
            val hadiths = try {
                getHadithsByNarrator(id)
            } catch (e: Exception) {
                emptyList()
            }
            // The user may have tapped another narrator while this one was loading.
            update { ui ->
                if (ui.profile?.node?.id == id) ui.copy(profile = NarratorProfile(node, hadiths, isLoading = false)) else ui
            }
        }
    }

    private fun current(): NarratorGraphUi? = (_state.value as? UiState.Success)?.data

    private inline fun update(transform: (NarratorGraphUi) -> NarratorGraphUi) {
        _state.value = (_state.value as? UiState.Success)?.let { UiState.Success(transform(it.data)) } ?: _state.value
    }
}
