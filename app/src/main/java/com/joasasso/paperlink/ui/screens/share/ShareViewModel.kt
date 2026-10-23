package com.joasasso.paperlink.ui.screens.share

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
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.ImportProgress
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.domain.CodeAlphabet
import com.joasasso.paperlink.domain.extractUrls
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.ui.navigation.ShareDestination
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Recepción de contenido compartido desde otra app.
 *
 * Los archivos se copian a `media/` apenas llegan, antes de elegir destino, porque el permiso
 * de lectura de la URI recibida puede vencer. Lo ya copiado se guarda en [SavedStateHandle].
 * Si una copia falla, se avisa y no se crea ningún vínculo.
 */
class ShareViewModel(
    private val handle: SavedStateHandle,
    private val uris: List<Uri>,
    sharedText: String?,
    private val store: AttachmentStore,
    private val repository: LinkRepository,
) : ViewModel() {

    sealed interface Phase {
        data class Copying(val fileIndex: Int, val fileCount: Int, val progress: ImportProgress?) : Phase
        data object Ready : Phase
        data object Saving : Phase
        data class Failed(@StringRes val message: Int) : Phase
    }

    data class UiState(
        val phase: Phase,
        val fileCount: Int,
        val linkCount: Int,
        val hasNote: Boolean,
        @StringRes val message: Int? = null,
        /** Resultado: código donde quedó guardado y si es nuevo. */
        val savedCode: String? = null,
        val createdNew: Boolean = false,
        val closed: Boolean = false,
    )

    private val textSources: List<AttachmentSource> = sharedText?.trim().orEmpty().let { text ->
        val urls = extractUrls(text)
        when {
            urls.isNotEmpty() -> urls.map { AttachmentSource.WebUrl(it) }
            text.isNotEmpty() -> listOf(AttachmentSource.Note(text))
            else -> emptyList()
        }
    }

    private val _state = MutableStateFlow(
        UiState(
            phase = when {
                uris.isEmpty() && textSources.isEmpty() -> Phase.Failed(R.string.share_nothing)
                staged().size < uris.size -> Phase.Copying(staged().size, uris.size, null)
                else -> Phase.Ready
            },
            fileCount = uris.size,
            linkCount = textSources.count { it is AttachmentSource.WebUrl },
            hasNote = textSources.any { it is AttachmentSource.Note },
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (_state.value.phase is Phase.Copying) copyFiles()
    }

    fun createNew() = save(appendTo = null)

    fun addToExisting(input: String) {
        val code = CodeAlphabet.normalize(input)
        if (!CodeAlphabet.isValid(code)) {
            _state.update { it.copy(message = R.string.error_invalid_code) }
            return
        }
        save(appendTo = code)
    }

    /** Descarta lo copiado y cierra. */
    fun cancel() {
        viewModelScope.launch {
            for (file in staged()) {
                if (!store.delete(file.relativePath)) log("Could not delete staged file")
            }
            handle[KEY_STAGED] = Json.encodeToString(emptyList<AttachmentStore.ImportResult>())
            _state.update { it.copy(closed = true) }
        }
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    private fun copyFiles() {
        viewModelScope.launch {
            try {
                // Retoma desde donde quedó si el proceso murió a mitad de la copia.
                for (index in staged().size until uris.size) {
                    _state.update { it.copy(phase = Phase.Copying(index, uris.size, null)) }
                    val result = store.import(uris[index]) { copied, total ->
                        _state.update { it.copy(phase = Phase.Copying(index, uris.size, ImportProgress(index, uris.size, copied, total))) }
                    }
                    handle[KEY_STAGED] = Json.encodeToString(staged() + result)
                }
                _state.update { it.copy(phase = Phase.Ready) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Could not copy shared file", e)
                for (file in staged()) {
                    if (!store.delete(file.relativePath)) log("Could not delete staged file")
                }
                handle[KEY_STAGED] = Json.encodeToString(emptyList<AttachmentStore.ImportResult>())
                _state.update { it.copy(phase = Phase.Failed(e.messageRes())) }
            }
        }
    }

    private fun save(appendTo: String?) {
        if (_state.value.phase != Phase.Ready) return
        _state.update { it.copy(phase = Phase.Saving) }
        viewModelScope.launch {
            val sources = staged().map { AttachmentSource.Stored(it) } + textSources
            try {
                val code = if (appendTo == null) {
                    repository.createLink(sources)
                } else {
                    repository.addAttachments(appendTo, sources)
                    appendTo
                }
                _state.update { it.copy(savedCode = code, createdNew = appendTo == null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Lo copiado se conserva para poder reintentar (por ejemplo, con otro código).
                log("Could not save shared content", e)
                _state.update { it.copy(phase = Phase.Ready, message = e.messageRes()) }
            }
        }
    }

    private fun staged(): List<AttachmentStore.ImportResult> =
        handle.get<String>(KEY_STAGED)?.let { Json.decodeFromString(it) } ?: emptyList()

    companion object {
        private const val KEY_STAGED = "share_staged"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val handle = createSavedStateHandle()
                val route = handle.toRoute<ShareDestination>()
                ShareViewModel(
                    handle = handle,
                    uris = route.uris.map(Uri::parse),
                    sharedText = route.text,
                    store = app.container.attachmentStore,
                    repository = app.container.linkRepository,
                )
            }
        }
    }
}
