package com.joasasso.paperlink.data.backup

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Archivo almacenado en `appDataFolder` de Google Drive. */
@Serializable
data class RemoteDriveFile(
    val id: String,
    val name: String,
    val size: Long? = null,
)

/**
 * Operaciones remotas sobre la carpeta oculta `appDataFolder` de Google Drive.
 */
interface DriveRemoteStore {
    /** Lista todos los archivos en `appDataFolder`, ordenados del más reciente al más antiguo. */
    suspend fun listFiles(accessToken: String): List<RemoteDriveFile>

    /** Sube un archivo nuevo a `appDataFolder` leyendo su contenido de [contentStream]. */
    suspend fun uploadStream(
        accessToken: String,
        name: String,
        mimeType: String,
        contentStream: InputStream,
    ): RemoteDriveFile

    /** Descarga el contenido del archivo [fileId] y se lo pasa a [consumer]. */
    suspend fun downloadFile(
        accessToken: String,
        fileId: String,
        consumer: suspend (InputStream) -> Unit,
    )

    /** Elimina un archivo de `appDataFolder` por su [fileId]. */
    suspend fun deleteFile(accessToken: String, fileId: String)
}

/**
 * Cliente REST v3 de Google Drive sobre OkHttp, restringido al espacio `appDataFolder`.
 */
class DriveAppDataClient(
    private val httpClient: OkHttpClient = defaultOkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DriveRemoteStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class FileListResponse(
        val nextPageToken: String? = null,
        val files: List<RemoteDriveFile> = emptyList(),
    )

    @Serializable
    private data class CreateFileMetadata(
        val name: String,
        val parents: List<String> = listOf(APP_DATA_FOLDER),
    )

    override suspend fun listFiles(accessToken: String): List<RemoteDriveFile> = withContext(ioDispatcher) {
        val allFiles = mutableListOf<RemoteDriveFile>()
        var pageToken: String? = null
        do {
            currentCoroutineContext().ensureActive()
            val urlBuilder = FILES_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("spaces", APP_DATA_FOLDER)
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("orderBy", "modifiedTime desc")
                .addQueryParameter("fields", "nextPageToken,files(id,name,size)")
            if (pageToken != null) {
                urlBuilder.addQueryParameter("pageToken", pageToken)
            }
            val request = Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val page = executeRequest(request) { response ->
                val body = response.body?.string()
                    ?: throw BackupException.NetworkError("Empty response listing Drive files")
                json.decodeFromString<FileListResponse>(body)
            }
            allFiles += page.files
            pageToken = page.nextPageToken
        } while (!pageToken.isNullOrEmpty())
        allFiles
    }

    override suspend fun uploadStream(
        accessToken: String,
        name: String,
        mimeType: String,
        contentStream: InputStream,
    ): RemoteDriveFile = withContext(ioDispatcher) {
        val boundary = "paperlink_${UUID.randomUUID()}"
        val metadataJson = json.encodeToString(CreateFileMetadata(name = name))
        val multipartBody = object : RequestBody() {
            override fun contentType() = "multipart/related; boundary=$boundary".toMediaType()
            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("--$boundary\r\n")
                sink.writeUtf8("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                sink.writeUtf8(metadataJson)
                sink.writeUtf8("\r\n--$boundary\r\n")
                sink.writeUtf8("Content-Type: $mimeType\r\n\r\n")

                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = contentStream.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                }
                sink.writeUtf8("\r\n--$boundary--\r\n")
            }
        }

        val url = UPLOAD_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", "id,name,size")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(multipartBody)
            .build()

        executeRequest(request) { response ->
            val body = response.body?.string()
                ?: throw BackupException.NetworkError("Empty response uploading $name")
            json.decodeFromString<RemoteDriveFile>(body)
        }
    }

    override suspend fun downloadFile(
        accessToken: String,
        fileId: String,
        consumer: suspend (InputStream) -> Unit,
    ) = withContext(ioDispatcher) {
        val url = "$FILES_ENDPOINT/$fileId".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw BackupException.NetworkError("Network failure downloading Drive file", e)
        }
        response.use { res ->
            checkResponseStatus(res)
            val stream = res.body?.byteStream()
                ?: throw BackupException.NetworkError("Empty body downloading Drive file")
            stream.use { consumer(it) }
        }
    }

    override suspend fun deleteFile(accessToken: String, fileId: String) = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("$FILES_ENDPOINT/$fileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()

        executeRequest(request) { /* 204 No Content */ }
    }

    private inline fun <T> executeRequest(request: Request, parse: (Response) -> T): T {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw BackupException.NetworkError("Network failure talking to Google Drive", e)
        }
        return response.use { res ->
            checkResponseStatus(res)
            try {
                parse(res)
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException.NetworkError("Invalid response from Google Drive", e)
            }
        }
    }

    private fun checkResponseStatus(response: Response) {
        if (response.isSuccessful) return
        val code = response.code
        if (code == 401 || code == 403) {
            throw BackupException.AuthRequired(message = "Google Drive rejected credentials ($code)")
        }
        throw BackupException.NetworkError("Google Drive error: HTTP $code")
    }

    companion object {
        private const val APP_DATA_FOLDER = "appDataFolder"
        private const val FILES_ENDPOINT = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD_ENDPOINT = "https://www.googleapis.com/upload/drive/v3/files"
        private const val BUFFER_SIZE = 64 * 1024

        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
