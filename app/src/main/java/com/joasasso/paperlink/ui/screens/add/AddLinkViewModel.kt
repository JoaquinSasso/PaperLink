package com.joasasso.paperlink.ui.screens.add

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddLinkUiState(
    val selectedType: ContentType? = null,
    val contentUri: String = "",
    val displayName: String = "",
    val note: String = "",
    val generatedCode: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AddLinkViewModel(
    private val repository: PaperLinkRepository,
    private val generateCodeUseCase: GenerateCodeUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddLinkUiState())
    val uiState: StateFlow<AddLinkUiState> = _uiState.asStateFlow()

    fun onTypeSelected(type: ContentType) {
        _uiState.value = _uiState.value.copy(selectedType = type, contentUri = "", displayName = "")
    }

    fun onNoteChanged(newNote: String) {
        if (newNote.length <= 200) {
            _uiState.value = _uiState.value.copy(note = newNote)
        }
    }

    fun onWebUrlEntered(url: String) {
        _uiState.value = _uiState.value.copy(
            contentUri = url,
            displayName = url.substringAfterLast("/")
        )
    }

    /**
     * Procesa el recurso local obtenido mediante el SAF (ACTION_OPEN_DOCUMENT).
     * Aplica el permiso persistente obligatorio solicitado por las leyes Local-First.
     */
    fun onLocalResourceSelected(context: Context, uri: Uri) {
        try {
            // Ley 5: Offline por defecto y persistencia extendida de permisos de Android
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

            // Extracción del meta-dato displayName directamente del DocumentProvider del sistema
            var name = ""
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
            }

            _uiState.value = _uiState.value.copy(
                contentUri = uri.toString(),
                displayName = name.ifBlank { "Archivo Local" }
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(errorMessage = "Error al persistir permiso: ${e.localizedMessage}")
        }
    }

    fun saveLink() {
        val state = _uiState.value
        if (state.contentUri.isBlank() || state.selectedType == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Invocación segura al caso de uso de dominio (Fase 3) garantizando unicidad
                val code = generateCodeUseCase()

                val newLink = PaperLink(
                    code = code,
                    contentType = state.selectedType,
                    contentUri = state.contentUri,
                    displayName = state.displayName.ifBlank { null },
                    note = state.note.ifBlank { null },
                    createdAt = System.currentTimeMillis()
                )

                repository.insert(newLink)
                _uiState.value = _uiState.value.copy(generatedCode = code, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.localizedMessage, isLoading = false)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as PaperLinkApp)
                AddLinkViewModel(
                    application.container.paperLinkRepository,
                    application.container.generateCodeUseCase
                )
            }
        }
    }
}
