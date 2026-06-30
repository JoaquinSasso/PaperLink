package com.joasasso.paperlink

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
        
        val userPreferencesRepository = (application as PaperLinkApp).container.userPreferencesRepository

        setContent {
            val isFirstLaunch by userPreferencesRepository.isFirstLaunch.collectAsState(initial = null)

            PaperLinkTheme {
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides this
                ) {
                    LaunchedEffect(intent) {
                        handleIntent(intent)
                    }

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if (isFirstLaunch != null) {
                            val navController = rememberNavController()
                            PaperLinkNavNavHost(
                                navController = navController,
                                homeViewModel = homeViewModel,
                                isFirstLaunch = isFirstLaunch!!
                            )
                        } else {
                            // Carga inicial o splash
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
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
