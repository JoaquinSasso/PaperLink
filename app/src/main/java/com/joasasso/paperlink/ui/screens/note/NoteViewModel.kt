package com.joasasso.paperlink.ui.screens.note

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.navigation.NoteDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Lectura de una nota de un código. */
class NoteViewModel(
    val code: String,
    attachmentId: String,
    repository: LinkRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        /** El código o la nota ya no existen (por ejemplo, se borraron). */
        data object Missing : State
        data class Loaded(val label: String?, val text: String) : State
    }

    val state: StateFlow<State> = repository.observeLink(code)
        .map { link ->
            val note = link?.attachments?.firstOrNull { it.id == attachmentId }
            if (link == null || note == null) State.Missing else State.Loaded(link.link.label, note.text.orEmpty())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val route = createSavedStateHandle().toRoute<NoteDestination>()
                NoteViewModel(route.code, route.attachmentId, app.container.linkRepository)
            }
        }
    }
}
