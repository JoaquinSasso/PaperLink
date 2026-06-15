package com.joasasso.paperlink.ui.screens.home

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.ui.components.ThumbnailImage
import com.joasasso.paperlink.ui.theme.JetBrainsMono
import kotlinx.coroutines.flow.collectLatest

/**
 * HomeScreen "Visual-First" de Fricción Cero.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAdd: () -> Unit,
    onNavigateToTxtViewer: (String, String) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Launcher para Permisos de Galería (Banner)
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.checkRecentPhoto()
        }
    }

    LaunchedEffect(Unit) {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            storagePermissionLauncher.launch(permission)
        }
    }

    // Launcher para Cámara
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && tempCameraUri != null) {
            viewModel.processIncomingUri(tempCameraUri!!, ContentType.IMAGE)
        }
    }

    // Launcher para Permisos (Cámara)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("PaperLinkDebug", "Camera permission GRANTED from launcher.")
            val uri = viewModel.getTempCameraUri()
            tempCameraUri = uri
            cameraLauncher.launch(uri)
            viewModel.onCameraLaunched()
        } else {
            Log.d("PaperLinkDebug", "Camera permission DENIED from launcher.")
            Toast.makeText(context, context.getString(R.string.home_camera_permission_denied), Toast.LENGTH_SHORT).show()
            viewModel.onCameraLaunched()
        }
    }

    // Reactivo a estado del ViewModel para lanzar cámara
    LaunchedEffect(uiState.shouldLaunchCamera) {
        Log.d("PaperLinkDebug", "[STEP 6] HomeScreen LaunchedEffect detected shouldLaunchCamera = ${uiState.shouldLaunchCamera}")
        if (uiState.shouldLaunchCamera) {
            val permissionCheck = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            )
            Log.d("PaperLinkDebug", "[STEP 7] Camera permission status: $permissionCheck")
            
            if (permissionCheck == PackageManager.PERMISSION_GRANTED) {
                Log.d("PaperLinkDebug", "[STEP 8] Permission granted. Getting URI and launching camera.")
                val uri = viewModel.getTempCameraUri()
                tempCameraUri = uri
                Log.d("PaperLinkDebug", "[STEP 9] Launching camera with URI: $uri")
                cameraLauncher.launch(uri)
                viewModel.onCameraLaunched()
            } else {
                Log.d("PaperLinkDebug", "[STEP 8B] Permission NOT granted. Requesting...")
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    // Escuchar eventos de error del ViewModel
    LaunchedEffect(viewModel.events) {
        viewModel.events.collectLatest { event ->
            Log.d("PaperLinkDebug", "Event received in HomeScreen: $event")
            when (event) {
                is HomeEvent.Error -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Lógica robusta para abrir archivo
    val openFile: (String) -> Unit = { code ->
        Log.d("PaperLinkDebug", "Intentando abrir código: $code")
        viewModel.findLinkByCode(code)?.let { link ->
            val uri = link.contentUri.toUri()
            val mimeType = context.contentResolver.getType(uri)
            
            val isTxtOrMd = link.contentUri.endsWith(".txt", ignoreCase = true) ||
                    link.contentUri.endsWith(".md", ignoreCase = true) ||
                    link.contentType == com.joasasso.paperlink.data.local.ContentType.TEXT_NOTE ||
                    mimeType == "text/plain" ||
                    mimeType == "text/markdown" ||
                    mimeType == "application/octet-stream" && (link.contentUri.contains(".md") || link.contentUri.contains(".txt"))

            Log.d("PaperLinkDebug", "Link: ${link.code}, MimeType: $mimeType, IsTxtOrMd: $isTxtOrMd")

            if (isTxtOrMd) {
                Log.d("PaperLinkDebug", "Navegando a TxtViewer interno para: ${link.code}")
                onNavigateToTxtViewer(link.contentUri, link.code)
            } else {
                try {
                    Log.d("PaperLinkDebug", "Lanzando Intent externo para: ${link.contentUri}")
                    
                    // Mejoramos la determinación del MIME type para el Intent
                    val intentMimeType = when(link.contentType) {
                        com.joasasso.paperlink.data.local.ContentType.IMAGE -> "image/*"
                        com.joasasso.paperlink.data.local.ContentType.VIDEO -> "video/*"
                        com.joasasso.paperlink.data.local.ContentType.AUDIO -> "audio/*"
                        com.joasasso.paperlink.data.local.ContentType.PDF -> "application/pdf"
                        else -> mimeType ?: "*/*"
                    }

                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, intentMimeType)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("PaperLinkDebug", "Error al abrir intent externo: ${e.localizedMessage}")
                }
            }
        } ?: Log.w("PaperLinkDebug", "No se encontró ningún link con el código: $code")
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
            title = { Text(stringResource(R.string.home_delete_confirm_title)) },
            text = { Text(stringResource(R.string.home_delete_confirm_text, uiState.linkToDelete?.code ?: "")) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteLink() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.home_delete_confirm_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.confirmDelete(null) }) {
                    Text(stringResource(R.string.home_cancel_btn))
                }
            },
        )
    }

    Scaffold(
        floatingActionButton = {
            // Canal 1: Menú Flotante Inferior (Estilo Dock)
            Surface(
                modifier = Modifier
                    .padding(bottom = 16.dp)
                    .height(64.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp,
                shadowElevation = 12.dp
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .fillMaxHeight(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Botón 1: Enlace / Archivo
                    Button(
                        onClick = onNavigateToAdd,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxHeight(0.8f)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_add_btn))
                    }

                    VerticalDivider(
                        modifier = Modifier
                            .fillMaxHeight(0.5f)
                            .width(1.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // Botón 2: Tomar Foto
                    Button(
                        onClick = { viewModel.onTakePhotoClicked() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        modifier = Modifier.fillMaxHeight(0.8f)
                    ) {
                        Icon(Icons.Default.CameraAlt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_take_photo_btn))
                    }
                }
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
            // Canal 2: Banner Inteligente de Foto Reciente (Deslizable)
            if (uiState.recentPhotoUri != null) {
                DismissiblePhotoBanner(
                    uri = uiState.recentPhotoUri!!,
                    onSelect = { viewModel.processIncomingUri(it, ContentType.IMAGE) },
                    onDismiss = { viewModel.onDismissRecentPhoto() },
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            // PIVOTE: Campo de código interactivo y grande
            CodeInputField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                onEnter = { openFile(uiState.searchQuery) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp)
            )

            if (uiState.isSaving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp))
            }

            // Grilla Visual-First
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 100.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(uiState.links) { link ->
                    VisualLinkCard(
                        link = link,
                        onClick = { openFile(link.code) },
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
        BasicTextField(
            value = value,
            onValueChange = {
                if (it.length <= 4) onValueChange(it.uppercase().trim())
            },
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.01f),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onEnter() }),
            textStyle = TextStyle(fontSize = fontSize)
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

                            if (isNextSlot) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .width(3.dp)
                                        .height(40.dp)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha))
                                )
                            }
                        }

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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
        )

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DismissiblePhotoBanner(
    uri: Uri,
    onSelect: (Uri) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd || value == SwipeToDismissBoxValue.EndToStart) {
                onDismiss()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = { /* Fondo vacío o sutil indicador de borrado */ },
        modifier = modifier
    ) {
        SmartPhotoBanner(
            uri = uri,
            onClick = { onSelect(uri) },
            onDismiss = onDismiss
        )
    }
}

@Composable
fun SmartPhotoBanner(
    uri: Uri,
    onClick: (Uri) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(uri) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_recent_photo_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = stringResource(R.string.home_recent_photo_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.home_dismiss_cd),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
        }
    }
}
