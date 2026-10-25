package com.joasasso.paperlink.data.backup

import android.net.Uri
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.files.ImportException
import com.joasasso.paperlink.data.repository.LinkRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Exportación e importación manual de respaldos en formato ZIP (`manifest.json` + carpeta `media/`).
 * Toda lectura y escritura de archivos pasa por [AttachmentStore] y la fusión por [LinkRepository].
 */
class ZipBackupManager(
    private val store: AttachmentStore,
    private val repository: LinkRepository,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Exporta todos los códigos y archivos vigentes a [destinationUri].
     */
    suspend fun exportZip(
        destinationUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupManifest {
        val manifest = repository.exportSnapshot()
        val filePaths = manifest.attachments.mapNotNull { it.relativePath }.distinct()
        onProgress(BackupProgress(0, filePaths.size))

        store.openOutputStream(destinationUri) { rawOut ->
            ZipOutputStream(BufferedOutputStream(rawOut, BUFFER_SIZE)).use { zipOut ->
                currentCoroutineContext().ensureActive()
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                zipOut.putNextEntry(ZipEntry(BackupManifest.MANIFEST_FILE_NAME))
                zipOut.write(manifestBytes)
                zipOut.closeEntry()

                val buffer = ByteArray(BUFFER_SIZE)
                filePaths.forEachIndexed { index, relativePath ->
                    currentCoroutineContext().ensureActive()
                    zipOut.putNextEntry(ZipEntry(relativePath))
                    store.readFile(relativePath) { input ->
                        copyStream(input, zipOut, buffer)
                    }
                    zipOut.closeEntry()
                    onProgress(BackupProgress(index + 1, filePaths.size))
                }
                zipOut.finish()
            }
        }
        return manifest
    }

    /**
     * Importa un archivo ZIP desde [sourceUri], verificando el SHA-256 de cada archivo antes de
     * fusionar los vínculos con los datos locales sin duplicar ni pisar (garantías 12 y 14).
     */
    suspend fun importZip(
        sourceUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): MergeCounts {
        val manifest = readManifest(sourceUri)
        val filesByPath = manifest.attachments
            .filter { it.relativePath != null }
            .associateBy { it.relativePath!! }
        val totalFiles = filesByPath.size
        onProgress(BackupProgress(0, totalFiles))

        val newlyRestoredPaths = mutableListOf<String>()
        try {
            var restoredCount = 0
            val seenPaths = HashSet<String>(totalFiles)
            store.openInputStream(sourceUri) { rawIn ->
                ZipInputStream(rawIn.buffered(BUFFER_SIZE)).use { zipIn ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zipIn.nextEntry ?: break
                        validateEntryName(entry.name)
                        val att = filesByPath[entry.name]
                        if (att != null && seenPaths.add(entry.name)) {
                            val path = att.relativePath!!
                            val created = store.restoreFile(
                                relativePath = path,
                                expectedSha256 = att.sha256,
                                estimatedSize = att.sizeBytes,
                            ) { out ->
                                copyStream(zipIn, out, buffer)
                            }
                            if (created) newlyRestoredPaths += path
                            restoredCount++
                            onProgress(BackupProgress(restoredCount, totalFiles))
                        }
                        zipIn.closeEntry()
                    }
                }
            }
            return repository.commitBackup(manifest, newlyRestoredPaths)
        } catch (e: CancellationException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw e
        } catch (e: BackupException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw e
        } catch (e: ImportException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw e
        } catch (e: ZipException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw BackupException.InvalidArchive("Invalid ZIP file", e)
        } catch (e: EOFException) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw BackupException.CorruptBackup("Truncated ZIP file", e)
        } catch (t: Throwable) {
            repository.discardRestoredFiles(newlyRestoredPaths)
            throw t
        }
    }

    private suspend fun readManifest(sourceUri: Uri): BackupManifest {
        val manifestText = try {
            store.openInputStream(sourceUri) { rawIn ->
                ZipInputStream(rawIn.buffered(BUFFER_SIZE)).use { zipIn ->
                    var found: String? = null
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val entry = zipIn.nextEntry ?: break
                        validateEntryName(entry.name)
                        if (entry.name == BackupManifest.MANIFEST_FILE_NAME) {
                            found = readBoundedUtf8(zipIn, MAX_MANIFEST_BYTES)
                            zipIn.closeEntry()
                            break
                        }
                        zipIn.closeEntry()
                    }
                    found
                }
            }
        } catch (e: ZipException) {
            throw BackupException.InvalidArchive("Invalid ZIP archive", e)
        } catch (e: EOFException) {
            throw BackupException.InvalidArchive("Truncated ZIP archive", e)
        } ?: throw BackupException.InvalidArchive("Missing ${BackupManifest.MANIFEST_FILE_NAME} in ZIP")

        val manifest = try {
            json.decodeFromString<BackupManifest>(manifestText)
        } catch (e: SerializationException) {
            throw BackupException.InvalidArchive("Malformed ${BackupManifest.MANIFEST_FILE_NAME}", e)
        } catch (e: IllegalArgumentException) {
            throw BackupException.InvalidArchive("Invalid ${BackupManifest.MANIFEST_FILE_NAME}", e)
        }
        manifest.validate()
        return manifest
    }

    private fun validateEntryName(name: String) {
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
            throw BackupException.InvalidArchive("Unsafe ZIP entry name: $name")
        }
    }

    private suspend fun readBoundedUtf8(input: InputStream, maxBytes: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                throw BackupException.InvalidArchive("Manifest exceeds maximum allowed size")
            }
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private suspend fun copyStream(input: InputStream, out: OutputStream, buffer: ByteArray) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 32 * 1024 * 1024
    }
}
