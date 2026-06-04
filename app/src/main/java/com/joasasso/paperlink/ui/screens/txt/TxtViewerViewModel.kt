package com.joasasso.paperlink.ui.screens.txt

import android.app.Application
import android.net.Uri
import android.util.Log
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
    val code: String = "",
    val isMarkdown: Boolean = false,
    val isEditMode: Boolean = false
)

class TxtViewerViewModel(
    savedStateHandle: SavedStateHandle,
    private val application: Application
) : ViewModel() {

    private val destination = savedStateHandle.toRoute<TxtViewerDestination>()
    
    private val _uiState = MutableStateFlow(TxtViewerUiState(code = destination.code))
    val uiState: StateFlow<TxtViewerUiState> = _uiState.asStateFlow()

    init {
        val uriString = destination.uri
        val uri = Uri.parse(uriString)
        val mimeType = application.contentResolver.getType(uri)
        
        val isMd = uriString.endsWith(".md", ignoreCase = true) || 
                   mimeType == "text/markdown"
        
        Log.d("PaperLinkDebug", "TxtViewerViewModel inicializado. URI: $uriString, MimeType: $mimeType, isMarkdown: $isMd")
        _uiState.update { it.copy(isMarkdown = isMd, isEditMode = !isMd) }
        loadText(uriString)
    }

    fun onTextChanged(newText: String) {
        _uiState.update { it.copy(text = newText) }
    }

    fun toggleEditMode() {
        _uiState.update { it.copy(isEditMode = !it.isEditMode) }
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
            Log.d("PaperLinkDebug", "Iniciando carga de texto desde: $uriString")
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val content = withContext(Dispatchers.IO) {
                    val uri = Uri.parse(uriString)
                    application.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().use { it.readText() }
                    } ?: throw Exception("No se pudo abrir el archivo")
                }
                Log.d("PaperLinkDebug", "Texto cargado con éxito. Longitud: ${content.length}")
                _uiState.update { it.copy(text = content, isLoading = false) }
            } catch (e: Exception) {
                Log.e("PaperLinkDebug", "Error al cargar texto: ${e.localizedMessage}")
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
