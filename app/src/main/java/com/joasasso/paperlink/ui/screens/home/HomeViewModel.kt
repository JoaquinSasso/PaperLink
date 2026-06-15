package com.joasasso.paperlink.ui.screens.home

import android.app.Application
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.CodeAlphabet
import com.joasasso.paperlink.domain.GenerateCodeUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Estado Visual-First para el Home.
 */
data class HomeUiState(
    val searchQuery: String = "",
    val isQueryValid: Boolean = false,
    val links: List<PaperLink> = emptyList(),
    val linkToDelete: PaperLink? = null,
    val recentPhotoUri: Uri? = null,
    val isSaving: Boolean = false,
    val shouldLaunchCamera: Boolean = false,
    val dismissedPhotoUris: Set<String> = emptySet()
)

sealed class HomeEvent {
    data class Error(val message: String) : HomeEvent()
}

class HomeViewModel(
    private val repository: PaperLinkRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val generateCodeUseCase: GenerateCodeUseCase,
    private val application: Application
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    private val _shouldLaunchCamera = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        _uiState,
        repository.getAllLinks(),
        _shouldLaunchCamera,
        userPreferencesRepository.dismissedUris
    ) { state, links, launchCamera, dismissedUris ->
        Log.d("PaperLinkDebug", "[FLOW] Combining state: launchCamera=$launchCamera, dismissedCount=${dismissedUris.size}")
        state.copy(
            links = links, 
            shouldLaunchCamera = launchCamera,
            dismissedPhotoUris = dismissedUris
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState()
    )

    private val _events = MutableSharedFlow<HomeEvent>()
    val events = _events.asSharedFlow()

    init {
        // Observamos los cambios en las URIs descartadas para refrescar el banner
        // Esto también soluciona el problema de que el MediaStore se consulta antes de cargar el DataStore
        viewModelScope.launch {
            userPreferencesRepository.dismissedUris.collectLatest { dismissed ->
                Log.d("PaperLinkBanner", "[INIT] Dismissed URIs updated in ViewModel. Size: ${dismissed.size}")
                checkRecentPhoto()
            }
        }
    }

    /**
     * Canal 2: Consulta el MediaStore para la foto más reciente (< 5 min).
     */
    fun checkRecentPhoto() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d("HomeViewModel", "Checking for recent photos...")
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED
                )
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
                val fiveMinutesAgoMs = System.currentTimeMillis() - (5 * 60 * 1000)

                application.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    null,
                    null,
                    sortOrder
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val id = cursor.getLong(idColumn)
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        val dateAddedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED))
                        val dateTakenMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                        
                        val nowSec = System.currentTimeMillis() / 1000
                        val diffAddedSec = nowSec - dateAddedSec
                        
                        val isDismissed = uiState.value.dismissedPhotoUris.contains(contentUri.toString())
                        Log.d("PaperLinkBanner", "[BANNER CHECK] URI: $contentUri | diff: ${diffAddedSec}s | isDismissed: $isDismissed")

                        // Si se añadió hace menos de 5 minutos (300s) y no ha sido descartada
                        if (diffAddedSec < 300 && !isDismissed) {
                            Log.d("PaperLinkBanner", "[BANNER CHECK] SUCCESS: Showing banner.")
                            _uiState.update { it.copy(recentPhotoUri = contentUri) }
                        } else {
                            Log.d("PaperLinkBanner", "[BANNER CHECK] SKIPPED: Too old or dismissed.")
                            _uiState.update { it.copy(recentPhotoUri = null) }
                        }
                    } else {
                        _uiState.update { it.copy(recentPhotoUri = null) }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error checking recent photo", e)
            }
        }
    }

    private fun updateRecentPhoto(cursor: android.database.Cursor) {
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val id = cursor.getLong(idColumn)
        val contentUri = ContentUris.withAppendedId(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            id
        )
        Log.d("HomeViewModel", "Found recent photo: $contentUri")
        _uiState.update { it.copy(recentPhotoUri = contentUri) }
    }

    /**
     * Procesa una URI entrante (Share Intent o Banner) y genera el código.
     */
    fun processIncomingUri(uri: Uri, contentType: ContentType? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                // Determinar tipo si no se provee
                val resolvedType = contentType ?: resolveContentType(uri)
                
                // Asegurar que tenemos una URI estable (referencia persistente o copia interna)
                val stableUri = ensureStableUri(uri, resolvedType)

                val code = generateCodeUseCase()
                val newLink = PaperLink(
                    code = code,
                    contentType = resolvedType,
                    contentUri = stableUri.toString()
                )
                repository.insert(newLink)
                _uiState.update { it.copy(isSaving = false, recentPhotoUri = null) }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error al procesar URI: $uri", e)
                _uiState.update { it.copy(isSaving = false) }
                _events.emit(HomeEvent.Error("Error al guardar: ${e.localizedMessage}"))
            }
        }
    }

    /**
     * Intenta persistir la URI o realiza una copia interna si es temporal.
     */
    private suspend fun ensureStableUri(uri: Uri, type: ContentType): Uri = withContext(Dispatchers.IO) {
        // 1. Si es MediaStore (galería de Android), intentamos tomar permiso persistente.
        // Las URIs de MediaStore son estables.
        if (uri.authority == "media" || uri.toString().contains("media/external")) {
            try {
                application.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                return@withContext uri
            } catch (e: Exception) {
                Log.d("HomeViewModel", "No se pudo persistir permiso para MediaStore, se procederá a copia: $uri")
            }
        }

        // 2. Si ya es una URI interna de nuestra propia app, no hacemos nada.
        if (uri.toString().contains(application.packageName)) {
            return@withContext uri
        }

        // 3. Fallback: Copiamos el archivo al almacenamiento interno.
        // Esto cubre Google Fotos (content://com.google.android.apps.photos.contentprovider...)
        // y cualquier otro proveedor de contenido temporal.
        return@withContext copyUriToInternal(uri, type)
    }

    /**
     * Copia el contenido de una URI al almacenamiento interno de la app.
     */
    private fun copyUriToInternal(uri: Uri, type: ContentType): Uri {
        val sharedDir = File(application.filesDir, "shared")
        if (!sharedDir.exists()) sharedDir.mkdirs()

        val extension = when (type) {
            ContentType.IMAGE -> "jpg"
            ContentType.VIDEO -> "mp4"
            ContentType.AUDIO -> "m4a"
            ContentType.PDF -> "pdf"
            else -> "bin"
        }
        val fileName = "shared_${System.currentTimeMillis()}.$extension"
        val destFile = File(sharedDir, fileName)

        try {
            application.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            // Retornamos una URI de FileProvider para que sea compartible en el futuro
            return FileProvider.getUriForFile(
                application,
                "${application.packageName}.fileprovider",
                destFile
            )
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Error copiando archivo interno", e)
            return uri // Devolvemos la original como último recurso
        }
    }

    private fun resolveContentType(uri: Uri): ContentType {
        val mimeType = application.contentResolver.getType(uri) ?: ""
        return when {
            mimeType.startsWith("image/") -> ContentType.IMAGE
            mimeType.startsWith("video/") -> ContentType.VIDEO
            mimeType.startsWith("audio/") -> ContentType.AUDIO
            mimeType == "application/pdf" -> ContentType.PDF
            else -> ContentType.WEB_LINK // Fallback
        }
    }

    /**
     * Prepara una URI temporal para la cámara en el almacenamiento permanente interno.
     */
    fun getTempCameraUri(): Uri {
        val cameraDir = File(application.filesDir, "camera")
        if (!cameraDir.exists()) cameraDir.mkdirs()
        val file = File(cameraDir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            application,
            "${application.packageName}.fileprovider",
            file
        )
    }

    fun onTakePhotoClicked() {
        _shouldLaunchCamera.value = true
    }

    fun onCameraLaunched() {
        _shouldLaunchCamera.value = false
    }

    fun onDismissRecentPhoto() {
        val uri = _uiState.value.recentPhotoUri ?: return
        Log.d("PaperLinkBanner", "[BANNER STEP 1] User requested dismissal for URI: $uri")
        viewModelScope.launch {
            Log.d("PaperLinkBanner", "[BANNER STEP 2] Calling userPreferencesRepository.saveDismissedUri($uri)")
            userPreferencesRepository.saveDismissedUri(uri.toString())
            _uiState.update { 
                Log.d("PaperLinkBanner", "[BANNER STEP 3] Local UI State updated: recentPhotoUri = null")
                it.copy(recentPhotoUri = null) 
            }
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        val normalized = CodeAlphabet.normalize(newQuery)
        if (normalized.length <= CodeAlphabet.CODE_LENGTH) {
            _uiState.update { it.copy(
                searchQuery = normalized,
                isQueryValid = CodeAlphabet.isValid(normalized)
            ) }
        }
    }

    fun confirmDelete(link: PaperLink?) {
        _uiState.update { it.copy(linkToDelete = link) }
    }

    fun deleteLink() {
        val link = _uiState.value.linkToDelete ?: return
        viewModelScope.launch {
            repository.delete(link, application.filesDir)
            _uiState.update { it.copy(linkToDelete = null) }
        }
    }

    fun findLinkByCode(code: String): PaperLink? {
        return uiState.value.links.find { it.code.equals(code, ignoreCase = true) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as PaperLinkApp)
                HomeViewModel(
                    application.container.paperLinkRepository,
                    application.container.userPreferencesRepository,
                    application.container.generateCodeUseCase,
                    application
                )
            }
        }
    }
}
