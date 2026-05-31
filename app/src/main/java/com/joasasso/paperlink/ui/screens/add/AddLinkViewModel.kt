package com.joasasso.paperlink.ui.screens.add

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.local.Subject
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase
import com.joasasso.paperlink.ui.theme.SubjectColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Estado que representa todos los datos que el usuario está manipulando
 * antes de darle al botón "Generar Código".
 */
data class AddLinkUiState(
    val selectedType: ContentType? = null,
    val contentUri: String = "",
    val displayName: String = "",
    val note: String = "",
    val nativeNoteContent: String = "", // [NUEVO] Contenido del apunte de texto largo
    val selectedSubjectId: Long? = null,
    val isCreatingSubject: Boolean = false,
    val newSubjectName: String = "",
    val newSubjectColorHex: String = SubjectColors.palette.first(),
    val subjects: List<Subject> = emptyList(),
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

    // Combinamos el estado local con el flujo de materias de la base de datos
    val uiState: StateFlow<AddLinkUiState> = combine(
        _uiState,
        repository.getAllSubjects().distinctUntilChanged()
    ) { state, subjects ->
        state.copy(subjects = subjects)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AddLinkUiState())

    fun onTypeSelected(type: ContentType) {
        _uiState.update {
            it.copy(
                selectedType = type,
                contentUri = "",
                displayName = "",
                nativeNoteContent = "",
                generatedCode = null
            )
        }
    }

    fun onNoteChanged(newNote: String) {
        if (newNote.length <= 200) _uiState.update { it.copy(note = newNote) }
    }

    // [NUEVO] Método para actualizar el cuerpo del apunte de texto largo
    fun onNativeNoteContentChanged(content: String) {
        if (content.length <= 5000) {
            _uiState.update { it.copy(nativeNoteContent = content) }
        }
    }

    fun onWebUrlEntered(url: String) {
        _uiState.update { it.copy(
            contentUri = url,
            displayName = url.substringAfterLast("/")
        ) }
    }

    fun selectSubject(subjectId: Long?) {
        _uiState.update { it.copy(selectedSubjectId = subjectId) }
    }

    fun showCreateSubjectDialog(show: Boolean) {
        _uiState.update { it.copy(isCreatingSubject = show, newSubjectName = "", errorMessage = null) }
    }

    fun onNewSubjectNameChanged(name: String) {
        _uiState.update { it.copy(newSubjectName = name) }
    }

    fun onNewSubjectColorChanged(colorHex: String) {
        _uiState.update { it.copy(newSubjectColorHex = colorHex) }
    }

    fun createSubject() {
        val state = _uiState.value
        if (state.newSubjectName.isBlank()) return

        viewModelScope.launch {
            val newSubject = Subject(name = state.newSubjectName.trim(), colorHex = state.newSubjectColorHex)
            val id = repository.insertSubject(newSubject) // Esto devuelve un Long
            if (id != -1L) {
                _uiState.update { it.copy(isCreatingSubject = false, selectedSubjectId = id, errorMessage = null) }
            } else {
                _uiState.update { it.copy(errorMessage = "Ya existe una materia con ese nombre") }
            }
        }
    }

    /**
     * Procesa un archivo local (PDF, Foto) pidiendo permisos permanentes (SAF).
     * Esto evita que la app copie archivos pesados.
     */
    fun onLocalResourceSelected(uri: Uri) {
        try {
            val takeFlags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)

            var name = ""
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) name = cursor.getString(nameIndex)
            }

            _uiState.update { it.copy(
                contentUri = uri.toString(),
                displayName = name.ifBlank { "Archivo Local" }
            ) }
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Error al persistir permiso: ${e.localizedMessage}") }
        }
    }

    /**
     * Función principal de guardado.
     * Ahora utiliza las dependencias inyectadas para acceder a archivos internos.
     */
    fun saveLink() {
        val state = _uiState.value
        if (state.selectedType == null) return

        // 1. Validación estricta antes de procesar
        val isTextNote = state.selectedType == ContentType.TEXT_NOTE
        if (!isTextNote && state.contentUri.isBlank()) return
        if (isTextNote && state.nativeNoteContent.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Generamos matemáticamente el código único (Ej: H9W2)
                val code = generateCodeUseCase()
                var finalUri = state.contentUri
                var finalDisplayName = state.displayName

                //  Si es una nota nativa, creamos el archivo físico y obtenemos su URI
                if (isTextNote) {
                    finalUri = withContext(Dispatchers.IO) {
                        // Creamos la carpeta 'notes' en el almacenamiento oculto de la app
                        val notesDir = File(filesDir, "notes")
                        if (!notesDir.exists()) notesDir.mkdirs()

                        // Creamos el archivo .txt nombrandolo con el código generado
                        val file = File(notesDir, "nota_${code}.txt")
                        file.writeText(state.nativeNoteContent)

                        // Usamos FileProvider para generar una URI segura que se pueda leer externamente
                        FileProvider.getUriForFile(
                            application,
                            "$packageName.fileprovider",
                            file
                        ).toString()
                    }
                    finalDisplayName = "Apunte ($code)"
                }

                // 2. Construcción final del vínculo
                val newLink = PaperLink(
                    code = code,
                    contentType = state.selectedType,
                    contentUri = finalUri,
                    displayName = finalDisplayName.ifBlank { null },
                    note = state.note.ifBlank { null },
                    subjectId = state.selectedSubjectId
                )

                repository.insert(newLink)
                // Éxito: avisamos a la UI para que navegue a la pantalla de detalle
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
