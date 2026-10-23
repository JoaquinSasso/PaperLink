package com.joasasso.paperlink.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.joasasso.paperlink.ui.screens.camera.CameraScreen
import com.joasasso.paperlink.ui.screens.code.CodeScreen
import com.joasasso.paperlink.ui.screens.home.HomeScreen
import com.joasasso.paperlink.ui.screens.onboarding.OnboardingScreen
import com.joasasso.paperlink.ui.screens.share.ShareScreen
import kotlinx.serialization.Serializable

@Serializable object HomeDestination
@Serializable object OnboardingDestination

/** @param appendTo código al que se agregan las fotos, o null para crear uno nuevo. */
@Serializable data class CameraDestination(val appendTo: String? = null)

/**
 * @param justCreated vibra al mostrarlo (solo códigos recién creados).
 * @param finishOnDone al tocar "Listo" se cierra la app (se llegó compartiendo desde otra app).
 */
@Serializable data class CodeDestination(
    val code: String,
    val justCreated: Boolean,
    val finishOnDone: Boolean = false,
)

@Serializable data class ShareDestination(val uris: List<String>, val text: String?)

/**
 * @param launchRoute destino pedido por un intent (cámara o compartir); reemplaza toda la pila.
 */
@Composable
fun PaperLinkNavHost(
    navController: NavHostController,
    isFirstLaunch: Boolean,
    launchRoute: Any?,
    onLaunchRouteHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivity.current

    LaunchedEffect(launchRoute) {
        if (launchRoute == null) return@LaunchedEffect
        navController.navigate(launchRoute) {
            popUpTo(navController.graph.id) { inclusive = true }
        }
        onLaunchRouteHandled()
    }

    fun goHome() = navController.navigate(HomeDestination) {
        popUpTo(navController.graph.id) { inclusive = true }
    }

    NavHost(
        navController = navController,
        startDestination = if (isFirstLaunch) OnboardingDestination else HomeDestination,
        modifier = modifier,
    ) {
        composable<OnboardingDestination> {
            OnboardingScreen(onFinish = ::goHome)
        }

        composable<HomeDestination> {
            HomeScreen(
                onOpenCamera = { navController.navigate(CameraDestination()) },
                onOpenCode = { code, justCreated -> navController.navigate(CodeDestination(code, justCreated)) },
            )
        }

        composable<CameraDestination> { entry ->
            val appendTo = entry.toRoute<CameraDestination>().appendTo
            CameraScreen(
                onFinished = { code ->
                    if (appendTo != null) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(CodeDestination(code, justCreated = true)) {
                            popUpTo<CameraDestination> { inclusive = true }
                        }
                    }
                },
                onClose = { if (!navController.popBackStack()) activity?.finish() },
            )
        }

        composable<CodeDestination> { entry ->
            val route = entry.toRoute<CodeDestination>()
            CodeScreen(
                onDone = { if (route.finishOnDone) activity?.finish() else goHome() },
                onAddWithCamera = { code -> navController.navigate(CameraDestination(appendTo = code)) },
            )
        }

        composable<ShareDestination> {
            ShareScreen(
                onSaved = { code, createdNew ->
                    navController.navigate(CodeDestination(code, justCreated = createdNew, finishOnDone = true)) {
                        popUpTo<ShareDestination> { inclusive = true }
                    }
                },
                onClose = { activity?.finish() },
            )
        }
    }
}
