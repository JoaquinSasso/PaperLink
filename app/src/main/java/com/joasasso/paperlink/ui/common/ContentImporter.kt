package com.joasasso.paperlink.ui.common

import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.ImportProgress
import com.joasasso.paperlink.data.repository.LinkRepository
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Guarda lo elegido desde la app (fotos, archivos, link, nota) en un código nuevo o existente,
 * exponiendo el progreso y permitiendo cancelar. Lo comparten la Home y la pantalla del código.
 */
class ContentImporter(
    private val scope: CoroutineScope,
    private val repository: LinkRepository,
) {
    /** Importación en curso; [progress] es null hasta que empieza a copiarse el primer archivo. */
    data class Status(val progress: ImportProgress?)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private var job: Job? = null

    /**
     * @param appendTo código existente, o null para crear uno nuevo.
     * @param onSuccess recibe el código, ya guardado en la base.
     */
    fun start(
        sources: List<AttachmentSource>,
        appendTo: String?,
        onSuccess: (code: String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (sources.isEmpty() || job?.isActive == true) return
        _status.value = Status(progress = null)
        job = scope.launch {
            try {
                val update: (ImportProgress) -> Unit = { _status.value = Status(it) }
                val code = if (appendTo == null) {
                    repository.createLink(sources, onProgress = update)
                } else {
                    repository.addAttachments(appendTo, sources, onProgress = update)
                    appendTo
                }
                onSuccess(code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Import failed", e)
                onError(e)
            } finally {
                _status.value = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
