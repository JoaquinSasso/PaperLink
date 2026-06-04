package com.joasasso.paperlink.ui.screens.txt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import com.joasasso.paperlink.PaperLinkApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtViewerScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as PaperLinkApp
    
    // Obtenemos el SavedStateHandle de la entrada de navegación actual
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    val savedStateHandle = (viewModelStoreOwner as? NavBackStackEntry)?.savedStateHandle
        ?: throw IllegalStateException("TxtViewerScreen debe estar dentro de un NavHost")

    val viewModel: TxtViewerViewModel = viewModel(
        factory = TxtViewerViewModel.provideFactory(application, savedStateHandle)
    )
    
    val uiState by viewModel.uiState.collectAsState()

    // Manejo de retroceso para guardar antes de salir
    BackHandler {
        viewModel.saveChanges(onNavigateBack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.code) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveChanges(onNavigateBack) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver y Guardar"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveChanges({}) }) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "Guardar"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.errorMessage != null -> {
                    AlertDialog(
                        onDismissRequest = { /* No permitir cerrar hasta resolver o forzar salida */ },
                        title = { Text("Error") },
                        text = { Text(uiState.errorMessage!!) },
                        confirmButton = {
                            TextButton(onClick = onNavigateBack) {
                                Text("Salir sin guardar")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.saveChanges(onNavigateBack) }) {
                                Text("Reintentar")
                            }
                        }
                    )
                }
                else -> {
                    TextField(
                        value = uiState.text,
                        onValueChange = { viewModel.onTextChanged(it) },
                        modifier = Modifier.fillMaxSize(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
