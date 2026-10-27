package com.joasasso.paperlink.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.backup.DriveAppDataClient
import com.joasasso.paperlink.data.backup.RemoteDriveFile
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Solo en debug: mide formas de subir archivos a `appDataFolder` y borra lo que subió.
 *
 *     adb shell am broadcast -a com.joasasso.paperlink.debug.DRIVE_BENCH --ei count 20 --ei sizeKb 300 -p com.joasasso.paperlink.debug
 *
 * Los resultados salen en logcat (`DriveBench:`). Los archivos se llaman `bench-*`.
 */
class DriveBenchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as PaperLinkApp).container
        val count = intent.getIntExtra("count", 20)
        val size = intent.getIntExtra("sizeKb", 300) * 1024
        val pending = goAsync()
        container.applicationScope.launch(Dispatchers.IO) {
            val client = DriveAppDataClient()
            val uploaded = mutableListOf<RemoteDriveFile>()
            try {
                val token: String
                val tokenMs = measureTimeMillis { token = container.driveAuthManager.getAccessToken() }
                val listMs = measureTimeMillis { client.listFiles(token) }
                log("DriveBench: token $tokenMs ms, list $listMs ms, $count files of ${size / 1024} KB")
                val data = Random(1).nextBytes(size)

                suspend fun run(label: String, parallelism: Int, upload: (String) -> RemoteDriveFile) {
                    val semaphore = Semaphore(parallelism)
                    val ms = measureTimeMillis {
                        coroutineScope {
                            (0 until count).map { i ->
                                async(Dispatchers.IO) { semaphore.withPermit { upload("bench-$label-$i") } }
                            }.awaitAll().let { synchronized(uploaded) { uploaded += it } }
                        }
                    }
                    log("DriveBench: $label total $ms ms, ${ms / count} ms/file")
                }

                run("resumable-x1", 1) { client.uploadResumable(token, it, "image/webp", size.toLong(), data.inputStream()) }
                run("multipart-x1", 1) { client.uploadMultipart(token, it, "image/webp", size.toLong(), data.inputStream()) }
                run("multipart-x4", 4) { client.uploadMultipart(token, it, "image/webp", size.toLong(), data.inputStream()) }
                run("multipart-x8", 8) { client.uploadMultipart(token, it, "image/webp", size.toLong(), data.inputStream()) }
            } catch (e: Exception) {
                log("DriveBench: failed", e)
            } finally {
                withContext(Dispatchers.IO) {
                    val token = container.driveAuthManager.getAccessToken()
                    uploaded.forEach { client.deleteFile(token, it.id) }
                }
                log("DriveBench: deleted ${uploaded.size} bench files")
                pending.finish()
            }
        }
    }
}
