package com.joasasso.paperlink.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.repository.LinkRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Home temporal de la reescritura: solo muestra cuántos códigos hay guardados.
 * Se reemplaza por la Home real en la fase 3.
 */
class HomeViewModel(repository: LinkRepository) : ViewModel() {

    /** null mientras se lee la base. */
    val linkCount: StateFlow<Int?> = repository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue = null)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                HomeViewModel(app.container.linkRepository)
            }
        }
    }
}
