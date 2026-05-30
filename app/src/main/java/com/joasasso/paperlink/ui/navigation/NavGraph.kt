package com.joasasso.paperlink.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.joasasso.paperlink.ui.screens.add.AddLinkScreen
import com.joasasso.paperlink.ui.screens.detail.DetailScreen
import com.joasasso.paperlink.ui.screens.home.HomeScreen
import com.joasasso.paperlink.ui.screens.subjects.SubjectsScreen
import kotlinx.serialization.Serializable

// Rutas tipadas (Type-Safe Navigation)
@Serializable object HomeDestination
@Serializable object AddLinkDestination
@Serializable object SubjectsDestination
@Serializable data class DetailDestination(val code: String)

@Composable
fun PaperLinkNavNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = HomeDestination,
        modifier = modifier
    ) {
        composable<HomeDestination> {
            HomeScreen(
                onNavigateToAdd = { navController.navigate(AddLinkDestination) },
                onNavigateToDetail = { code -> navController.navigate(DetailDestination(code)) },
                onNavigateToSubjects = { navController.navigate(SubjectsDestination) }
            )
        }

        composable<AddLinkDestination> {
            AddLinkScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { code ->
                    navController.navigate(DetailDestination(code)) {
                        popUpTo(HomeDestination) { inclusive = false }
                    }
                }
            )
        }

        composable<SubjectsDestination> {
            SubjectsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable<DetailDestination> { backStackEntry ->
            val destination: DetailDestination = backStackEntry.toRoute()
            DetailScreen(
                code = destination.code,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
