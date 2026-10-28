package com.joasasso.paperlink.ui.screens.code

import android.net.Uri
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
import com.joasasso.paperlink.data.repository.BatchImporter
import com.joasasso.paperlink.data.repository.LinkContent
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.common.ContentImporter
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.ui.navigation.CodeDestination
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
 * Pantalla de un código: el código, su etiqueta (se guarda sola al dejar de escribir) y la grilla
 * de elementos. Agregar más, seleccionar y borrar varios con deshacer, o borrar el código entero.
 * La etiqueta en edición, la selección y si ya se vibró viven en [SavedStateHandle].
 */
class CodeViewModel(
    private val handle: SavedStateHandle,
    val code: String,
    val justCreated: Boolean,
    private val repository: LinkRepository,
    private val batchImporter: BatchImporter,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Missing : State
        data class Loaded(val content: LinkContent) : State
    }

    data class UiState(
        val label: String = "",
        /** Ids de los elementos seleccionados; vacío = fuera del modo selección. */
        val selected: Set<String> = emptySet(),
        /** Elementos recién borrados, para ofrecer "Deshacer". */
        val deletedIds: List<String> = emptyList(),
        /** El código se borró: volver a la Home (que ofrece deshacer). */
        val codeDeleted: Boolean = false,
        /** La etiqueta quedó guardada y se puede salir de la pantalla. */
        val done: Boolean = false,
        @StringRes val message: Int? = null,
    )

    val state: StateFlow<State> = repository.observeLinkContent(code)
        .map { if (it == null) State.Missing else State.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    private val _ui = MutableStateFlow(
        UiState(
            label = handle[KEY_LABEL] ?: "",
            selected = handle.get<ArrayList<String>>(KEY_SELECTED).orEmpty().toSet(),
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val importer = ContentImporter(viewModelScope, batchImporter)

    /** Carga en segundo plano de este código (por ejemplo, el resto de las fotos elegidas). */
    val backgroundImport: StateFlow<BatchImporter.Status?> = batchImporter.status(code)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun cancelBackgroundImport() = batchImporter.cancel(code)

    fun acknowledgeBackgroundImport() = batchImporter.acknowledge(code)

    /** Vibrar una sola vez al mostrar un código recién creado, aunque la pantalla se recree. */
    var shouldConfirm: Boolean = justCreated && handle.get<Boolean>(KEY_CONFIRMED) != true
        private set

    private var labelJob: Job? = null

    init {
        if (handle.get<String>(KEY_LABEL) == null) {
            viewModelScope.launch {
                val saved = repository.observeLink(code).first()?.link?.label.orEmpty()
                // Si el usuario ya empezó a escribir, no pisar lo que escribió.
                if (handle.get<String>(KEY_LABEL) == null) {
                    handle[KEY_LABEL] = saved
                    _ui.update { it.copy(label = saved) }
                }
            }
        }
    }

    fun onConfirmed() {
        shouldConfirm = false
        handle[KEY_CONFIRMED] = true
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

    /** Guarda la etiqueta ya (sin esperar) y después ejecuta [then]. */
    fun saveLabel(then: () -> Unit = {}) {
        labelJob?.cancel()
        viewModelScope.launch {
            run {
                repository.setLabel(code, _ui.value.label)
                then()
            }
        }
    }

    /** Salir de la pantalla: primero se guarda la etiqueta. */
    fun onDone() = saveLabel { _ui.update { it.copy(done = true) } }

    fun add(sources: List<AttachmentSource>) {
        importer.start(
            sources = sources,
            appendTo = code,
            // El progreso se ve en la tarjeta de carga en segundo plano.
            onSuccess = {},
            onError = { e -> _ui.update { it.copy(message = e.messageRes()) } },
        )
    }

    fun toggleSelection(id: String) = setSelection(
        _ui.value.selected.let { if (id in it) it - id else it + id }
    )

    fun clearSelection() = setSelection(emptySet())

    private fun setSelection(selected: Set<String>) {
        handle[KEY_SELECTED] = ArrayList(selected)
        _ui.update { it.copy(selected = selected) }
    }

    fun deleteSelected() {
        val ids = _ui.value.selected.toList()
        clearSelection()
        viewModelScope.launch {
            run {
                val deleted = ids.filter { repository.deleteAttachment(it) }
                if (deleted.isNotEmpty()) _ui.update { it.copy(deletedIds = deleted) }
            }
        }
    }

    fun undoDelete(ids: List<String>) {
        viewModelScope.launch {
            run {
                val restored = ids.count { repository.undoDeleteAttachment(it) }
                if (restored < ids.size) _ui.update { it.copy(message = R.string.undo_too_late) }
            }
        }
    }

    /** Solo limpia si no se borraron otros elementos mientras tanto. */
    fun onUndoHandled(ids: List<String>) =
        _ui.update { if (it.deletedIds == ids) it.copy(deletedIds = emptyList()) else it }

    fun deleteCode() {
        viewModelScope.launch {
            run {
                repository.delete(code)
                _ui.update { it.copy(codeDeleted = true) }
            }
        }
    }

    fun showMessage(@StringRes message: Int) = _ui.update { it.copy(message = message) }

    fun onMessageShown() = _ui.update { it.copy(message = null) }

    fun fileFor(attachment: Attachment): File? = repository.fileFor(attachment)

    fun uriFor(attachment: Attachment): Uri? = repository.uriFor(attachment)

    /** Ejecuta [block] y muestra un mensaje si falla. */
    private suspend fun run(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Code action failed", e)
            _ui.update { it.copy(message = e.messageRes()) }
        }
    }

    companion object {
        private const val KEY_LABEL = "code_label"
        private const val KEY_SELECTED = "code_selected"
        private const val KEY_CONFIRMED = "code_confirmed"
        private const val LABEL_SAVE_DELAY_MS = 600L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val handle = createSavedStateHandle()
                val route = handle.toRoute<CodeDestination>()
                CodeViewModel(handle, route.code, route.justCreated, app.container.linkRepository, app.container.batchImporter)
            }
        }
    }
}
