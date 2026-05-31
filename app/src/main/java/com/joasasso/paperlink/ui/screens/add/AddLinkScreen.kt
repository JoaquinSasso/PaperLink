package com.joasasso.paperlink.ui.screens.add

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.joasasso.paperlink.ui.components.ContentTypeIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkScreen(
    onNavigateBack: () -> Unit,
    viewModel: AddLinkViewModel = viewModel(factory = AddLinkViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()

    // SAF (Storage Access Framework)
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onLocalResourceSelected(it) }
    }

    // Al generar el código, volvemos atrás (al Home)
    LaunchedEffect(uiState.generatedCode) {
        if (uiState.generatedCode != null) {
            onNavigateBack()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
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

            // FASE 1: Selección del Tipo
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
                        .heightIn(max = 600.dp)
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
            // FASE 2: Entrada de Datos
            else {
                Text(
                    text = "Tipo: ${uiState.selectedType?.name}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )

                when (uiState.selectedType) {
                    ContentType.WEB_LINK -> {
                        OutlinedTextField(
                            value = uiState.contentUri,
                            onValueChange = { viewModel.onWebUrlEntered(it) },
                            label = { Text("URL (https://...)") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(ContentTypeIcons.getIcon(ContentType.WEB_LINK), null) }
                        )
                    }
                    ContentType.TEXT_NOTE -> {
                        OutlinedTextField(
                            value = uiState.nativeNoteContent,
                            onValueChange = { viewModel.onNativeNoteContentChanged(it) },
                            label = { Text("Escribe tu nota aquí...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                            maxLines = 10
                        )
                    }
                    else -> {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (uiState.contentUri.isNotBlank()) "Archivo seleccionado" else "Ningún archivo",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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
            .aspectRatio(1f),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
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
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = type.name.replace("_", " "),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 1
            )
        }
    }
}
