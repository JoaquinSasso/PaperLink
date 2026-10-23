package com.joasasso.paperlink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Directorio temporal para un test, fuera de `filesDir/media` para no tocar datos reales
 * de la app instalada.
 */
class TestDir {
    val root: File = File(
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "test-${UUID.randomUUID()}",
    ).apply { mkdirs() }

    /** Simula el `filesDir` de la app. */
    val base: File = File(root, "files").apply { mkdirs() }

    /** Archivos de origen (lo que el usuario elegiría en la galería, etc.). */
    private val sources: File = File(root, "sources").apply { mkdirs() }

    val media: File get() = File(base, "media")

    fun mediaFiles(): List<File> = media.listFiles()?.toList().orEmpty()

    fun source(name: String, bytes: ByteArray): Uri =
        Uri.fromFile(File(sources, name).apply { writeBytes(bytes) })

    fun missingSource(name: String): Uri = Uri.fromFile(File(sources, name))

    /** Imagen con la mitad izquierda roja y la derecha azul (o transparente si [alpha]). */
    fun image(name: String, width: Int, height: Int, format: Bitmap.CompressFormat, alpha: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawRect(0f, 0f, width / 2f, height.toFloat(), Paint().apply { color = Color.RED })
            if (!alpha) drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), Paint().apply { color = Color.BLUE })
        }
        return File(sources, name).apply {
            outputStream().use { bitmap.compress(format, 95, it) }
            bitmap.recycle()
        }
    }

    fun delete() {
        root.deleteRecursively()
    }
}

fun File.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }
