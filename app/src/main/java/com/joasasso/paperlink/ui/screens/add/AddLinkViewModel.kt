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
import com.joasasso.paperlink.data.local.Subject
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase
import com.joasasso.paperlink.ui.theme.DEFAULT_SUBJECT_COLOR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AddLinkUiState(
    val selectedType: ContentType? = null,
    val contentUri: String = "",
    val displayName: String = "",
    val note: String = "",
    val selectedSubjectId: Long? = null,
    val generatedCode: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    // Estado del mini-diálogo de "crear materia al vuelo"
    val creatingSubject: Boolean = false,
    val newSubjectName: String = "",
    val newSubjectColor: String = DEFAULT_SUBJECT_COLOR,
    val subjectError: String? = null
)

class AddLinkViewModel(
    private val repository: PaperLinkRepository,
    private val generateCodeUseCase: GenerateCodeUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddLinkUiState())
    val uiState: StateFlow<AddLinkUiState> = _uiState.asStateFlow()

    /** Lista de materias disponibles para el selector. */
    val subjects: StateFlow<List<Subject>> = repository.getAllSubjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun onSubjectSelected(id: Long?) {
        _uiState.value = _uiState.value.copy(selectedSubjectId = id)
    }

    // ---- Crear materia al vuelo ----

    fun openCreateSubject() {
        _uiState.value = _uiState.value.copy(
            creatingSubject = true,
            newSubjectName = "",
            newSubjectColor = DEFAULT_SUBJECT_COLOR,
            subjectError = null
        )
    }

    fun onNewSubjectName(value: String) {
        if (value.length <= 40) {
            _uiState.value = _uiState.value.copy(newSubjectName = value, subjectError = null)
        }
    }

    fun onNewSubjectColor(hex: String) {
        _uiState.value = _uiState.value.copy(newSubjectColor = hex)
    }

    fun dismissCreateSubject() {
        _uiState.value = _uiState.value.copy(creatingSubject = false, subjectError = null)
    }

    fun confirmCreateSubject() {
        val name = _uiState.value.newSubjectName.trim()
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(subjectError = "El nombre no puede estar vacío")
            return
        }
        viewModelScope.launch {
            val id = repository.insertSubject(
                Subject(name = name, colorHex = _uiState.value.newSubjectColor)
            )
            if (id == -1L) {
                _uiState.value = _uiState.value.copy(subjectError = "Ya existe una materia con ese nombre")
            } else {
                // La materia recién creada queda seleccionada automáticamente
                _uiState.value = _uiState.value.copy(
                    selectedSubjectId = id,
                    creatingSubject = false,
                    newSubjectName = "",
                    subjectError = null
                )
            }
        }
    }

    /**
     * Procesa el recurso local obtenido por SAF (ACTION_OPEN_DOCUMENT) y persiste
     * el permiso de lectura para que el acceso sobreviva al reinicio del proceso.
     */
    fun onLocalResourceSelected(context: Context, uri: Uri) {
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)

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
            _uiState.value = _uiState.value.copy(
                errorMessage = "Error al persistir permiso: ${e.localizedMessage}"
            )
        }
    }

    fun saveLink() {
        val state = _uiState.value
        if (state.contentUri.isBlank() || state.selectedType == null) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val code = generateCodeUseCase()
                val newLink = PaperLink(
                    code = code,
                    contentType = state.selectedType,
                    contentUri = state.contentUri,
                    displayName = state.displayName.ifBlank { null },
                    note = state.note.ifBlank { null },
                    createdAt = System.currentTimeMillis(),
                    subjectId = state.selectedSubjectId
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
