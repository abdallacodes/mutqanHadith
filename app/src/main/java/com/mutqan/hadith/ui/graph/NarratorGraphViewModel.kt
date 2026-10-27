package com.mutqan.hadith.ui.graph

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mutqan.hadith.domain.graph.GraphLayout
import com.mutqan.hadith.domain.graph.GraphLayoutEngine
import com.mutqan.hadith.domain.graph.NarratorGraph
import com.mutqan.hadith.domain.graph.NarratorNode
import com.mutqan.hadith.domain.usecase.GetNarratorGraphUseCase
import com.mutqan.hadith.ui.common.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NarratorGraphUi(
    val graph: NarratorGraph,
    val layout: GraphLayout,
    val studiedOnly: Boolean = false,
    /** The narrator whose edges are highlighted; it stays while his page is open and after coming back. */
    val selectedId: Long? = null,
)

sealed interface NarratorGraphEvent {
    data class SelectNode(val id: Long) : NarratorGraphEvent

    /** Tap on empty canvas: drops the selection. */
    data object ClearSelection : NarratorGraphEvent

    data class SetStudiedOnly(val studiedOnly: Boolean) : NarratorGraphEvent
}

class NarratorGraphViewModel(
    private val getGraph: GetNarratorGraphUseCase,
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
            NarratorGraphEvent.ClearSelection -> update { it.copy(selectedId = null) }
            is NarratorGraphEvent.SetStudiedOnly -> if (event.studiedOnly != current()?.studiedOnly) load(event.studiedOnly)
        }
    }

    private fun select(id: Long) {
        if (current()?.graph?.node(id) == null) return
        update { it.copy(selectedId = id) }
    }

    private fun current(): NarratorGraphUi? = (_state.value as? UiState.Success)?.data

    private inline fun update(transform: (NarratorGraphUi) -> NarratorGraphUi) {
        _state.value = (_state.value as? UiState.Success)?.let { UiState.Success(transform(it.data)) } ?: _state.value
    }
}
