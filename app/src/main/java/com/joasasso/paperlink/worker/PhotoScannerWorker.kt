package com.joasasso.paperlink.worker

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.*
import com.joasasso.paperlink.data.local.ContentType
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.data.repository.PaperLinkRepository
import com.joasasso.paperlink.domain.GenerateCodeUseCase
import com.joasasso.paperlink.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PhotoScannerWorker(
    context: Context,
    params: WorkerParameters,
    private val repository: PaperLinkRepository,
    private val generateCodeUseCase: GenerateCodeUseCase
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PhotoScanner"
        private const val WORK_NAME_A = "PhotoScannerWork_A"
        private const val WORK_NAME_B = "PhotoScannerWork_B"

        fun enqueue(context: Context, workName: String = WORK_NAME_A) {
            Log.d(TAG, "Enqueuing $workName...")
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                .build()

            val request = OneTimeWorkRequestBuilder<PhotoScannerWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .addTag(workName)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val runId = id.toString().take(5)

        // Estrategia Ping-Pong: Encolar el "otro" worker inmediatamente para no perder disparos
        val currentTags = tags
        val nextWorkName = if (currentTags.contains(WORK_NAME_A)) WORK_NAME_B else WORK_NAME_A

        // Usamos NonCancellable para asegurar que el relevo ocurra
        withContext(NonCancellable) {
            Log.d(TAG, "[$runId] Ping-Pong: Enqueuing $nextWorkName while I process...")
            enqueue(applicationContext, nextWorkName)
        }

        Log.i(TAG, "[$runId] doWork() started! Trigger detected.")
        try {
            val latestPhoto = getLatestPhotoInfo()
            if (latestPhoto != null) {
                val (uri, timestamp) = latestPhoto
                val now = System.currentTimeMillis()
                val latency = now - timestamp

                if (latency < TimeUnit.MINUTES.toMillis(5)) {
                    val code = generateCodeUseCase()
                    val link = PaperLink(code, ContentType.IMAGE, uri, now)
                    repository.insert(link)
                    NotificationHelper.showPhotoCodeNotification(applicationContext, code, latency)
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "[$runId] Failure", e)
            Result.failure()
        }
    }

    private fun getLatestPhotoInfo(): Pair<String, Long>? {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN))
                return "${MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$id" to timestamp
            }
        }
        return null
    }
}