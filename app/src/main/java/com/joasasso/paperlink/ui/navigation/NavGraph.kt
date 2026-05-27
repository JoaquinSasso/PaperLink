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
import kotlinx.serialization.Serializable

// Definición de rutas seguras tipo-fuertes (Type-Safe Navigation)
@Serializable object HomeDestination
@Serializable object AddLinkDestination
@Serializable data class DetailDestination(val code: String)

/**
 * Grafo de navegación central de PaperLink.
 * Conecta HomeScreen, AddLinkScreen y DetailScreen sin strings duros en las rutas.
 */
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
        // Pantalla Principal: Búsqueda y Recientes
        composable<HomeDestination> {
            HomeScreen(
                onNavigateToAdd = { navController.navigate(AddLinkDestination) },
                onNavigateToDetail = { code -> navController.navigate(DetailDestination(code)) }
            )
        }

        // Pantalla de Creación: Selección de Multimedia y Generación de Código
        composable<AddLinkDestination> {
            AddLinkScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { code ->
                    // Hacemos pop y vamos al detalle para que el flujo de retorno no vuelva al formulario
                    navController.navigate(DetailDestination(code)) {
                        popUpTo(HomeDestination) { inclusive = false }
                    }
                }
            )
        }

        // Pantalla de Detalle: Visualización, Redirección y Borrado
        composable<DetailDestination> { backStackEntry ->
            val destination: DetailDestination = backStackEntry.toRoute()
            DetailScreen(
                code = destination.code,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}