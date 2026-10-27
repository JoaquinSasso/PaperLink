package com.joasasso.paperlink.ui.common

import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.BatchImporter
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Guarda lo elegido desde la app (fotos, archivos, link, nota) con [BatchImporter]. No hay espera:
 * el código (nuevo o existente) se devuelve enseguida y los elementos llegan en segundo plano, con
 * su progreso en [BatchImporter.statuses]. Lo comparten la Home, la pantalla del código y el detalle.
 */
class ContentImporter(
    private val scope: CoroutineScope,
    private val batchImporter: BatchImporter,
) {
    /**
     * @param appendTo código existente, o null para crear uno nuevo.
     * @param onSuccess recibe el código, ya guardado en la base (sus elementos pueden seguir llegando).
     */
    fun start(
        sources: List<AttachmentSource>,
        appendTo: String?,
        onSuccess: (code: String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (sources.isEmpty()) return
        scope.launch {
            try {
                onSuccess(batchImporter.start(sources, appendTo))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Import failed", e)
                onError(e)
            }
        }
    }
}
