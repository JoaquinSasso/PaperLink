package com.joasasso.paperlink.data.backup

import android.app.PendingIntent
import com.joasasso.paperlink.data.files.AttachmentStore
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.Link
import com.joasasso.paperlink.domain.CodeAlphabet
import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * Contenido serializable de un respaldo (tanto para el archivo `manifest.json` dentro del ZIP
 * como para el snapshot subido al final a Google Drive).
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val createdAt: Long,
    val links: List<BackupLink>,
    val attachments: List<BackupAttachment>,
) {
    /**
     * Verifica que el manifest sea de una versión soportada y que sus códigos, referencias
     * y rutas relativas sean válidos antes de tocar el disco o la base.
     */
    fun validate() {
        if (formatVersion < 1 || formatVersion > FORMAT_VERSION) {
            throw BackupException.InvalidArchive("Unsupported backup formatVersion: $formatVersion")
        }
        val codes = HashSet<String>(links.size)
        for (link in links) {
            if (!CodeAlphabet.isValid(link.code) || link.code != CodeAlphabet.normalize(link.code)) {
                throw BackupException.InvalidArchive("Invalid code in manifest: ${link.code}")
            }
            if (!codes.add(link.code)) {
                throw BackupException.InvalidArchive("Duplicate code in manifest: ${link.code}")
            }
        }
        val attachmentIds = HashSet<String>(attachments.size)
        for (att in attachments) {
            if (att.id.isBlank()) {
                throw BackupException.InvalidArchive("Blank attachment id in manifest")
            }
            if (!attachmentIds.add(att.id)) {
                throw BackupException.InvalidArchive("Duplicate attachment id in manifest: ${att.id}")
            }
            if (att.linkCode !in codes) {
                throw BackupException.InvalidArchive("Attachment ${att.id} references unknown code ${att.linkCode}")
            }
            when (att.kind) {
                Attachment.Kind.URL -> if (att.url.isNullOrBlank()) {
                    throw BackupException.InvalidArchive("URL attachment ${att.id} has no url")
                }
                Attachment.Kind.NOTE -> if (att.text.isNullOrBlank()) {
                    throw BackupException.InvalidArchive("NOTE attachment ${att.id} has no text")
                }
                else -> {
                    val path = att.relativePath
                        ?: throw BackupException.InvalidArchive("File attachment ${att.id} has no relativePath")
                    if (!isValidMediaPath(path)) {
                        throw BackupException.InvalidArchive("Invalid media path in manifest: $path")
                    }
                    if (att.sha256.isNullOrBlank()) {
                        throw BackupException.InvalidArchive("File attachment ${att.id} has no sha256")
                    }
                }
            }
        }
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MANIFEST_FILE_NAME = "manifest.json"

        fun isValidMediaPath(relativePath: String): Boolean {
            val prefix = "${AttachmentStore.MEDIA_DIR}/"
            if (!relativePath.startsWith(prefix)) return false
            val name = relativePath.removePrefix(prefix)
            return name.isNotEmpty() &&
                name != "." &&
                name != ".." &&
                !name.endsWith(AttachmentStore.PART_SUFFIX) &&
                name.none { it == '/' || it == '\\' }
        }
    }
}

@Serializable
data class BackupLink(
    val code: String,
    val label: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toEntity(): Link = Link(
        code = code,
        label = label,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = null,
    )
}

@Serializable
data class BackupAttachment(
    val id: String,
    val linkCode: String,
    val kind: Attachment.Kind,
    val relativePath: String? = null,
    val url: String? = null,
    val text: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val position: Int,
    val createdAt: Long,
) {
    fun toEntity(): Attachment = Attachment(
        id = id,
        linkCode = linkCode,
        kind = kind,
        relativePath = relativePath,
        url = url,
        text = text,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        position = position,
        createdAt = createdAt,
        deletedAt = null,
    )
}

fun Link.toBackup(): BackupLink = BackupLink(
    code = code,
    label = label,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun Attachment.toBackup(): BackupAttachment = BackupAttachment(
    id = id,
    linkCode = linkCode,
    kind = kind,
    relativePath = relativePath,
    url = url,
    text = text,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    position = position,
    createdAt = createdAt,
)

/** Progreso de una operación de respaldo o restauración. */
data class BackupProgress(
    val currentFiles: Int,
    val totalFiles: Int,
    /** En Drive: se está uniendo el respaldo remoto antes de subir. */
    val merging: Boolean = false,
)

/** Resultado de fusionar un respaldo sobre la base local. */
data class MergeCounts(
    val addedLinks: Int,
    val addedAttachments: Int,
)

sealed class BackupException(message: String, cause: Throwable? = null) : IOException(message, cause) {
    /** El archivo elegido no es un respaldo válido de PaperLink o no contiene `manifest.json`. */
    class InvalidArchive(message: String, cause: Throwable? = null) : BackupException(message, cause)

    /** Falta un archivo referenciado por el manifest o su hash SHA-256 no coincide. */
    class CorruptBackup(message: String, cause: Throwable? = null) : BackupException(message, cause)

    /** Drive requiere permiso del usuario o la sesión fue revocada. */
    class AuthRequired(
        val resolution: PendingIntent? = null,
        message: String = "Drive authorization required",
        cause: Throwable? = null,
    ) : BackupException(message, cause)

    /** No hay respaldo guardado en la cuenta de Google Drive. */
    class NoRemoteBackup(message: String = "No backup found in Google Drive") : BackupException(message)

    /** Fallo de red o respuesta inesperada del servidor de Drive. */
    class NetworkError(message: String, cause: Throwable? = null) : BackupException(message, cause)
}
