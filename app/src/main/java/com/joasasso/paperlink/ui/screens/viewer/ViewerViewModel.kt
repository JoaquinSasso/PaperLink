package com.joasasso.paperlink.ui.screens.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.repository.LinkContent
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.navigation.ViewerDestination
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/** Visor de las fotos de un código, deslizables. La página actual la guarda el pager. */
class ViewerViewModel(
    val code: String,
    val startId: String?,
    private val repository: LinkRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data object Missing : State
        data class Loaded(val content: LinkContent) : State
    }

    val state: StateFlow<State> = repository.observeLinkContent(code)
        .map { if (it == null) State.Missing else State.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State.Loading)

    fun fileFor(attachment: Attachment): File? = repository.fileFor(attachment)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val route = createSavedStateHandle().toRoute<ViewerDestination>()
                ViewerViewModel(route.code, route.startId, app.container.linkRepository)
            }
        }
    }
}
