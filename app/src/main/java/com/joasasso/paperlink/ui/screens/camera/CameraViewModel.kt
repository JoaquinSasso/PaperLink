package com.joasasso.paperlink.ui.screens.camera

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.ui.common.messageRes
import com.joasasso.paperlink.ui.navigation.CameraDestination
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Sesión de captura de varias fotos.
 *
 * Cada foto se guarda en la base apenas la cámara la escribe: la primera crea el código y las
 * siguientes se le agregan, de a una. El estado de la sesión (código y fotos escritas que aún no
 * llegaron a la base) vive en [SavedStateHandle]: si el proceso muere, al volver se retoma
 * la sesión y se importan las capturas pendientes.
 *
 * El guardado corre en [externalScope] para que salir de la pantalla no lo cancele.
 */
class CameraViewModel(
    private val handle: SavedStateHandle,
    private val appendTo: String?,
    private val repository: LinkRepository,
    private val store: AttachmentStore,
    private val externalScope: CoroutineScope,
) : ViewModel() {

    data class UiState(
        /** Código de la sesión; null hasta que se guarda la primera foto. */
        val code: String? = null,
        val photoCount: Int = 0,
        /** Fotos escritas por la cámara que todavía se están guardando. */
        val savingCount: Int = 0,
        /** El usuario tocó "Listo"; se navega cuando [savingCount] llegue a 0. */
        val doneRequested: Boolean = false,
        @StringRes val message: Int? = null,
    ) {
        val canFinish: Boolean get() = doneRequested && savingCount == 0
    }

    private val saveMutex = Mutex()

    private val _state = MutableStateFlow(
        UiState(code = handle[KEY_CODE] ?: appendTo, photoCount = handle[KEY_COUNT] ?: 0)
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // Capturas que la cámara terminó de escribir pero no llegaron a la base (el proceso murió).
        for (name in pendingCaptures()) persist(store.captureFile(name))
    }

    suspend fun newCaptureFile(): File = store.newCaptureFile()

    /** La cámara terminó de escribir [file]: desde acá la foto sobrevive a la muerte del proceso. */
    fun onPhotoSaved(file: File) {
        handle[KEY_PENDING] = ArrayList(pendingCaptures() + file.name)
        persist(file)
    }

    fun onCaptureFailed(file: File, error: Throwable) {
        log("Capture failed", error)
        externalScope.launch { store.deleteCapture(file) }
        _state.update { it.copy(message = R.string.camera_error_capture) }
    }

    fun onDone() {
        _state.update { it.copy(doneRequested = true) }
    }

    fun onMessageShown() {
        _state.update { it.copy(message = null) }
    }

    private fun persist(file: File) {
        _state.update { it.copy(savingCount = it.savingCount + 1) }
        externalScope.launch(Dispatchers.Main.immediate) {
            try {
                if (!store.captureExists(file)) {
                    removePending(file.name)
                    return@launch
                }
                saveMutex.withLock {
                    val sources = listOf(AttachmentSource.Content(Uri.fromFile(file)))
                    val code = _state.value.code
                    if (code == null) {
                        val created = repository.createLink(sources)
                        handle[KEY_CODE] = created
                        _state.update { it.copy(code = created) }
                    } else {
                        repository.addAttachments(code, sources)
                    }
                    val count = _state.value.photoCount + 1
                    handle[KEY_COUNT] = count
                    _state.update { it.copy(photoCount = count) }
                }
                discardCapture(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Could not save photo", e)
                discardCapture(file)
                _state.update { it.copy(message = e.messageRes()) }
            } finally {
                _state.update { it.copy(savingCount = it.savingCount - 1) }
            }
        }
    }

    private suspend fun discardCapture(file: File) {
        if (!store.deleteCapture(file)) log("Could not delete capture ${file.name}")
        removePending(file.name)
    }

    private fun pendingCaptures(): List<String> = handle.get<ArrayList<String>>(KEY_PENDING).orEmpty()

    private fun removePending(name: String) {
        handle[KEY_PENDING] = ArrayList(pendingCaptures() - name)
    }

    companion object {
        private const val KEY_CODE = "camera_code"
        private const val KEY_COUNT = "camera_count"
        internal const val KEY_PENDING = "camera_pending"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PaperLinkApp
                val handle = createSavedStateHandle()
                CameraViewModel(
                    handle = handle,
                    appendTo = handle.toRoute<CameraDestination>().appendTo,
                    repository = app.container.linkRepository,
                    store = app.container.attachmentStore,
                    externalScope = app.container.applicationScope,
                )
            }
        }
    }
}
