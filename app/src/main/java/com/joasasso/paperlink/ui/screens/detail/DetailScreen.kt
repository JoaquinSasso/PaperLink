package com.joasasso.paperlink.ui.screens.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.ui.components.ThumbnailImage
import com.joasasso.paperlink.ui.theme.JetBrainsMono

/**
 * Pantalla de Detalle (DetailScreen).
 * Principio aplicado: La app vincula y redirecciona de forma perfecta al sistema, no reproduce.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    code: String,
    onNavigateBack: () -> Unit,
    viewModel: DetailViewModel = viewModel(factory = DetailViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(code) {
        viewModel.loadLink(code)
    }

    if (uiState.showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.showDeleteConfirmation(false) },
            title = { Text("¿Eliminar código?") },
            text = { Text("Se borrará el código $code y su referencia digital de forma permanente.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteLink()
                        onNavigateBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.showDeleteConfirmation(false) }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Código: $code", fontFamily = JetBrainsMono) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    if (uiState.link != null) {
                        IconButton(onClick = { viewModel.showDeleteConfirmation(true) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator()
            } else if (uiState.isUriInvalid || uiState.link == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.detail_error_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Button(
                        onClick = {
                            viewModel.deleteLink()
                            onNavigateBack()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.detail_delete_btn))
                    }
                }
            } else {
                val link = uiState.link!!
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ThumbnailImage(
                        uri = link.contentUri,
                        type = link.contentType,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = link.code,
                        style = MaterialTheme.typography.displayMedium.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.primary
                    )

                    Text(
                        text = link.displayName ?: "Archivo sin nombre",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    if (!link.note.isNullOrBlank()) {
                        Text(
                            text = link.note,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Lanzamiento del Intent implícito delegando la apertura limpia al sistema operativo de Android
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse(link.contentUri)
                                    // Agregamos flags de lectura obligatorios para que la app destino pueda abrir la URI de SAF
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Si el recurso fue borrado de la galería externa, el catch captura la falla y activa el flujo defensivo
                                viewModel.markUriAsInvalid()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) {
                        Text(stringResource(R.string.detail_open_btn))
                    }
                }
            }
        }
    }
}