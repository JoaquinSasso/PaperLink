package com.joasasso.paperlink.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.joasasso.paperlink.data.backup.BackupException
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Único componente que toca el disco. Los archivos del usuario viven en `filesDir/media/`
 * y la base guarda solo su ruta relativa (`media/<uuid>.<ext>`).
 *
 * Toda escritura es atómica: se escribe a `<nombre>.part`, se hace `fsync` y recién entonces
 * se renombra al nombre final. Si algo falla o se cancela, los `.part` se borran.
 */
class AttachmentStore(
    private val context: Context,
    baseDir: File = context.filesDir,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val usableSpace: (File) -> Long = { it.usableSpace },
) {
    private val mediaDir = File(baseDir, MEDIA_DIR)

    /** Fotos recién sacadas por la cámara, antes de importarse. Fuera de `media/` y del barrido rápido. */
    private val capturesDir = File(baseDir, CAPTURES_DIR)

    @Serializable
    data class ImportResult(
        val relativePath: String,
        val kind: Attachment.Kind,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: String,
    )

    /**
     * Copia el contenido de [uri] a `media/`. Las imágenes se re-codifican a WebP con pérdida
     * (lado mayor ≤ [IMAGE_MAX_SIDE_PX], calidad [IMAGE_QUALITY], orientación EXIF aplicada); si no
     * se pueden decodificar se guarda el original. El resto se copia tal cual.
     *
     * Se cancela cancelando la corrutina. [onProgress] recibe bytes copiados y total (si se conoce).
     *
     * @throws ImportException si falta espacio o no se puede leer el origen.
     */
    suspend fun import(
        uri: Uri,
        onProgress: (copiedBytes: Long, totalBytes: Long?) -> Unit = { _, _ -> },
    ): ImportResult = withContext(ioDispatcher) {
        val mimeType = resolveMimeType(uri)
        val kind = kindForMimeType(mimeType)
        val declaredSize = querySize(uri)
        val reencode = kind == Attachment.Kind.IMAGE && mimeType != "image/gif"
        // Re-codificar necesita espacio para el original y el re-codificado a la vez.
        val estimated = (declaredSize ?: 0L) * (if (reencode) 2 else 1)
        checkSpace(estimated)

        // mkdirs devuelve false si otra importación simultánea la acaba de crear: se verifica después.
        if (!mediaDir.mkdirs() && !mediaDir.isDirectory) throw IOException("Cannot create $mediaDir")
        val id = UUID.randomUUID().toString()
        val extension = extensionFor(mimeType, uri)
        // Nombres distintos: si el original ya es WebP, no debe pisarse con el re-codificado.
        val originalPart = File(mediaDir, "$id.original$PART_SUFFIX")
        val encodedPart = File(mediaDir, "$id.$IMAGE_EXTENSION$PART_SUFFIX")
        try {
            // Si se va a re-codificar, la copia es temporal: no hace falta fsync (se hace del resultado).
            val original = copyFrom(uri, originalPart, declaredSize, onProgress, sync = !reencode)
            if (reencode) {
                val encoded = reencodeImage(originalPart, encodedPart)
                if (encoded != null) {
                    originalPart.delete()
                    return@withContext commit(encodedPart, "$id.$IMAGE_EXTENSION", kind, IMAGE_MIME_TYPE, encoded)
                }
                log("Image not decodable, keeping original ($mimeType)")
                syncFile(originalPart)
            }
            commit(originalPart, "$id.$extension", kind, mimeType, original)
        } catch (t: Throwable) {
            originalPart.delete()
            encodedPart.delete()
            throw if (t.isNoSpaceLeft()) ImportException.InsufficientSpace(estimated, usableSpace(mediaDir), t) else t
        }
    }

    /** Archivo nuevo donde la cámara escribe una foto; se importa con [import] y se borra con [deleteCapture]. */
    suspend fun newCaptureFile(): File = withContext(ioDispatcher) {
        if (!capturesDir.mkdirs() && !capturesDir.isDirectory) throw IOException("Cannot create $capturesDir")
        File(capturesDir, "${UUID.randomUUID()}.jpg")
    }

    /** Archivo de captura por nombre (para retomar una sesión tras la muerte del proceso). */
    fun captureFile(name: String): File {
        val file = File(capturesDir, name)
        require(file.parentFile?.canonicalPath == capturesDir.canonicalPath) { "Not a capture: $name" }
        return file
    }

    suspend fun captureExists(file: File): Boolean = withContext(ioDispatcher) { file.isFile && file.length() > 0 }

    suspend fun deleteCapture(file: File): Boolean = withContext(ioDispatcher) {
        !file.exists() || file.delete()
    }

    /** Borra el archivo. Devuelve true si ya no existe. */
    suspend fun delete(relativePath: String): Boolean = withContext(ioDispatcher) {
        val file = fileFor(relativePath)
        !file.exists() || file.delete()
    }

    suspend fun exists(relativePath: String): Boolean = withContext(ioDispatcher) {
        fileFor(relativePath).isFile
    }

    /**
     * Devuelve true si el archivo existe en `media/` y su SHA-256 coincide con [expectedSha256]
     * (si [expectedSha256] es null alcanza con que exista y no esté vacío).
     */
    suspend fun hasMatchingFile(relativePath: String, expectedSha256: String?): Boolean = withContext(ioDispatcher) {
        val file = fileFor(relativePath)
        if (!file.isFile) return@withContext false
        if (expectedSha256 == null) return@withContext file.length() > 0
        sha256Of(file).equals(expectedSha256, ignoreCase = true)
    }

    /** Tamaño en bytes de un archivo de `media/`. */
    suspend fun length(relativePath: String): Long = withContext(ioDispatcher) { fileFor(relativePath).length() }

    /** Lee los bytes de un archivo de `media/` en [ioDispatcher]. */
    suspend fun readFile(relativePath: String, reader: suspend (InputStream) -> Unit) = withContext(ioDispatcher) {
        FileInputStream(fileFor(relativePath)).buffered(BUFFER_SIZE).use { input ->
            reader(input)
        }
    }

    /**
     * Restaura un archivo en [relativePath] verificando su [expectedSha256] antes de renombrarlo
     * atómicamente. Si el archivo ya existe con el mismo hash no lo reescribe y devuelve false;
     * si se escribió como nuevo devuelve true.
     *
     * @throws BackupException.CorruptBackup si el hash calculado no coincide con [expectedSha256].
     * @throws ImportException.InsufficientSpace si falta espacio en disco.
     */
    suspend fun restoreFile(
        relativePath: String,
        expectedSha256: String?,
        estimatedSize: Long?,
        write: suspend (OutputStream) -> Unit,
    ): Boolean = withContext(ioDispatcher) {
        val target = fileFor(relativePath)
        if (target.isFile) {
            val matches = if (expectedSha256 == null) {
                target.length() > 0
            } else {
                sha256Of(target).equals(expectedSha256, ignoreCase = true)
            }
            if (matches) return@withContext false
        }
        val estimated = estimatedSize ?: 0L
        checkSpace(estimated)
        if (!mediaDir.mkdirs() && !mediaDir.isDirectory) throw IOException("Cannot create $mediaDir")
        val part = File(mediaDir, "${target.name}.restore$PART_SUFFIX")
        try {
            val written = writeSynced(part) { out -> write(out) }
            if (expectedSha256 != null && !written.sha256.equals(expectedSha256, ignoreCase = true)) {
                throw BackupException.CorruptBackup(
                    "SHA-256 mismatch for $relativePath: expected $expectedSha256, got ${written.sha256}"
                )
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (t: Throwable) {
            part.delete()
            throw if (t.isNoSpaceLeft()) ImportException.InsufficientSpace(estimated, usableSpace(mediaDir), t) else t
        }
    }

    /** Abre un `InputStream` de [uri] en [ioDispatcher] (para importar un ZIP elegido por el usuario). */
    suspend fun <T> openInputStream(uri: Uri, reader: suspend (InputStream) -> T): T = withContext(ioDispatcher) {
        openSource(uri).use { reader(it) }
    }

    /** Abre un `OutputStream` de [uri] en [ioDispatcher] (para exportar un ZIP elegido por el usuario). */
    suspend fun openOutputStream(uri: Uri, writer: suspend (OutputStream) -> Unit) = withContext(ioDispatcher) {
        val out = try {
            context.contentResolver.openOutputStream(uri, "wt")
                ?: context.contentResolver.openOutputStream(uri)
        } catch (e: IOException) {
            throw ImportException.SourceUnreadable(e)
        } catch (e: SecurityException) {
            throw ImportException.SourceUnreadable(e)
        } ?: throw ImportException.SourceUnreadable(null)
        out.use { writer(it) }
    }

    /** Archivo de una ruta relativa de `media/`. Solo valida el texto de la ruta: no toca el disco. */
    fun fileFor(relativePath: String): File {
        val name = relativePath.removePrefix("$MEDIA_DIR/")
        require(name != relativePath && name.isNotEmpty() && name != ".." && name.none { it == '/' || it == '\\' }) {
            "Not a media path: $relativePath"
        }
        return File(mediaDir, name)
    }

    fun uriFor(relativePath: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fileFor(relativePath))

    /**
     * Borra los `.part` y los archivos que ninguna fila referencia. Solo toca archivos sin
     * modificar hace más de [SWEEP_MIN_AGE_MS], para no pisar una importación en curso
     * (archivo ya renombrado pero fila todavía sin insertar).
     *
     * @return cantidad de archivos borrados.
     */
    suspend fun sweep(referencedPaths: Set<String>, now: Long): Int = withContext(ioDispatcher) {
        var deleted = 0
        for (file in mediaDir.listFiles().orEmpty()) {
            if (!file.isFile || now - file.lastModified() < SWEEP_MIN_AGE_MS) continue
            val orphan = file.name.endsWith(PART_SUFFIX) || "$MEDIA_DIR/${file.name}" !in referencedPaths
            if (!orphan) continue
            if (file.delete()) deleted++ else log("Sweep could not delete ${file.name}")
        }
        // Capturas de sesiones de cámara abandonadas hace mucho.
        for (file in capturesDir.listFiles().orEmpty()) {
            if (now - file.lastModified() >= CAPTURE_MAX_AGE_MS && file.delete()) deleted++
        }
        deleted
    }

    // --- Internos ---

    private fun checkSpace(estimatedBytes: Long) {
        val required = estimatedBytes + FREE_SPACE_MARGIN_BYTES
        val available = usableSpace(mediaDir.parentFile ?: mediaDir)
        if (available < required) throw ImportException.InsufficientSpace(required, available)
    }

    private suspend fun copyFrom(
        uri: Uri,
        dest: File,
        totalBytes: Long?,
        onProgress: (Long, Long?) -> Unit,
        sync: Boolean,
    ): Written {
        val input = openSource(uri)
        return input.use { source ->
            writeSynced(dest, sync) { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    copied += read
                    onProgress(copied, totalBytes)
                }
            }
        }
    }

    private fun openSource(uri: Uri): InputStream =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (e: IOException) {
            throw ImportException.SourceUnreadable(e)
        } catch (e: SecurityException) {
            throw ImportException.SourceUnreadable(e)
        } ?: throw ImportException.SourceUnreadable(null)

    /**
     * Decodifica [source], aplica orientación EXIF, achica y escribe un WebP en [dest].
     * Devuelve null si la imagen no se puede decodificar (formato no soportado o sin memoria).
     */
    private suspend fun reencodeImage(source: File, dest: File): Written? {
        val bitmap = try {
            val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) decodeScaled(source) else decodeScaledLegacy(source)
            decoded?.let(::flattenAlpha) ?: return null
        } catch (e: OutOfMemoryError) {
            log("Out of memory decoding image", e)
            return null
        }
        currentCoroutineContext().ensureActive()
        return try {
            writeSynced(dest) { out ->
                if (!bitmap.compress(webpLossy(), IMAGE_QUALITY, out)) {
                    throw IOException("WebP compression failed")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun readExif(file: File): ExifInterface? =
        try {
            ExifInterface(file)
        } catch (e: IOException) {
            log("No EXIF data", e)
            null
        }

    /**
     * Android 9+: decodifica directo al tamaño final (sin cargar la imagen completa en memoria) y
     * aplica la orientación EXIF. Es lo que permite procesar varias fotos a la vez sin quedarse
     * sin memoria. Devuelve null si no se puede decodificar.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeScaled(source: File): Bitmap? =
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > IMAGE_MAX_SIDE_PX) {
                    val scale = IMAGE_MAX_SIDE_PX.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (e: IOException) {
            log("ImageDecoder could not decode image", e)
            null
        }

    /** Android 8: `BitmapFactory` con submuestreo, y orientación y escala a mano. */
    private fun decodeScaledLegacy(source: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, IMAGE_MAX_SIDE_PX)
        }
        val decoded = BitmapFactory.decodeFile(source.path, options) ?: return null
        return orientAndScale(decoded, readExif(source))
    }

    private fun orientAndScale(decoded: Bitmap, exif: ExifInterface?): Bitmap {
        val scale = IMAGE_MAX_SIDE_PX.toFloat() / max(decoded.width, decoded.height)
        val matrix = Matrix()
        if (exif?.isFlipped == true) matrix.postScale(-1f, 1f)
        exif?.rotationDegrees?.takeIf { it != 0 }?.let { matrix.postRotate(it.toFloat()) }
        if (scale < 1f) matrix.postScale(scale, scale)

        val transformed = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (transformed !== decoded) decoded.recycle()
        return transformed
    }

    private fun flattenAlpha(transformed: Bitmap): Bitmap {
        if (!transformed.hasAlpha()) return transformed

        // Las fotos de apuntes no necesitan transparencia: se compone sobre blanco (archivo más chico
        // y sin fondo negro al verla en apps que no manejan alfa).
        val opaque = Bitmap.createBitmap(transformed.width, transformed.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(transformed, 0f, 0f, null)
        }
        transformed.recycle()
        return opaque
    }

    private fun commit(part: File, finalName: String, kind: Attachment.Kind, mimeType: String, written: Written): ImportResult {
        Files.move(part.toPath(), File(mediaDir, finalName).toPath(), StandardCopyOption.ATOMIC_MOVE)
        return ImportResult("$MEDIA_DIR/$finalName", kind, mimeType, written.sizeBytes, written.sha256)
    }

    private fun resolveMimeType(uri: Uri): String {
        context.contentResolver.getType(uri)?.let { return it }
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: DEFAULT_MIME_TYPE
    }

    private fun extensionFor(mimeType: String, uri: Uri): String {
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)?.let { return it }
        val fromName = queryDisplayName(uri)?.substringAfterLast('.', "")?.lowercase()
        return fromName?.takeIf { it.isNotEmpty() && it.length <= 10 && it.all(Char::isLetterOrDigit) } ?: "bin"
    }

    private fun querySize(uri: Uri): Long? =
        queryColumn(uri, OpenableColumns.SIZE) { cursor, index -> cursor.getLong(index) }?.takeIf { it >= 0 }

    private fun queryDisplayName(uri: Uri): String? =
        queryColumn(uri, OpenableColumns.DISPLAY_NAME) { cursor, index -> cursor.getString(index) }

    private fun <T> queryColumn(uri: Uri, column: String, read: (android.database.Cursor, Int) -> T): T? {
        if (uri.scheme != "content") return null
        return try {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(column)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) read(cursor, index) else null
            }
        } catch (e: RuntimeException) {
            // La metadata es opcional: si el proveedor no la da, se sigue sin ella.
            log("Could not query $column", e)
            null
        }
    }

    private class Written(val sizeBytes: Long, val sha256: String)

    /** Escribe [dest] calculando su SHA-256 y hace fsync antes de cerrar. */
    private inline fun writeSynced(dest: File, sync: Boolean = true, write: (OutputStream) -> Unit): Written {
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(dest).use { file ->
            val out = DigestOutputStream(file.buffered(BUFFER_SIZE), digest)
            write(out)
            out.flush()
            if (sync) file.fd.sync()
        }
        return Written(dest.length(), digest.digest().toHex())
    }

    /** fsync de un archivo ya escrito (la copia temporal que termina siendo el archivo final). */
    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered(BUFFER_SIZE).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    companion object {
        const val MEDIA_DIR = "media"
        const val CAPTURES_DIR = "captures"
        const val CAPTURE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val PART_SUFFIX = ".part"
        /**
         * WebP 2400 px calidad 70: ~79 % menos que el JPEG 3000 px q85 anterior, con la letra
         * manuscrita igual de legible al hacer zoom (prueba con fotos reales, ver docs/PLAN.md).
         */
        const val IMAGE_MAX_SIDE_PX = 2400
        const val IMAGE_QUALITY = 70
        const val IMAGE_EXTENSION = "webp"
        const val IMAGE_MIME_TYPE = "image/webp"
        const val FREE_SPACE_MARGIN_BYTES = 50L * 1024 * 1024
        const val SWEEP_MIN_AGE_MS = 60L * 60 * 1000
        private const val BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_MIME_TYPE = "application/octet-stream"
    }
}

