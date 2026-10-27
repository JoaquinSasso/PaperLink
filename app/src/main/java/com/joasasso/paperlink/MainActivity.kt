package com.joasasso.paperlink

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat
import androidx.navigation.compose.rememberNavController
import com.joasasso.paperlink.ui.navigation.CameraDestination
import com.joasasso.paperlink.ui.navigation.PaperLinkNavHost
import com.joasasso.paperlink.ui.navigation.ShareDestination
import com.joasasso.paperlink.ui.theme.PaperLinkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Destino pedido por el intent (widget, atajo o compartir), pendiente de navegar. */
    private var launchRoute by mutableStateOf<Any?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Tras una recreación la pila de navegación ya se restauró: no volver a procesar el intent.
        if (savedInstanceState == null) launchRoute = routeFor(intent)

        val userPreferencesRepository = (application as PaperLinkApp).container.userPreferencesRepository
        // Si la actividad se está recreando (por ejemplo con "No conservar actividades" al volver de
        // un selector de archivos o de Google OAuth), el NavHost debe componerse en el primer cuadro
        // para que su pila y sus ActivityResultLauncher reciban el resultado pendiente.
        val initialFirstLaunch: Boolean? = if (savedInstanceState != null) {
            savedInstanceState.getBoolean(KEY_FIRST_LAUNCH, false)
        } else {
            null
        }

        setContent {
            val isFirstLaunch by userPreferencesRepository.isFirstLaunch.collectAsState(initial = initialFirstLaunch)

            PaperLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val firstLaunch = isFirstLaunch
                    if (firstLaunch != null) {
                        lastKnownFirstLaunch = firstLaunch
                        PaperLinkNavHost(
                            navController = rememberNavController(),
                            isFirstLaunch = firstLaunch,
                            launchRoute = launchRoute,
                            onLaunchRouteHandled = { launchRoute = null },
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    private var lastKnownFirstLaunch: Boolean = false

    override fun onStart() {
        super.onStart()
        if (!isChangingConfigurations) {
            val container = (application as PaperLinkApp).container
            container.applicationScope.launch {
                container.driveBackupManager.onAppOpened()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FIRST_LAUNCH, lastKnownFirstLaunch)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFor(intent)?.let { launchRoute = it }
    }

    private fun routeFor(intent: Intent?): Any? = when (intent?.action) {
        ACTION_CAPTURE -> CameraDestination()
        Intent.ACTION_SEND -> {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            shareRoute(listOfNotNull(uri), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            val uris = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            shareRoute(uris.orEmpty(), intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        }
        else -> null
    }

    private fun shareRoute(uris: List<Uri>, text: String?) =
        ShareDestination(uris = uris.map(Uri::toString), text = text?.takeIf { it.isNotBlank() })

    companion object {
        /** Abre la cámara directamente (widget y atajo del ícono). */
        const val ACTION_CAPTURE = "com.joasasso.paperlink.action.CAPTURE"
        private const val KEY_FIRST_LAUNCH = "is_first_launch"
    }
}
