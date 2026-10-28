package com.joasasso.paperlink.ui.navigation

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.joasasso.paperlink.ui.screens.backup.BackupScreen
import com.joasasso.paperlink.ui.screens.camera.CameraScreen
import com.joasasso.paperlink.ui.screens.code.CodeScreen
import com.joasasso.paperlink.ui.screens.home.HomeScreen
import com.joasasso.paperlink.ui.screens.note.NoteScreen
import com.joasasso.paperlink.ui.screens.onboarding.OnboardingScreen
import com.joasasso.paperlink.ui.screens.share.ShareScreen
import com.joasasso.paperlink.ui.screens.viewer.ViewerScreen
import kotlinx.serialization.Serializable

@Serializable object HomeDestination
@Serializable object OnboardingDestination
@Serializable object BackupDestination

/** @param appendTo código al que se agregan las fotos, o null para crear uno nuevo. */
@Serializable data class CameraDestination(val appendTo: String? = null)

/**
 * Pantalla de un código: se abre al ingresarlo en la Home y al crearlo.
 * @param justCreated vibra al mostrarlo y recuerda anotarlo (solo códigos recién creados).
 * @param finishOnDone al salir se cierra la app (se llegó compartiendo desde otra app).
 */
@Serializable data class CodeDestination(
    val code: String,
    val justCreated: Boolean,
    val finishOnDone: Boolean = false,
)

@Serializable data class ShareDestination(val uris: List<String>, val text: String?)

/** Visor de fotos. @param startId foto con la que abre; null = la primera. */
@Serializable data class ViewerDestination(val code: String, val startId: String? = null)

@Serializable data class NoteDestination(val code: String, val attachmentId: String)

/** Clave del código recién borrado, que la Home recibe para ofrecer "Deshacer". */
private const val KEY_DELETED_CODE = "deleted_code"

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

        composable<HomeDestination> { entry ->
            val deletedCode by entry.savedStateHandle.getStateFlow<String?>(KEY_DELETED_CODE, null)
                .collectAsStateWithLifecycle()
            HomeScreen(
                onOpenCode = { code -> navController.navigate(CodeDestination(code, justCreated = false)) },
                onCodeCreated = { code -> navController.navigate(CodeDestination(code, justCreated = true)) },
                onOpenCamera = { navController.navigate(CameraDestination()) },
                onOpenBackup = { navController.navigate(BackupDestination) },
                deletedCode = deletedCode,
                onDeletedCodeHandled = { code ->
                    if (entry.savedStateHandle.get<String>(KEY_DELETED_CODE) == code) {
                        entry.savedStateHandle[KEY_DELETED_CODE] = null
                    }
                },
            )
        }

        composable<BackupDestination> {
            BackupScreen(
                onBack = { if (!navController.popBackStack()) goHome() },
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
                showDone = route.justCreated || route.finishOnDone,
                onBack = {
                    when {
                        route.finishOnDone -> activity?.finish()
                        !navController.popBackStack() -> goHome()
                    }
                },
                onOpenPhoto = { code, id -> navController.navigate(ViewerDestination(code, startId = id)) },
                onOpenNote = { code, id -> navController.navigate(NoteDestination(code, id)) },
                onAddWithCamera = { code -> navController.navigate(CameraDestination(appendTo = code)) },
                onCodeDeleted = { code ->
                    if (navController.popBackStack<HomeDestination>(inclusive = false)) {
                        navController.currentBackStackEntry?.savedStateHandle?.set(KEY_DELETED_CODE, code)
                    } else {
                        goHome()
                    }
                },
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

        composable<ViewerDestination> {
            ViewerScreen(onBack = { if (!navController.popBackStack()) goHome() })
        }

        composable<NoteDestination> {
            NoteScreen(onBack = { if (!navController.popBackStack()) goHome() })
        }
    }
}
