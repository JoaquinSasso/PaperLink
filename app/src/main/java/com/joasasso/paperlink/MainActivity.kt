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
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.joasasso.paperlink.ui.navigation.PaperLinkNavNavHost
import com.joasasso.paperlink.ui.screens.home.HomeViewModel
import com.joasasso.paperlink.ui.theme.PaperLinkTheme

class MainActivity : ComponentActivity() {
    
    // Necesitamos el ViewModel a nivel de Actividad para procesar los Intents
    private val homeViewModel: HomeViewModel by viewModels { HomeViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            PaperLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    PaperLinkNavNavHost(navController = navController)
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
        // Forzamos un chequeo de foto reciente cada vez que la app vuelve al frente
        homeViewModel.checkRecentPhoto()
    }

    private fun handleIntent(intent: Intent?) {
        Log.d("PaperLinkDebug", "Handling Intent: action=${intent?.action}")
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                Log.d("PaperLinkDebug", "Share Intent received with URI: $uri")
                uri?.let { homeViewModel.processIncomingUri(it) }
            }
            "com.joasasso.paperlink.ACTION_LAUNCH_CAMERA" -> {
                Log.d("PaperLinkDebug", "Widget Camera action received")
                homeViewModel.onTakePhotoClicked()
            }
        }
    }
}
