package com.joasasso.paperlink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.joasasso.paperlink.ui.screens.home.HomeScreen
import com.joasasso.paperlink.ui.screens.onboarding.OnboardingScreen
import kotlinx.serialization.Serializable

@Serializable object HomeDestination
@Serializable object OnboardingDestination

@Composable
fun PaperLinkNavHost(
    navController: NavHostController,
    isFirstLaunch: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = if (isFirstLaunch) OnboardingDestination else HomeDestination,
        modifier = modifier,
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
            HomeScreen()
        }
    }
}
