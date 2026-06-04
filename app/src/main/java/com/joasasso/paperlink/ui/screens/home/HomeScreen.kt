package com.joasasso.paperlink.ui.screens.home

import android.content.Intent
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.ui.theme.JetBrainsMono
import com.joasasso.paperlink.ui.components.ThumbnailImage

/**
 * HomeScreen "Visual-First" de Fricción Cero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAdd: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Lógica robusta para abrir archivo
    val openFile: (String) -> Unit = { code ->
        viewModel.findLinkByCode(code)?.let { link ->
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    data = link.contentUri.toUri()
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                // Fallback silencioso
            }
        }
    }

    // Disparador reactivo: En cuanto el código llega a 4, intentamos abrir
    LaunchedEffect(uiState.searchQuery) {
        if (uiState.searchQuery.length == 4) {
            openFile(uiState.searchQuery)
        }
    }

    // Diálogo de Confirmación de Borrado
    if (uiState.linkToDelete != null) {
        AlertDialog(
            onDismissRequest = { viewModel.confirmDelete(null) },
            title = { Text("¿Eliminar vínculo?") },
            text = { Text("Se borrará el código ${uiState.linkToDelete?.code} de forma permanente.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteLink() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Eliminar")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmDelete(null) }) {
                    Text("Cancelar")
                }
            },
        )
    }

    Scaffold(
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Añadir",
                    modifier = Modifier.size(36.dp)
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp)
        ) {
            // PIVOTE: Campo de código interactivo y grande
            CodeInputField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                onEnter = { openFile(uiState.searchQuery) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp)
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
                                    data = link.contentUri.toUri()
                                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                // Falla silenciosa
                            }
                        },
                        onLongClick = { viewModel.confirmDelete(link) },
                    )
                }
            }
        }
    }
}

@Composable
fun CodeInputField(
    value: String,
    onValueChange: (String) -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fontSize = 48.sp
    val boxWidth = 56.dp
    val boxHeight = 80.dp
    val gap = 12.dp

    // Animación de parpadeo para el cursor manual
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes { durationMillis = 1000; 0.5f at 500 },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorAlpha"
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // TextField invisible: CAPTURA TODA LA ENTRADA
        // Usamos un solo campo para evitar bugs de foco y lag del cursor nativo
        BasicTextField(
            value = value,
            onValueChange = {
                if (it.length <= 4) onValueChange(it.uppercase().trim())
            },
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.01f), // Casi invisible pero recibe clicks y foco
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onEnter() }),
            textStyle = TextStyle(fontSize = fontSize) // Para que el teclado sepa el tamaño
        )

        // Capa Visual: Renderizamos las cajas y el cursor manual
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(4) { index ->
                val char = value.getOrNull(index)
                val isFilled = char != null
                val isNextSlot = index == value.length

                Box(
                    modifier = Modifier
                        .width(boxWidth)
                        .height(boxHeight),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier.weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            // Texto o Placeholder (X)
                            Text(
                                text = char?.toString() ?: "X",
                                style = TextStyle(
                                    fontFamily = JetBrainsMono,
                                    fontSize = fontSize,
                                    fontWeight = FontWeight.Black,
                                    color = if (isFilled)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                                ),
                                textAlign = TextAlign.Center
                            )

                            // EL CURSOR FAKE: Siempre indica dónde irá el siguiente carácter
                            if (isNextSlot) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .width(3.dp)
                                        .height(40.dp) // Altura visual para 48sp
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha))
                                )
                            }
                        }

                        // Subrayado dinámico
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(
                                    color = if (isFilled || isNextSlot)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
                                    shape = MaterialTheme.shapes.extraSmall
                                )
                        )
                    }
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
            .clip(RoundedCornerShape(12.dp))
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

        // Overlay oscuro total
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
        )

        // El código ahora se posiciona en la parte inferior para no tapar el centro visual
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Text(
                text = link.code,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                ),
                color = Color.White
            )
        }
    }
}
