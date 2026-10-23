package com.joasasso.paperlink.ui.screens.home

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.LinkWithAttachments
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.domain.CodeAlphabet
import com.joasasso.paperlink.domain.suggestCodes
import com.joasasso.paperlink.ui.common.ContentImporter
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home: campo de código (abre al cuarto carácter), recientes y búsqueda por etiqueta.
 * Lo escrito en el campo y en la búsqueda vive en [SavedStateHandle].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val handle: SavedStateHandle,
    private val repository: LinkRepository,
) : ViewModel() {

    /** Resultado de buscar el código escrito. */
    sealed interface Lookup {
        data object Idle : Lookup
        data class NotFound(val code: String, val hasInvalidChars: Boolean, val suggestions: List<String>) : Lookup
    }

    data class Events(
        /** Código a abrir en el visor. */
        val open: String? = null,
        /** Código recién creado desde "Nuevo", para mostrar su pantalla. */
        val created: String? = null,
        @StringRes val message: Int? = null,
    )

    val code: StateFlow<String> = handle.getStateFlow(KEY_CODE, "")
    val query: StateFlow<String> = handle.getStateFlow(KEY_QUERY, "")

    private val _lookup = MutableStateFlow<Lookup>(Lookup.Idle)
    val lookup: StateFlow<Lookup> = _lookup.asStateFlow()

    private val _events = MutableStateFlow(Events())
    val events: StateFlow<Events> = _events.asStateFlow()

    /** null mientras se lee la base. */
    val recent: StateFlow<List<LinkWithAttachments>?> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    /** null si no hay búsqueda activa. */
    val searchResults: StateFlow<List<LinkWithAttachments>?> = query
        .flatMapLatest { q -> if (q.isBlank()) flowOf(null) else repository.searchByLabel(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    val importer = ContentImporter(viewModelScope, repository)

    private var lookupJob: Job? = null

    init {
        // Tras recrear la pantalla con 4 caracteres escritos, rehacer la búsqueda.
        if (code.value.length == CodeAlphabet.CODE_LENGTH) lookUp(code.value, openIfFound = false)
    }

    fun onCodeChange(input: String) {
        val sanitized = CodeAlphabet.sanitizeInput(input)
        handle[KEY_CODE] = sanitized
        lookupJob?.cancel()
        _lookup.value = Lookup.Idle
        if (sanitized.length == CodeAlphabet.CODE_LENGTH) {
            log("Lookup started")
            lookUp(sanitized, openIfFound = true)
        }
    }

    /** Abre un código sugerido o de la lista. */
    fun open(code: String) {
        handle[KEY_CODE] = ""
        _lookup.value = Lookup.Idle
        _events.update { it.copy(open = code) }
    }

    fun onQueryChange(query: String) {
        handle[KEY_QUERY] = query
    }

    fun create(sources: List<AttachmentSource>) {
        importer.start(
            sources = sources,
            appendTo = null,
            onSuccess = { code -> _events.update { it.copy(created = code) } },
            onError = { e -> _events.update { it.copy(message = e.messageRes()) } },
        )
    }

    /** Deshacer el borrado de un código hecho desde su detalle. */
    fun undoDelete(code: String) {
        viewModelScope.launch {
            if (!repository.undoDelete(code)) _events.update { it.copy(message = R.string.undo_too_late) }
        }
    }

    fun fileFor(attachment: Attachment): File? = repository.fileFor(attachment)

    fun onOpenHandled() = _events.update { it.copy(open = null) }
    fun onCreatedHandled() = _events.update { it.copy(created = null) }
    fun onMessageShown() = _events.update { it.copy(message = null) }

    private fun lookUp(code: String, openIfFound: Boolean) {
        lookupJob = viewModelScope.launch {
            try {
                val valid = CodeAlphabet.isValid(code)
                if (valid && repository.getLink(code) != null) {
                    if (openIfFound) open(code)
                    return@launch
                }
                _lookup.value = Lookup.NotFound(code, !valid, suggestCodes(code, repository.allCodes()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Code lookup failed", e)
                _events.update { it.copy(message = e.messageRes()) }
            }
        }
    }

    companion object {
        private const val KEY_CODE = "home_code"
        private const val KEY_QUERY = "home_query"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                HomeViewModel(createSavedStateHandle(), app.container.linkRepository)
            }
        }
    }
}
