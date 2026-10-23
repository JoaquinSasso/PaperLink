package com.joasasso.paperlink.ui.screens.code

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
import com.joasasso.paperlink.data.local.LinkWithAttachments
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.common.ContentImporter
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.ui.navigation.CodeDestination
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
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
 * Pantalla del código: se abre solo con códigos ya guardados en la base.
 * La etiqueta en edición y si ya se vibró viven en [SavedStateHandle].
 */
class CodeViewModel(
    private val handle: SavedStateHandle,
    val code: String,
    justCreated: Boolean,
    private val repository: LinkRepository,
) : ViewModel() {

    sealed interface LinkState {
        data object Loading : LinkState
        data object Missing : LinkState
        data class Loaded(val link: LinkWithAttachments) : LinkState
    }

    data class UiState(
        val label: String = "",
        val done: Boolean = false,
        @StringRes val message: Int? = null,
    )

    val link: StateFlow<LinkState> = repository.observeLink(code)
        .map { if (it == null) LinkState.Missing else LinkState.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkState.Loading)

    private val _state = MutableStateFlow(UiState(label = handle[KEY_LABEL] ?: ""))
    val state: StateFlow<UiState> = _state.asStateFlow()

    val importer = ContentImporter(viewModelScope, repository)

    /** Vibrar una sola vez al mostrar un código recién creado, aunque la pantalla se recree. */
    var shouldConfirm: Boolean = justCreated && handle.get<Boolean>(KEY_CONFIRMED) != true
        private set

    init {
        if (handle.get<String>(KEY_LABEL) == null) {
            viewModelScope.launch {
                val saved = repository.observeLink(code).first()?.link?.label.orEmpty()
                // Si el usuario ya empezó a escribir, no pisar lo que escribió.
                if (handle.get<String>(KEY_LABEL) == null) {
                    handle[KEY_LABEL] = saved
                    _state.update { it.copy(label = saved) }
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
        _state.update { it.copy(label = label) }
    }

    fun saveLabel(then: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                repository.setLabel(code, _state.value.label)
                then()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Could not save label", e)
                _state.update { it.copy(message = e.messageRes()) }
            }
        }
    }

    fun onDone() = saveLabel { _state.update { it.copy(done = true) } }

    fun add(sources: List<AttachmentSource>) {
        importer.start(
            sources = sources,
            appendTo = code,
            onSuccess = { _state.update { it.copy(message = R.string.code_added) } },
            onError = { e -> _state.update { it.copy(message = e.messageRes()) } },
        )
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    companion object {
        private const val KEY_LABEL = "code_label"
        private const val KEY_CONFIRMED = "code_confirmed"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val handle = createSavedStateHandle()
                val route = handle.toRoute<CodeDestination>()
                CodeViewModel(handle, route.code, route.justCreated, app.container.linkRepository)
            }
        }
    }
}
