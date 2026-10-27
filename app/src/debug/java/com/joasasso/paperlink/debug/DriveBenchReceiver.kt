package com.joasasso.paperlink.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.backup.DriveAppDataClient
import com.joasasso.paperlink.data.backup.DriveSyncWorker
import com.joasasso.paperlink.data.backup.RemoteDriveFile
import com.joasasso.paperlink.di.AppContainer
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Solo en debug: mide formas de subir archivos a `appDataFolder` y borra lo que subió.
 *
 *     adb shell am broadcast -a com.joasasso.paperlink.debug.DRIVE_BENCH --ei count 20 --ei sizeKb 300 -p com.joasasso.paperlink.debug
 *
 * Extras: `modes` (separados por coma, por defecto todos: resumable-x1, multipart-x1, multipart-x4,
 * multipart-x8) y `--ez worker true` para correrlo dentro de un servicio en primer plano (como el
 * respaldo real), así se puede mandar la app a segundo plano. Sin `worker`, la app tiene que estar
 * abierta. Los resultados salen en logcat (`DriveBench:`). Los archivos se llaman `bench-*`.
 */
class DriveBenchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val count = intent.getIntExtra("count", 20)
        val sizeKb = intent.getIntExtra("sizeKb", 300)
        val modes = intent.getStringExtra("modes") ?: ALL_MODES
        if (intent.getBooleanExtra("worker", false)) {
            val request = OneTimeWorkRequestBuilder<DriveBenchWorker>()
                .setInputData(workDataOf("count" to count, "sizeKb" to sizeKb, "modes" to modes))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("drive_bench", ExistingWorkPolicy.REPLACE, request)
        } else {
            // Sin goAsync: la medición dura más que el límite de un receptor y Android lo mataría por ANR.
            val app = context.applicationContext as PaperLinkApp
            app.container.applicationScope.launch { runDriveBench(app.container, count, sizeKb, modes) }
        }
    }
}

/** La misma medición dentro de un servicio en primer plano `dataSync`. */
class DriveBenchWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val container = (applicationContext as PaperLinkApp).container
        runDriveBench(
            container,
            inputData.getInt("count", 20),
            inputData.getInt("sizeKb", 300),
            inputData.getString("modes") ?: ALL_MODES,
            label = "worker ",
        )
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        DriveSyncWorker.ensureChannel(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, "drive_backup")
            .setSmallIcon(R.drawable.ic_notification_backup)
            .setContentTitle("DriveBench")
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(43, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(43, notification)
        }
    }
}

private const val ALL_MODES = "resumable-x1,multipart-x1,multipart-x4,multipart-x8"

