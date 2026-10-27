package com.joasasso.paperlink.ui.common

import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.data.repository.BatchImporter
import com.joasasso.paperlink.data.repository.ImportProgress
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Guarda lo elegido desde la app (fotos, archivos, link, nota) con [BatchImporter].
 *
 * Al crear un código, [status] muestra el progreso solo hasta que el primer elemento queda guardado
 * (el código ya existe y se abre su pantalla); el resto sigue en segundo plano. Al agregar a un
 * código existente no hay espera: todo va en segundo plano.
 */
class ContentImporter(
    private val scope: CoroutineScope,
    private val batchImporter: BatchImporter,
) {
    /** Esperando el primer elemento; [progress] es null hasta que empieza a copiarse. */
    data class Status(val progress: ImportProgress?)

    private val _status = MutableStateFlow<Status?>(null)
    val status: StateFlow<Status?> = _status.asStateFlow()

    private var job: Job? = null

    /**
     * @param appendTo código existente, o null para crear uno nuevo.
     * @param onSuccess recibe el código, ya guardado en la base con al menos un elemento.
     */
    fun start(
        sources: List<AttachmentSource>,
        appendTo: String?,
        onSuccess: (code: String) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        if (sources.isEmpty() || job?.isActive == true) return
        if (appendTo == null) _status.value = Status(progress = null)
        job = scope.launch {
            try {
                val code = batchImporter.start(sources, appendTo) { _status.value = Status(it) }
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

    /** Cancela la espera del primer elemento (y con ella toda la carga). */
    fun cancel() {
        job?.cancel()
    }
}
