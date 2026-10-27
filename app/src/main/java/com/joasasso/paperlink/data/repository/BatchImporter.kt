package com.joasasso.paperlink.data.repository

import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.preferences.PendingImport
import com.joasasso.paperlink.data.preferences.UserPreferencesRepository
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Guarda varios elementos de una vez (por ejemplo, 25 fotos de la galería) sin hacer esperar:
 *
 * - Importa hasta [parallelism] archivos a la vez (re-codificar fotos es lo lento).
 * - Al crear un código, el código se guarda vacío antes de importar nada y se devuelve: la pantalla
 *   del código aparece al instante (el código ya existe en la base, garantía 8).
 * - Los elementos se agregan en segundo plano, en el orden elegido, en [scope] (sobrevive a salir de
 *   la pantalla). [statuses] expone el progreso para mostrarlo.
 * - Las cargas en curso se anotan en preferencias: si el proceso muere a mitad, al volver se avisa
 *   cuántos elementos no se llegaron a guardar (ver [UserPreferencesRepository.pendingImports]).
 */
class BatchImporter(
    private val repository: LinkRepository,
    private val store: AttachmentStore,
    private val preferences: UserPreferencesRepository,
    private val scope: CoroutineScope,
    parallelism: Int,
) {
    /** Progreso de las cargas en segundo plano de un código. */
    data class Status(
        val code: String,
        val total: Int,
        val saved: Int,
        val failed: Int,
        val running: Boolean,
    ) {
        val done: Int get() = saved + failed
    }

    private val semaphore = Semaphore(parallelism)
    private val persistLock = Mutex()
    private val jobs = ConcurrentHashMap<String, MutableSet<Job>>()

    private val _statuses = MutableStateFlow<Map<String, Status>>(emptyMap())
    val statuses: StateFlow<Map<String, Status>> = _statuses.asStateFlow()

    fun status(code: String): Flow<Status?> = statuses.map { it[code] }.distinctUntilChanged()

    /** Espera a que terminen las cargas en segundo plano en curso (si hay alguna). */
    suspend fun awaitIdle() {
        statuses.first { current -> current.values.none { it.running } }
    }

    /**
     * Guarda [sources] en un código nuevo ([appendTo] null) o existente y devuelve el código enseguida.
     *
     * El código nuevo se crea vacío antes de importar nada (unos milisegundos), así el usuario lo
     * anota mientras los elementos llegan en segundo plano, en el orden elegido. Si no se pudiera
     * guardar ninguno, el código queda (ya está anotado) y [statuses] lo informa para agregarlos de nuevo.
     */
    suspend fun start(sources: List<AttachmentSource>, appendTo: String? = null): String {
        require(sources.isNotEmpty()) { "Nothing to import" }
        val startedAt = System.currentTimeMillis()
        val code = appendTo ?: repository.createEmptyLink()
        log("Batch: code ready after ${System.currentTimeMillis() - startedAt} ms (${sources.size} items)")

        val batchJob = SupervisorJob(scope.coroutineContext[Job])
        val batchScope = CoroutineScope(scope.coroutineContext + batchJob)
        val prepared: List<Deferred<Result<AttachmentSource>>> = sources.mapIndexed { index, source ->
            batchScope.async { semaphore.withPermit { prepare(source, index) } }
        }
        runInBackground(batchScope, batchJob, code, prepared, startedAt)
        return code
    }

    /** Cancela las cargas en segundo plano de [code]. Lo ya guardado queda. */
    fun cancel(code: String) {
        jobs[code]?.forEach { it.cancel() }
    }

    /** El usuario ya vio el resultado (por ejemplo, cuántos fallaron): se deja de mostrar. */
    fun acknowledge(code: String) {
        _statuses.update { current -> current[code]?.takeIf { !it.running }?.let { current - code } ?: current }
    }

    private fun runInBackground(
        batchScope: CoroutineScope,
        batchJob: CompletableJob,
        code: String,
        prepared: List<Deferred<Result<AttachmentSource>>>,
        startedAt: Long,
    ) {
        updateStatus(code) { old ->
            old?.copy(total = old.total + prepared.size, running = true) ?: Status(code, prepared.size, 0, 0, running = true)
        }
        // LAZY: se registra antes de arrancar, así `finish` siempre lo encuentra.
        val job = batchScope.launch(start = CoroutineStart.LAZY) {
            val self = coroutineContext[Job]
            var next = 0
            try {
                // Dentro del try: si la cancelación llega acá, el finally igual cierra la carga.
                persistPending()
                while (next < prepared.size) {
                    val result = prepared[next].await()
                    next++
                    val source = result.getOrNull()
                    if (source == null) {
                        updateStatus(code) { it?.copy(failed = it.failed + 1) }
                        continue
                    }
                    try {
                        repository.addAttachments(code, listOf(source))
                        updateStatus(code) { it?.copy(saved = it.saved + 1) }
                        persistPending()
                    } catch (e: LinkNotFoundException) {
                        // El código se borró mientras se cargaba: no tiene sentido seguir.
                        discard(source)
                        log("Code deleted during import, stopping", e)
                        break
                    } catch (e: CancellationException) {
                        discard(source)
                        throw e
                    } catch (e: Exception) {
                        log("Could not append imported item", e)
                        discard(source)
                        updateStatus(code) { it?.copy(failed = it.failed + 1) }
                    }
                }
            } finally {
                log("Batch: ${prepared.size} items finished after ${System.currentTimeMillis() - startedAt} ms")
                withContext(NonCancellable) {
                    batchJob.cancel()
                    discardCompleted(prepared.drop(next))
                    finish(code, self)
                }
            }
        }
        jobs.getOrPut(code) { ConcurrentHashMap.newKeySet() }.add(job)
        job.start()
    }

    /** Importa un archivo (o deja pasar un link o una nota). Los errores se devuelven, no se lanzan. */
    private suspend fun prepare(source: AttachmentSource, index: Int): Result<AttachmentSource> =
        try {
            Result.success(
                when (source) {
                    is AttachmentSource.Content -> AttachmentSource.Stored(store.import(source.uri))
                    else -> source
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Could not import item $index", e)
            Result.failure(e)
        }

    private fun updateStatus(code: String, transform: (Status?) -> Status?) {
        _statuses.update { current ->
            val updated = transform(current[code])
            if (updated == null) current - code else current + (code to updated)
        }
    }

    private suspend fun finish(code: String, job: Job?) {
        val codeJobs = jobs[code]
        codeJobs?.remove(job)
        if (codeJobs.isNullOrEmpty()) {
            jobs.remove(code)
            // Primero se borra la anotación y después el estado: si fuera al revés, la Home vería por un
            // instante una carga anotada sin estar en curso y mostraría el aviso de "carga cortada".
            persistPending(finished = code)
            // Sin fallas no hace falta mostrar nada más; con fallas queda hasta que el usuario lo vea.
            updateStatus(code) { status -> status?.takeIf { it.failed > 0 }?.copy(running = false) }
        } else {
            persistPending()
        }
    }

    /** Anota en preferencias las cargas en curso (para avisar si el proceso muere a mitad). */
    private suspend fun persistPending(finished: String? = null) = persistLock.withLock {
        val running = _statuses.value.values
            .filter { it.running && it.code != finished }
            .map { PendingImport(it.code, it.total, it.saved) }
        preferences.setPendingImports(running)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun discardCompleted(items: List<Deferred<Result<AttachmentSource>>>) {
        for (item in items) {
            if (item.isCompleted && !item.isCancelled) {
                item.getCompleted().getOrNull()?.let { discard(it) }
            }
        }
    }

    /** Borra el archivo de un elemento importado que no llegó a la base (si ninguna fila lo usa). */
    private suspend fun discard(source: AttachmentSource) {
        val path = (source as? AttachmentSource.Stored)?.file?.relativePath ?: return
        repository.discardUnreferenced(listOf(path))
    }
}