sealed class ImportException(message: String, cause: Throwable?) : IOException(message, cause) {
    class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long, cause: Throwable? = null) :
        ImportException("Not enough space: required $requiredBytes, available $availableBytes", cause)

    class SourceUnreadable(cause: Throwable?) : ImportException("Source cannot be read", cause)
}

fun kindForMimeType(mimeType: String?): Attachment.Kind {
    val mime = mimeType?.lowercase() ?: return Attachment.Kind.FILE
    return when {
        mime.startsWith("image/") -> Attachment.Kind.IMAGE
        mime.startsWith("video/") -> Attachment.Kind.VIDEO
        mime.startsWith("audio/") -> Attachment.Kind.AUDIO
        mime == "application/pdf" -> Attachment.Kind.PDF
        else -> Attachment.Kind.FILE
    }
}

/**
 * Mayor potencia de 2 que deja el lado mayor en al menos [maxSide] (el ajuste fino lo hace el
 * escalado posterior), así la decodificación usa la menor memoria posible sin perder calidad.
 */
fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    val longest = max(width, height)
    while (longest / (sample * 2) >= maxSide) sample *= 2
    return sample
}

private fun Throwable.isNoSpaceLeft(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if ((current as? ErrnoException)?.errno == OsConstants.ENOSPC) return true
        if (current.message?.contains("ENOSPC") == true) return true
        current = current.cause
    }
    return false
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** WebP con pérdida. Antes de Android 11 el formato `WEBP` es con pérdida si la calidad es menor a 100. */
@Suppress("DEPRECATION")
private fun webpLossy(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
