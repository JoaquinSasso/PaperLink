package com.joasasso.paperlink.worker

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import androidx.work.*
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.util.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PhotoDetectionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PhotoWorker"
        private const val WORK_NAME = "PhotoDetectionWork"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
                .setTriggerContentUpdateDelay(1, TimeUnit.SECONDS)
                .setTriggerContentMaxDelay(5, TimeUnit.SECONDS)
                .build()

            val request = OneTimeWorkRequestBuilder<PhotoDetectionWorker>()
                .setConstraints(constraints)
                .addTag(TAG)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Worker enqueued and waiting for MediaStore changes...")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Worker triggered! Checking for latest photo...")
            
            val latestPhotoTime = getLatestPhotoTimestamp()
            if (latestPhotoTime != null) {
                val now = System.currentTimeMillis()
                val latency = now - latestPhotoTime
                Log.i(TAG, "Latency detected: ${latency}ms (Now: $now, Photo: $latestPhotoTime)")

                // Generar código PoC
                val app = applicationContext as PaperLinkApp
                val code = app.container.generateCodeUseCase()

                NotificationHelper.showPhotoCodeNotification(applicationContext, code, latency)
            } else {
                Log.w(TAG, "Worker triggered but no photo found in MediaStore.")
            }

            // Re-encolar para seguir escuchando
            enqueue(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error in PhotoDetectionWorker", e)
            // Re-encolar incluso si falla para no perder el trigger
            enqueue(applicationContext)
            Result.failure()
        }
    }

    private fun getLatestPhotoTimestamp(): Long? {
        val projection = arrayOf(MediaStore.Images.Media.DATE_TAKEN)
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        
        applicationContext.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val dateTakenIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                return cursor.getLong(dateTakenIndex)
            }
        }
        return null
    }
}
