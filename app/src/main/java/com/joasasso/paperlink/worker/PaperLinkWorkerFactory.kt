package com.joasasso.paperlink.worker

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase

/**
 * Factory personalizada para inyectar dependencias en los Workers de PaperLink.
 */
class PaperLinkWorkerFactory(
    private val repository: PaperLinkRepository,
    private val generateCodeUseCase: GenerateCodeUseCase
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            PhotoScannerWorker::class.java.name -> {
                PhotoScannerWorker(
                    appContext,
                    workerParameters,
                    repository,
                    generateCodeUseCase
                )
            }
            else -> null // Delegar a la fábrica por defecto si no coincide
        }
    }
}
