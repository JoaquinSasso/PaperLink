package com.joasasso.paperlink.ui.screens.add

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.ui.components.ColorPickerRow
import com.joasasso.paperlink.ui.components.ContentTypeIcons
import com.joasasso.paperlink.ui.theme.SubjectColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: AddLinkViewModel = viewModel(factory = AddLinkViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()

    // SAF (Storage Access Framework) garantiza el acceso persistente a archivos fuera de la app
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onLocalResourceSelected(it) }
    }

    // Efecto reactivo: si se genera un código, navegamos al detalle automáticamente
    LaunchedEffect(uiState.generatedCode) {
        uiState.generatedCode?.let { onNavigateToDetail(it) }
    }

    // Efecto reactivo para mostrar errores (ej: nombre de materia duplicada)
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            // Un Snackbar sería lo ideal en producción, para el MVP usamos un log o alert nativo,
            // pero como no tenemos SnackbarHost, lo reseteamos para no trabar el flujo.
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
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

            // FASE 1: Selección del Tipo de Contenido
            if (uiState.selectedType == null) {
                Text(
                    text = stringResource(R.string.add_select_content_type),
                    style = MaterialTheme.typography.titleMedium
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 600.dp) // Limitamos la altura para que no choque con el scroll exterior si hay mucho contenido
                ) {
                    items(ContentType.entries) { type ->
                        ContentTypeCard(
                            type = type,
                            onClick = {
                                viewModel.onTypeSelected(type)
                                if (type != ContentType.WEB_LINK && type != ContentType.TEXT_NOTE) {
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
                        )
                    }
                }
            }
            // FASE 2: Formulario de Enriquecimiento
            else {
                Text(
                    text = "Tipo seleccionado: ${uiState.selectedType?.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )

                // 2.A: Carga del recurso principal (Depende del tipo)
                when (uiState.selectedType) {
                    ContentType.WEB_LINK -> {
                        // TODO [Joaquín]: Aquí se podría disparar el scraping reactivo
                        // al detectar un cambio de URL válido, guardando el favicon en el estado
                        // para que ThumbnailImage lo muestre automáticamente.
                        OutlinedTextField(
                            value = uiState.contentUri,
                            onValueChange = { viewModel.onWebUrlEntered(it) },
                            label = { Text("Pegar enlace URL (https://...)") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(ContentTypeIcons.getIcon(ContentType.WEB_LINK), null) }
                        )
                    }
                    ContentType.TEXT_NOTE -> { // [NUEVO]
                        OutlinedTextField(
                            value = uiState.nativeNoteContent,
                            onValueChange = { viewModel.onNativeNoteContentChanged(it) },
                            label = { Text("Escribe tu apunte aquí...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp), // Lo hacemos grande para que sea cómodo escribir
                            maxLines = 10
                        )
                    }
                    else -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            PaddingValues(16.dp)
                            Text(
                                text = uiState.displayName.ifBlank { "Ningún archivo seleccionado" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 2.B: Selección de Materia (Etiquetas)
                Text("Materia (Opcional)", style = MaterialTheme.typography.labelLarge)
                var expanded by remember { mutableStateOf(false) }

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = uiState.subjects.find { it.id == uiState.selectedSubjectId }?.name ?: "Sin materia",
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Sin materia") },
                            onClick = {
                                viewModel.selectSubject(null)
                                expanded = false
                            }
                        )
                        uiState.subjects.forEach { subject ->
                            DropdownMenuItem(
                                text = { Text(subject.name) },
                                onClick = {
                                    viewModel.selectSubject(subject.id)
                                    expanded = false
                                }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Crear nueva materia...", color = MaterialTheme.colorScheme.primary) },
                            onClick = {
                                expanded = false
                                viewModel.showCreateSubjectDialog(show = true)
                            }
                        )
                    }
                }

                // 2.C: Nota corta opcional
                OutlinedTextField(
                    value = uiState.note,
                    onValueChange = { viewModel.onNoteChanged(it) },
                    label = { Text(stringResource(R.string.add_note_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 2.D: Botón de Acción Final
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    Button(
                        onClick = { viewModel.saveLink() },
                        enabled = if (uiState.selectedType == ContentType.TEXT_NOTE) {
                            uiState.nativeNoteContent.isNotBlank()
                        } else {
                            uiState.contentUri.isNotBlank()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.add_generate_btn))
                    }
                }
            }
        }
    }

    // Diálogo Al Vuelo para Crear Materias (Contexto)
    if (uiState.isCreatingSubject) {
        AlertDialog(
            onDismissRequest = { viewModel.showCreateSubjectDialog(show = false) },
            title = { Text("Nueva Materia") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    OutlinedTextField(
                        value = uiState.newSubjectName,
                        onValueChange = viewModel::onNewSubjectNameChanged,
                        label = { Text("Nombre") },
                        singleLine = true,
                        isError = uiState.errorMessage != null
                    )
                    if (uiState.errorMessage != null) {
                        Text(uiState.errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Color", style = MaterialTheme.typography.labelMedium)
                    ColorPickerRow(
                        colors = SubjectColors.palette,
                        selected = uiState.newSubjectColorHex,
                        onSelect = viewModel::onNewSubjectColorChanged
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.createSubject() }) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showCreateSubjectDialog(false) }) { Text("Cancelar") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentTypeCard(
    type: ContentType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f) // Hacemos que sean cuadradas
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = ContentTypeIcons.getIcon(type),
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .weight(1f),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = type.name.replace("_", " "), // Limpiamos un poco el nombre del enum
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}
