package com.joasasso.paperlink.ui.screens.txt

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.ui.navigation.TxtViewerDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TxtViewerUiState(
    val text: String = "",
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val code: String = ""
)

class TxtViewerViewModel(
    savedStateHandle: SavedStateHandle,
    private val application: Application
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<TxtViewerDestination>()
    
    private val _uiState = MutableStateFlow(TxtViewerUiState(code = destination.code))
    val uiState: StateFlow<TxtViewerUiState> = _uiState.asStateFlow()

    init {
        loadText(destination.uri)
    }

    fun onTextChanged(newText: String) {
        _uiState.update { it.copy(text = newText) }
    }

    fun saveChanges(onComplete: () -> Unit) {
        val currentText = _uiState.value.text
        val uriString = destination.uri
        
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val uri = Uri.parse(uriString)
                    application.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                        outputStream.bufferedWriter().use { it.write(currentText) }
                    } ?: throw Exception("No se pudo abrir el archivo para escritura")
                }
                onComplete()
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(errorMessage = "Error al guardar: ${e.localizedMessage}") 
                }
                // Incluso si falla, permitimos salir o mostramos el error? 
                // Por ahora mostramos error y no cerramos para no perder cambios.
            }
        }
    }

    private fun loadText(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val content = withContext(Dispatchers.IO) {
                    val uri = Uri.parse(uriString)
                    application.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().use { it.readText() }
                    } ?: throw Exception("No se pudo abrir el archivo")
                }
                _uiState.update { it.copy(text = content, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        errorMessage = "Error al leer el archivo: ${e.localizedMessage}"
                    ) 
                }
            }
        }
    }

    companion object {
        fun provideFactory(
            application: PaperLinkApp,
            savedStateHandle: SavedStateHandle
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TxtViewerViewModel(savedStateHandle, application)
            }
        }
    }
}
