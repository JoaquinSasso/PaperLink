package com.joasasso.paperlink.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DetailUiState(
    val link: PaperLink? = null,
    val isLoading: Boolean = true,
    val isUriInvalid: Boolean = false
)

class DetailViewModel(
    private val repository: PaperLinkRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    fun loadLink(code: String) {
        viewModelScope.launch {
            val paperLink = repository.getByCode(code)
            _uiState.value = _uiState.value.copy(
                link = paperLink,
                isLoading = false,
                isUriInvalid = paperLink == null
            )
        }
    }

    fun markUriAsInvalid() {
        _uiState.value = _uiState.value.copy(isUriInvalid = true)
    }

    fun deleteLink() {
        _uiState.value.link?.let {
            viewModelScope.launch {
                repository.deleteByCode(it.code)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as PaperLinkApp)
                DetailViewModel(application.container.paperLinkRepository)
            }
        }
    }
}
