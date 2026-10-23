package com.joasasso.paperlink.ui.screens.detail

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
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.LinkContent
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.common.ContentImporter
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.ui.navigation.DetailDestination
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
import java.io.File

/**
 * Detalle de un código: elementos, etiqueta (se guarda sola al dejar de escribir), agregar más,
 * borrar elementos sueltos o el código entero, con deshacer.
 */
class DetailViewModel(
    private val handle: SavedStateHandle,
    val code: String,
    private val repository: LinkRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Missing : State
        data class Loaded(val content: LinkContent) : State
    }

    data class UiState(
        val label: String = "",
        /** Elemento recién borrado, para ofrecer "Deshacer". */
        val deletedAttachmentId: String? = null,
        /** El código se borró: volver a la Home (que ofrece deshacer). */
        val codeDeleted: Boolean = false,
        @StringRes val message: Int? = null,
    )

    val state: StateFlow<State> = repository.observeLinkContent(code)
        .map { if (it == null) State.Missing else State.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    private val _ui = MutableStateFlow(UiState(label = handle[KEY_LABEL] ?: ""))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val importer = ContentImporter(viewModelScope, repository)

    private var labelJob: Job? = null

    init {
        if (handle.get<String>(KEY_LABEL) == null) {
            viewModelScope.launch {
                val saved = repository.observeLink(code).first()?.link?.label.orEmpty()
                if (handle.get<String>(KEY_LABEL) == null) {
                    handle[KEY_LABEL] = saved
                    _ui.update { it.copy(label = saved) }
                }
            }
        }
    }

    fun onLabelChange(label: String) {
        handle[KEY_LABEL] = label
        _ui.update { it.copy(label = label) }
        labelJob?.cancel()
        labelJob = viewModelScope.launch {
            delay(LABEL_SAVE_DELAY_MS)
            run { repository.setLabel(code, label) }
        }
    }

    fun add(sources: List<AttachmentSource>) {
        importer.start(
            sources = sources,
            appendTo = code,
            onSuccess = { _ui.update { it.copy(message = R.string.code_added) } },
            onError = { e -> _ui.update { it.copy(message = e.messageRes()) } },
        )
    }

    fun deleteAttachment(attachment: Attachment) {
        viewModelScope.launch {
            run {
                if (repository.deleteAttachment(attachment.id)) _ui.update { it.copy(deletedAttachmentId = attachment.id) }
            }
        }
    }

    fun undoDeleteAttachment(id: String) {
        viewModelScope.launch {
            run {
                if (!repository.undoDeleteAttachment(id)) _ui.update { it.copy(message = R.string.undo_too_late) }
            }
        }
    }

    /** Solo limpia si no se borró otro elemento mientras tanto. */
    fun onAttachmentUndoHandled(id: String) =
        _ui.update { if (it.deletedAttachmentId == id) it.copy(deletedAttachmentId = null) else it }

    fun deleteCode() {
        viewModelScope.launch {
            run {
                repository.delete(code)
                _ui.update { it.copy(codeDeleted = true) }
            }
        }
    }

    fun onMessageShown() = _ui.update { it.copy(message = null) }

    fun fileFor(attachment: Attachment): File? = repository.fileFor(attachment)

    /** Ejecuta [block] y muestra un mensaje si falla. */
    private suspend fun run(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Detail action failed", e)
            _ui.update { it.copy(message = e.messageRes()) }
        }
    }

    companion object {
        private const val KEY_LABEL = "detail_label"
        private const val LABEL_SAVE_DELAY_MS = 600L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val handle = createSavedStateHandle()
                DetailViewModel(handle, handle.toRoute<DetailDestination>().code, app.container.linkRepository)
            }
        }
    }
}
