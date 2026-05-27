package com.joasasso.paperlink.ui.screens.home

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado de la UI para la pantalla principal.
 */
data class HomeUiState(
    val searchQuery: String = "",
    val isQueryValid: Boolean = false,
    val recentLinks: List<PaperLink> = emptyList()
)

/**
 * ViewModel encargado de la pantalla principal.
 * Maneja el flujo continuo de elementos recientes y la validación del campo de búsqueda.
 */
class HomeViewModel(
    private val repository: PaperLinkRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Observa reactivamente las últimas 10 referencias guardadas mediante un StateFlow caliente
    val recentLinks: StateFlow<List<PaperLink>> = repository.getRecent(limit = 10)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onSearchQueryChanged(newQuery: String) {
        // Forzamos la normalización (Mayúsculas y sin espacios) al vuelo según las leyes de la app
        val normalized = CodeAlphabet.normalize(newQuery)
        if (normalized.length <= CodeAlphabet.CODE_LENGTH) {
            _uiState.value = _uiState.value.copy(
                searchQuery = normalized,
                isQueryValid = CodeAlphabet.isValid(normalized)
            )
        }
    }

    /**
     * Factoría para inyectar manualmente el repositorio desde el AppContainer
     */
    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as PaperLinkApp)
                HomeViewModel(application.container.paperLinkRepository)
            }
        }
    }
}
