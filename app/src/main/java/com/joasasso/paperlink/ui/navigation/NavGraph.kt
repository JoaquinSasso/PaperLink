package com.joasasso.paperlink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.joasasso.paperlink.ui.screens.add.AddLinkScreen
import com.joasasso.paperlink.ui.screens.home.HomeScreen
import com.joasasso.paperlink.ui.screens.onboarding.OnboardingScreen
import com.joasasso.paperlink.ui.screens.txt.TxtViewerScreen
import kotlinx.serialization.Serializable

import com.joasasso.paperlink.ui.screens.home.HomeViewModel

// Rutas tipadas simplificadas: El detalle muere, las materias mueren.
@Serializable object HomeDestination
@Serializable object AddLinkDestination
@Serializable data class TxtViewerDestination(val uri: String, val code: String)
@Serializable object OnboardingDestination

@Composable
fun PaperLinkNavNavHost(
    navController: NavHostController,
    homeViewModel: HomeViewModel, // Recibimos la instancia compartida
    isFirstLaunch: Boolean,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = if (isFirstLaunch) OnboardingDestination else HomeDestination,
        modifier = modifier
    ) {
        composable<OnboardingDestination> {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(HomeDestination) {
                        popUpTo(OnboardingDestination) { inclusive = true }
                    }
                }
            )
        }

        composable<HomeDestination> {
            HomeScreen(
                onNavigateToAdd = { navController.navigate(AddLinkDestination) },
                onNavigateToTxtViewer = { uri, code ->
                    navController.navigate(TxtViewerDestination(uri = uri, code = code))
                },
                onNavigateToOnboarding = { navController.navigate(OnboardingDestination) },
                viewModel = homeViewModel // La inyectamos manualmente
            )
        }

        composable<AddLinkDestination> {
            AddLinkScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<TxtViewerDestination> {
            TxtViewerScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
