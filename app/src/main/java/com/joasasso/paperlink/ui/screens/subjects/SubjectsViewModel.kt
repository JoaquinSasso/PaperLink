package com.joasasso.paperlink.ui.screens.subjects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.local.Subject
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.ui.theme.DEFAULT_SUBJECT_COLOR
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Estado del diálogo de crear/editar materia.
 * [editingId] null => estamos creando; con valor => editando esa materia.
 */
data class SubjectDialogState(
    val visible: Boolean = false,
    val editingId: Long? = null,
    val name: String = "",
    val colorHex: String = DEFAULT_SUBJECT_COLOR,
    val error: String? = null
)

class SubjectsViewModel(
    private val repository: PaperLinkRepository
) : ViewModel() {

    val subjects: StateFlow<List<Subject>> = repository.getAllSubjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _dialog = MutableStateFlow(SubjectDialogState())
    val dialog: StateFlow<SubjectDialogState> = _dialog.asStateFlow()

    fun openAdd() {
        _dialog.value = SubjectDialogState(visible = true)
    }

    fun openEdit(subject: Subject) {
        _dialog.value = SubjectDialogState(
            visible = true,
            editingId = subject.id,
            name = subject.name,
            colorHex = subject.colorHex
        )
    }

    fun onNameChange(value: String) {
        if (value.length <= MAX_NAME_LENGTH) {
            _dialog.value = _dialog.value.copy(name = value, error = null)
        }
    }

    fun onColorChange(hex: String) {
        _dialog.value = _dialog.value.copy(colorHex = hex)
    }

    fun dismissDialog() {
        _dialog.value = SubjectDialogState()
    }

    fun save() {
        val state = _dialog.value
        val name = state.name.trim()
        if (name.isBlank()) {
            _dialog.value = state.copy(error = "El nombre no puede estar vacío")
            return
        }
        viewModelScope.launch {
            try {
                if (state.editingId == null) {
                    val id = repository.insertSubject(Subject(name = name, colorHex = state.colorHex))
                    if (id == -1L) {
                        _dialog.value = _dialog.value.copy(error = "Ya existe una materia con ese nombre")
                        return@launch
                    }
                } else {
                    repository.updateSubject(
                        Subject(id = state.editingId, name = name, colorHex = state.colorHex)
                    )
                }
                dismissDialog()
            } catch (e: Exception) {
                // El índice único puede saltar al editar a un nombre ya existente
                _dialog.value = _dialog.value.copy(error = "Ya existe una materia con ese nombre")
            }
        }
    }

    fun delete(subject: Subject) {
        viewModelScope.launch { repository.deleteSubject(subject) }
    }

    companion object {
        const val MAX_NAME_LENGTH = 40

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as PaperLinkApp
                SubjectsViewModel(app.container.paperLinkRepository)
            }
        }
    }
}
