package com.joasasso.paperlink.ui.screens.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.CodeAlphabet
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Estado Visual-First para el Home.
 */
data class HomeUiState(
    val searchQuery: String = "",
    val isQueryValid: Boolean = false,
    val links: List<PaperLink> = emptyList(),
    val linkToDelete: PaperLink? = null
)

class HomeViewModel(
    private val repository: PaperLinkRepository,
    private val application: Application
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = combine(
        _uiState,
        repository.getAllLinks()
    ) { state, links ->
        state.copy(links = links)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun onSearchQueryChanged(newQuery: String) {
        val normalized = CodeAlphabet.normalize(newQuery)
        if (normalized.length <= CodeAlphabet.CODE_LENGTH) {
            _uiState.update { it.copy(
                searchQuery = normalized,
                isQueryValid = CodeAlphabet.isValid(normalized)
            ) }
        }
    }

    fun confirmDelete(link: PaperLink?) {
        _uiState.update { it.copy(linkToDelete = link) }
    }

    fun deleteLink() {
        val link = _uiState.value.linkToDelete ?: return
        viewModelScope.launch {
            repository.delete(link, application.filesDir)
            _uiState.update { it.copy(linkToDelete = null) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as PaperLinkApp)
                HomeViewModel(application.container.paperLinkRepository, application)
            }
        }
    }
}
