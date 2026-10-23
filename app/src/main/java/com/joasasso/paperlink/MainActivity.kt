package com.joasasso.paperlink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.joasasso.paperlink.ui.navigation.PaperLinkNavHost
import com.joasasso.paperlink.ui.theme.PaperLinkTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val userPreferencesRepository = (application as PaperLinkApp).container.userPreferencesRepository

        setContent {
            val isFirstLaunch by userPreferencesRepository.isFirstLaunch.collectAsState(initial = null)

            PaperLinkTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val firstLaunch = isFirstLaunch
                    if (firstLaunch != null) {
                        PaperLinkNavHost(
                            navController = rememberNavController(),
                            isFirstLaunch = firstLaunch,
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
}