private suspend fun runDriveBench(
    container: AppContainer,
    count: Int,
    sizeKb: Int,
    modes: String,
    label: String = "",
) = withContext(Dispatchers.IO) {
    // "-h1" al final del modo usa HTTP/1.1 (una conexión TCP por subida) en vez de HTTP/2.
    val http2Client = DriveAppDataClient()
    val http1Client = DriveAppDataClient(
        DriveAppDataClient.defaultOkHttpClient().newBuilder().protocols(listOf(okhttp3.Protocol.HTTP_1_1)).build()
    )
    var client = http2Client
    val uploaded = mutableListOf<RemoteDriveFile>()
    val size = sizeKb * 1024
    try {
        val token: String
        val tokenMs = measureTimeMillis { token = container.driveAuthManager.getAccessToken() }
        val existing: List<RemoteDriveFile>
        val listMs = measureTimeMillis { existing = client.listFiles(token) }
        val staleBench = existing.filter { it.name.startsWith("bench-") }
        if (staleBench.isNotEmpty()) {
            val cleanSem = Semaphore(8)
            val cleanMs = measureTimeMillis {
                coroutineScope {
                    staleBench.map { f ->
                        async { cleanSem.withPermit { runCatching { client.deleteFile(token, f.id) } } }
                    }.awaitAll()
                }
            }
            log("DriveBench: cleaned ${staleBench.size} stale bench files in $cleanMs ms")
        }
        log("DriveBench: ${label}token $tokenMs ms, list $listMs ms, $count files of $sizeKb KB")
        val data = Random(1).nextBytes(size)

        for (rawMode in modes.split(',')) {
            client = if (rawMode.endsWith("-h1")) http1Client else http2Client
            val mode = rawMode.removeSuffix("-h1")
            if (mode.startsWith("cf")) {
                // Control: la misma subida a un servidor de prueba (Cloudflare), sin Drive.
                val http = DriveAppDataClient.defaultOkHttpClient()
                val semaphore = Semaphore(mode.substringAfter("-x").toInt())
                val ms = measureTimeMillis {
                    coroutineScope {
                        (0 until count).map {
                            async {
                                semaphore.withPermit {
                                    val request = okhttp3.Request.Builder()
                                        .url("https://speed.cloudflare.com/__up")
                                        .post(data.toRequestBody())
                                        .build()
                                    http.newCall(request).execute().close()
                                }
                            }
                        }.awaitAll()
                    }
                }
                log("DriveBench: $label$rawMode total $ms ms, ${ms / count} ms/file, ${count.toLong() * size * 1000 / ms / 1024} KB/s")
                continue
            }
            if (mode.startsWith("real")) {
                // Los archivos reales de media/, leídos y subidos como en el respaldo.
                val store = container.attachmentStore
                val paths = container.linkRepository.exportSnapshot(includeMissingFiles = false)
                    .attachments.mapNotNull { it.relativePath }.distinct().filter { store.exists(it) }.take(count)
                val semaphore = Semaphore(mode.substringAfter("-x").toInt())
                var bytes = 0L
                val ms = measureTimeMillis {
                    coroutineScope {
                        paths.mapIndexed { i, path ->
                            async {
                                semaphore.withPermit {
                                    val length = store.length(path)
                                    synchronized(uploaded) { bytes += length }
                                    store.readFile(path) { input ->
                                        val mime = if (mode.startsWith("realbin")) "application/octet-stream" else "image/webp"
                                        val file = client.uploadStream(token, "bench-$rawMode-$i", mime, length, input)
                                        synchronized(uploaded) { uploaded += file }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
                log("DriveBench: $label$rawMode ${paths.size} files, ${bytes / 1024} KB, total $ms ms, ${ms / paths.size} ms/file")
                continue
            }
            val parallelism = mode.substringAfter("-x").toInt()
            val resumable = mode.startsWith("resumable")
            val semaphore = Semaphore(parallelism)
            val ms = measureTimeMillis {
                coroutineScope {
                    (0 until count).map { i ->
                        async {
                            semaphore.withPermit {
                                val name = "bench-$rawMode-$i"
                                if (resumable) {
                                    client.uploadResumable(token, name, "image/webp", size.toLong(), data.inputStream())
                                } else {
                                    client.uploadMultipart(token, name, "image/webp", size.toLong(), data.inputStream())
                                }
                            }
                        }
                    }.awaitAll().let { synchronized(uploaded) { uploaded += it } }
                }
            }
            log("DriveBench: $label$rawMode total $ms ms, ${ms / count} ms/file")
        }
    } catch (e: Exception) {
        log("DriveBench: failed: $e / ${e.cause}", e)
    } finally {
        withContext(NonCancellable) {
            val token = container.driveAuthManager.getAccessToken()
            val delSem = Semaphore(8)
            val delMs = measureTimeMillis {
                coroutineScope {
                    uploaded.map { f ->
                        async { delSem.withPermit { runCatching { http2Client.deleteFile(token, f.id) } } }
                    }.awaitAll()
                }
            }
            log("DriveBench: deleted ${uploaded.size} bench files in $delMs ms")
        }
    }
}
