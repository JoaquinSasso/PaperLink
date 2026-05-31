package com.joasasso.paperlink.ui.screens.add

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Estado radical para añadir un vínculo.
 * Se eliminan títulos, notas y materias.
 */
data class AddLinkUiState(
    val selectedType: ContentType? = null,
    val contentUri: String = "",
    val nativeNoteContent: String = "", 
    val generatedCode: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class AddLinkViewModel(
    private val repository: PaperLinkRepository,
    private val generateCodeUseCase: GenerateCodeUseCase,
    private val application: Application
) : ViewModel() {

    private val contentResolver: ContentResolver = application.contentResolver
    private val filesDir: File = application.filesDir
    private val packageName: String = application.packageName

    private val _uiState = MutableStateFlow(AddLinkUiState())
    val uiState: StateFlow<AddLinkUiState> = _uiState.asStateFlow()

    fun onTypeSelected(type: ContentType) {
        _uiState.update {
            it.copy(
                selectedType = type,
                contentUri = "",
                nativeNoteContent = "",
                generatedCode = null
            )
        }
    }

    fun onNativeNoteContentChanged(content: String) {
        if (content.length <= 5000) {
            _uiState.update { it.copy(nativeNoteContent = content) }
        }
    }

    fun onWebUrlEntered(url: String) {
        _uiState.update { it.copy(contentUri = url) }
    }

    /**
     * Procesa un archivo local pidiendo permisos permanentes.
     */
    fun onLocalResourceSelected(uri: Uri) {
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            _uiState.update { it.copy(contentUri = uri.toString()) }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Error de permisos: ${e.localizedMessage}") }
        }
    }

    fun saveLink() {
        val state = _uiState.value
        if (state.selectedType == null) return

        val isTextNote = state.selectedType == ContentType.TEXT_NOTE
        if (!isTextNote && state.contentUri.isBlank()) return
        if (isTextNote && state.nativeNoteContent.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val code = generateCodeUseCase()
                var finalUri = state.contentUri

                if (isTextNote) {
                    finalUri = withContext(Dispatchers.IO) {
                        val notesDir = File(filesDir, "notes")
                        if (!notesDir.exists()) notesDir.mkdirs()
                        val file = File(notesDir, "nota_${code}.txt")
                        file.writeText(state.nativeNoteContent)

                        FileProvider.getUriForFile(
                            application,
                            "$packageName.fileprovider",
                            file
                        ).toString()
                    }
                }

                val newLink = PaperLink(
                    code = code,
                    contentType = state.selectedType,
                    contentUri = finalUri
                )

                repository.insert(newLink)
                _uiState.update { it.copy(generatedCode = code, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.localizedMessage, isLoading = false) }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val application = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as com.joasasso.paperlink.PaperLinkApp
                return AddLinkViewModel(
                    application.container.paperLinkRepository,
                    application.container.generateCodeUseCase,
                    application
                ) as T
            }
        }
    }
}
