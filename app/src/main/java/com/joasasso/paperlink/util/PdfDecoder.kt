package com.joasasso.paperlink.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import coil3.ImageLoader
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.asImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import java.io.File

/**
 * Custom Decoder para Coil 3 que renderiza la primera página de un PDF.
 * Descarga/copia el stream a un archivo temporal para poder usar PdfRenderer.
 */
class PdfDecoder(
    private val source: ImageSource,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult? = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("pdf_thumb", ".pdf", options.context.cacheDir)
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        
        try {
            // Copiamos el stream del PDF al archivo temporal
            // No usamos .use { } sobre input para no cerrar el source de Coil prematuramente
            val input = source.source()
            tempFile.outputStream().sink().buffer().use { output ->
                output.writeAll(input)
            }

            pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                return@withContext DecodeResult(
                    image = bitmap.asImage(),
                    isSampled = false
                )
            }
        } catch (_: Exception) {
            // Si falla, ThumbnailImage usará el fallback del gradiente
        } finally {
            renderer?.close()
            pfd?.close()
            if (tempFile.exists()) tempFile.delete()
        }
        null
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? {
            val isPdf = result.mimeType == "application/pdf" || 
                        result.source.fileOrNull()?.let { it.name.endsWith(".pdf", ignoreCase = true) } == true

            return if (isPdf) PdfDecoder(result.source, options) else null
        }
    }
}
