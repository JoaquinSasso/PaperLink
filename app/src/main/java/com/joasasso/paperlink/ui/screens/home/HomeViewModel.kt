package com.joasasso.paperlink.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.common.ContentImporter
import com.joasasso.paperlink.ui.common.messageRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Home temporal de la reescritura: cantidad de códigos, crear uno nuevo y volver al último.
 * Se reemplaza por la Home real en la fase 3.
 */
class HomeViewModel(repository: LinkRepository) : ViewModel() {

    /** null mientras se lee la base. */
    val linkCount: StateFlow<Int?> = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    val lastCreatedCode: StateFlow<String?> = repository.observeLastCreatedCode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    val importer = ContentImporter(viewModelScope, repository)

    /** Código recién creado desde la Home, para navegar a su pantalla. */
    private val _created = MutableStateFlow<String?>(null)
    val created: StateFlow<String?> = _created.asStateFlow()

    /** Recurso de string del último error, o null. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    fun create(sources: List<AttachmentSource>) {
        importer.start(
            sources = sources,
            appendTo = null,
            onSuccess = { _created.value = it },
            onError = { _message.value = it.messageRes() },
        )
    }

    fun onCreatedHandled() {
        _created.value = null
    }

    fun onMessageShown() {
        _message.value = null
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                HomeViewModel(app.container.linkRepository)
            }
        }
    }
}
