package com.joasasso.paperlink.ui.screens.add

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.ui.components.ColorPickerRow
import com.joasasso.paperlink.ui.theme.SubjectColorPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: AddLinkViewModel = viewModel(factory = AddLinkViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val subjects by viewModel.subjects.collectAsState()
    val context = LocalContext.current

    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onLocalResourceSelected(context, it) }
    }

    LaunchedEffect(uiState.generatedCode) {
        uiState.generatedCode?.let { onNavigateToDetail(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.selectedType == null) {
                Text(
                    text = stringResource(R.string.add_select_content_type),
                    style = MaterialTheme.typography.titleMedium
                )

                ContentType.entries.forEach { type ->
                    Button(
                        onClick = {
                            viewModel.onTypeSelected(type)
                            if (type != ContentType.WEB_LINK) {
                                val mimeType = when (type) {
                                    ContentType.IMAGE -> "image/*"
                                    ContentType.VIDEO -> "video/*"
                                    ContentType.AUDIO -> "audio/*"
                                    ContentType.PDF -> "application/pdf"
                                    else -> "*/*"
                                }
                                safLauncher.launch(arrayOf(mimeType))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(type.name)
                    }
                }
            } else {
                Text(
                    text = "Tipo seleccionado: ${uiState.selectedType?.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )

                if (uiState.selectedType == ContentType.WEB_LINK) {
                    OutlinedTextField(
                        value = uiState.contentUri,
                        onValueChange = { viewModel.onWebUrlEntered(it) },
                        label = { Text("Pegar enlace URL (https://...)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = uiState.displayName.ifBlank { "Ningún archivo seleccionado" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = uiState.note,
                    onValueChange = { viewModel.onNoteChanged(it) },
                    label = { Text(stringResource(R.string.add_note_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                // Selector de materia (opcional)
                SubjectSelector(
                    subjects = subjects,
                    selectedId = uiState.selectedSubjectId,
                    onSelect = viewModel::onSubjectSelected,
                    onCreateNew = viewModel::openCreateSubject
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Button(
                        onClick = { viewModel.saveLink() },
                        enabled = uiState.contentUri.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.add_generate_btn))
                    }
                }
            }
        }
    }

    if (uiState.creatingSubject) {
        CreateSubjectDialog(
            name = uiState.newSubjectName,
            color = uiState.newSubjectColor,
            error = uiState.subjectError,
            onNameChange = viewModel::onNewSubjectName,
            onColorChange = viewModel::onNewSubjectColor,
            onConfirm = viewModel::confirmCreateSubject,
            onDismiss = viewModel::dismissCreateSubject
        )
    }
}

@Composable
private fun SubjectSelector(
    subjects: List<com.joasasso.paperlink.data.local.Subject>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    onCreateNew: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = subjects.firstOrNull { it.id == selectedId }?.name
        ?: stringResource(R.string.add_subject_none)

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "${stringResource(R.string.add_subject_label)}: $selectedName",
                modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_subject_none)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            subjects.forEach { subject ->
                DropdownMenuItem(
                    text = { Text(subject.name) },
                    onClick = {
                        onSelect(subject.id)
                        expanded = false
                    }
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_subject_create)) },
                onClick = {
                    expanded = false
                    onCreateNew()
                }
            )
        }
    }
}

@Composable
private fun CreateSubjectDialog(
    name: String,
    color: String,
    error: String?,
    onNameChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_subject_create)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.subjects_name_hint)) },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.subjects_color), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                ColorPickerRow(colors = SubjectColorPalette, selected = color, onSelect = onColorChange)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.subjects_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.subjects_cancel)) }
        }
    )
}
