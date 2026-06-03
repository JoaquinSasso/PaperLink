package com.joasasso.paperlink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.joasasso.paperlink.ui.screens.add.AddLinkScreen
import com.joasasso.paperlink.ui.screens.home.HomeScreen
import com.joasasso.paperlink.ui.screens.onboarding.OnboardingScreen
import kotlinx.serialization.Serializable

// Rutas tipadas simplificadas: El detalle muere, las materias mueren.
@Serializable object HomeDestination
@Serializable object AddLinkDestination
@Serializable object OnboardingDestination

@Composable
fun PaperLinkNavNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = OnboardingDestination,
        modifier = modifier
    ) {
        composable<OnboardingDestination> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(HomeDestination) {
                        popUpTo(OnboardingDestination) { inclusive = true }
                    }
                }
            )
        }

        composable<HomeDestination> {
            HomeScreen(
                onNavigateToAdd = { navController.navigate(AddLinkDestination) }
            )
        }

        composable<AddLinkDestination> {
            AddLinkScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
