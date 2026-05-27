package com.joasasso.paperlink.ui.screens.add

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.ContentType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddLinkScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    viewModel: AddLinkViewModel = viewModel(factory = AddLinkViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Launcher para Storage Access Framework (SAF) garantizando la robustez de permisos persistentes
    val safLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.onLocalResourceSelected(context, it) }
    }

    // Monitoreo del éxito del guardado para saltar directamente al detalle final
    LaunchedEffect(uiState.generatedCode) {
        uiState.generatedCode?.let { onNavigateToDetail(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
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
                // Ley 1 y 2: Selección intuitiva de tipo libre de ruido visual
                Text(
                    text = stringResource(R.string.add_select_content_type),
                    style = MaterialTheme.typography.titleMedium
                )

                ContentType.values().forEach { type ->
                    Button(
                        onClick = {
                            viewModel.onTypeSelected(type)
                            if (type != ContentType.WEB_LINK) {
                                // Mime type dinámico según la estructura de datos extendida de la app
                                val mimeType = when(type) {
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
                // Formulario secundario dinámico
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
                        PaddingValues(16.dp)
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
}