package com.joasasso.paperlink.ui.screens.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.ui.components.ThumbnailImage
import com.joasasso.paperlink.ui.theme.JetBrainsMono

/**
 * HomeScreen "Visual-First" de Fricción Cero.
 * Todo el sistema se reduce a esta grilla táctil.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAdd: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Diálogo de Confirmación de Borrado
    if (uiState.linkToDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmDelete(null) },
            title = { Text("¿Eliminar vínculo?") },
            text = { Text("Se borrará el código ${uiState.linkToDelete?.code} de forma permanente.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteLink() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmDelete(null) }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Añadir")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // Barra superior compacta para ingreso de código
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { 
                    viewModel.onSearchQueryChanged(it)
                    // PIVOTE: Si el código coincide exactamente con uno existente, se abre solo? 
                    // No, mejor dejar el botón o que el usuario lo vea en la grilla.
                },
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.titleLarge.copy(
                    fontFamily = JetBrainsMono,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                shape = MaterialTheme.shapes.extraLarge
            )

            // Grilla Visual-First
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.links) { link ->
                    VisualLinkCard(
                        link = link,
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                    data = Uri.parse(link.contentUri)
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // En el diseño radical, si falla al abrir, 
                                // el usuario simplemente lo nota al hacer click.
                            }
                        },
                        onLongClick = { viewModel.confirmDelete(link) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VisualLinkCard(
    link: PaperLink,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        ThumbnailImage(
            uri = link.contentUri,
            type = link.contentType,
            code = link.code,
            modifier = Modifier.fillMaxSize()
        )
        
        // El código gigante es el único texto permitido
        Surface(
            color = Color.Black.copy(alpha = 0.3f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.padding(4.dp)
        ) {
            Text(
                text = link.code,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                ),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
