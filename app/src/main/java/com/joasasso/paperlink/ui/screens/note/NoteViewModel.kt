package com.joasasso.paperlink.ui.screens.note

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.LinkWithAttachments
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.ui.navigation.NoteDestination
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Nota de un código, editable: el texto se guarda solo al dejar de escribir y al salir.
 * Una nota vacía no se guarda (queda el texto anterior). El texto en edición vive en [SavedStateHandle].
 */
class NoteViewModel(
    private val handle: SavedStateHandle,
    val code: String,
    private val attachmentId: String,
    private val repository: LinkRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        /** El código o la nota ya no existen (por ejemplo, se borraron). */
        data object Missing : State
        data class Loaded(val label: String?) : State
    }

    data class UiState(
        val text: String = "",
        /** El texto quedó guardado y se puede salir de la pantalla. */
        val done: Boolean = false,
        @StringRes val message: Int? = null,
    )

    val state: StateFlow<State> = repository.observeLink(code)
        .map { link ->
            if (link == null || link.findNote() == null) State.Missing else State.Loaded(link.link.label)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    private val _ui = MutableStateFlow(UiState(text = handle[KEY_TEXT] ?: ""))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var saveJob: Job? = null

    init {
        if (handle.get<String>(KEY_TEXT) == null) {
            viewModelScope.launch {
                val saved = repository.observeLink(code).first()?.findNote()?.text.orEmpty()
                // Si el usuario ya empezó a escribir, no pisar lo que escribió.
                if (handle.get<String>(KEY_TEXT) == null) {
                    handle[KEY_TEXT] = saved
                    _ui.update { it.copy(text = saved) }
                }
            }
        }
    }

    private fun LinkWithAttachments.findNote(): Attachment? =
        attachments.firstOrNull { it.id == attachmentId && it.kind == Attachment.Kind.NOTE }

    fun onTextChange(text: String) {
        handle[KEY_TEXT] = text
        _ui.update { it.copy(text = text) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DELAY_MS)
            save(text)
        }
    }

    /** Salir: guarda ya lo escrito (si no está vacío) y después avisa con [UiState.done]. */
    fun onDone() {
        saveJob?.cancel()
        viewModelScope.launch {
            save(_ui.value.text)
            _ui.update { it.copy(done = true) }
        }
    }

    fun onMessageShown() = _ui.update { it.copy(message = null) }

    private suspend fun save(text: String) {
        if (text.isBlank()) return
        try {
            repository.updateNote(attachmentId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not save note", e)
            _ui.update { it.copy(message = e.messageRes()) }
        }
    }

    companion object {
        private const val KEY_TEXT = "note_text"
        private const val SAVE_DELAY_MS = 600L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val handle = createSavedStateHandle()
                val route = handle.toRoute<NoteDestination>()
                NoteViewModel(handle, route.code, route.attachmentId, app.container.linkRepository)
            }
        }
    }
}
