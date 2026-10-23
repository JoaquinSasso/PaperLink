package com.joasasso.paperlink.ui.screens.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Cámara propia: varias fotos seguidas, cada una guardada al sacarla.
 *
 * @param onFinished se llama con el código cuando todas las fotos están guardadas.
 * @param onClose salir sin fotos.
 */
@Composable
fun CameraScreen(
    onFinished: (code: String) -> Unit,
    onClose: () -> Unit,
    viewModel: CameraViewModel = viewModel(factory = CameraViewModel.Factory),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) {
        if (!granted && !asked) permissionLauncher.launch(Manifest.permission.CAMERA)
    }
    // Al volver de Ajustes el permiso puede haber cambiado.
    LifecycleResumeEffect(Unit) {
        granted = context.hasCameraPermission()
        onPauseOrDispose {}
    }

    val hasPhotos = state.photoCount > 0 || state.savingCount > 0
    BackHandler(enabled = hasPhotos) { viewModel.onDone() }

    LaunchedEffect(state.canFinish) {
        if (!state.canFinish) return@LaunchedEffect
        val code = state.code
        if (code != null && state.photoCount > 0) onFinished(code) else onClose()
    }

    val message = state.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraContent(
                photoCount = state.photoCount,
                saving = state.savingCount > 0,
                waitingToFinish = state.doneRequested,
                onCapture = { file -> viewModel.onPhotoSaved(file) },
                onCaptureError = viewModel::onCaptureFailed,
                newCaptureFile = viewModel::newCaptureFile,
                onDone = viewModel::onDone,
                onCameraError = { snackbar.showSnackbar(context.getString(R.string.camera_error_unavailable)) },
            )
        } else if (asked) {
            PermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = { context.openAppSettings() },
            )
        }

        IconButton(
            onClick = { if (hasPhotos) viewModel.onDone() else onClose() },
            modifier = Modifier.statusBarsPadding().padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.camera_close_cd), tint = Color.White)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    }
}

@Composable
private fun CameraContent(
    photoCount: Int,
    saving: Boolean,
    waitingToFinish: Boolean,
    onCapture: (File) -> Unit,
    onCaptureError: (File, Throwable) -> Unit,
    newCaptureFile: suspend () -> File,
    onDone: () -> Unit,
    onCameraError: suspend () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    val flash = remember { Animatable(0f) }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider { surfaceRequest = it } }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: IllegalArgumentException) {
            log("No back camera", e)
            onCameraError()
            return@LaunchedEffect
        } catch (e: IllegalStateException) {
            log("Camera bind failed", e)
            onCameraError()
            return@LaunchedEffect
        }
        try {
            awaitCancellation()
        } finally {
            provider.unbindAll()
        }
    }

    Box(Modifier.fillMaxSize()) {
        surfaceRequest?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
        Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = pluralStringResource(R.plurals.camera_photo_count, photoCount, photoCount),
                color = Color.White,
                modifier = Modifier.width(96.dp),
            )
            val shutterDescription = stringResource(R.string.camera_shutter_cd)
            Surface(
                onClick = {
                    scope.launch {
                        val file = try {
                            newCaptureFile()
                        } catch (e: IOException) {
                            log("Cannot create capture file", e)
                            onCameraError()
                            return@launch
                        }
                        launch {
                            flash.snapTo(0.7f)
                            flash.animateTo(0f)
                        }
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onCapture(file)
                                override fun onError(exception: ImageCaptureException) = onCaptureError(file, exception)
                            },
                        )
                    }
                },
                enabled = !waitingToFinish,
                shape = CircleShape,
                color = Color.White,
                modifier = Modifier
                    .size(76.dp)
                    .border(4.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .semantics {
                        contentDescription = shutterDescription
                        role = Role.Button
                    },
            ) {}
            Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterEnd) {
                if (waitingToFinish || (saving && photoCount == 0)) {
                    CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                } else {
                    Button(onClick = onDone, enabled = photoCount > 0 || saving) {
                        Text(stringResource(R.string.camera_done))
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRationale(onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.camera_permission_needed),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest) { Text(stringResource(R.string.camera_permission_allow)) }
        OutlinedButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.camera_permission_settings), color = Color.White)
        }
    }
}

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
