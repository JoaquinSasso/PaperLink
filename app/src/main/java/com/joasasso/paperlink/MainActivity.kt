package com.joasasso.paperlink

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.rememberNavController
import com.joasasso.paperlink.ui.navigation.PaperLinkNavNavHost
import com.joasasso.paperlink.ui.screens.home.HomeViewModel
import com.joasasso.paperlink.ui.theme.PaperLinkTheme

class MainActivity : ComponentActivity() {
    
    // ViewModel compartido a nivel de Actividad
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // El handleIntent DEBE ocurrir después de que Compose esté listo o asegurar que el ViewModel 
        // mantenga el estado. Pero el log muestra que se llama bien.
        // handleIntent(intent) // <--- Moveremos esto al final o lo dejaremos si el VM es compartido.

        setContent {
            PaperLinkTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides this
                ) {
                    // Lanzamos el procesamiento del intent dentro del contexto de Compose
                    // para asegurar que el ViewModelStoreOwner esté establecido y el VM sea el compartido.
                    LaunchedEffect(intent) {
                        handleIntent(intent)
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val navController = rememberNavController()
                        // PASO CLAVE: Pasamos la instancia física 'homeViewModel' al NavHost
                        PaperLinkNavNavHost(
                            navController = navController,
                            homeViewModel = homeViewModel
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Al volver, forzamos un chequeo de foto reciente
        // Nota: El init del VM ya observa cambios, pero onResume asegura refresco al volver de la cámara nativa
        homeViewModel.checkRecentPhoto()
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data = intent?.data
        
        if (action == "com.joasasso.paperlink.ACTION_LAUNCH_CAMERA") {
            homeViewModel.onTakePhotoClicked()
            intent.action = null
        } else if (action == Intent.ACTION_SEND) {
            val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            uri?.let { homeViewModel.processIncomingUri(it) }
            intent.action = null
        }
    }
}
