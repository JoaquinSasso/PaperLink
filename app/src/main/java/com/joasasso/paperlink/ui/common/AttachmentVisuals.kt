package com.joasasso.paperlink.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.util.YouTubeUtils
import com.joasasso.paperlink.util.log
import java.io.File

fun Kind.icon(): ImageVector = when (this) {
    Kind.IMAGE -> Icons.Filled.Image
    Kind.VIDEO -> Icons.Filled.Movie
    Kind.AUDIO -> Icons.Filled.Audiotrack
    Kind.PDF -> Icons.Filled.PictureAsPdf
    Kind.FILE -> Icons.AutoMirrored.Filled.InsertDriveFile
    Kind.URL -> Icons.Filled.Link
    Kind.NOTE -> Icons.AutoMirrored.Filled.Notes
}

@StringRes
fun Kind.labelRes(): Int = when (this) {
    Kind.IMAGE -> R.string.kind_image
    Kind.VIDEO -> R.string.kind_video
    Kind.AUDIO -> R.string.kind_audio
    Kind.PDF -> R.string.kind_pdf
    Kind.FILE -> R.string.kind_file
    Kind.URL -> R.string.kind_link
    Kind.NOTE -> R.string.kind_note
}

/** Modelo de Coil para la miniatura, o null si se muestra solo el ícono. */
private fun thumbnailModel(attachment: Attachment, file: File?): Any? = when (attachment.kind) {
    Kind.IMAGE, Kind.VIDEO, Kind.PDF -> file
    Kind.URL -> attachment.url?.let(YouTubeUtils::getYouTubeThumbnail)
    else -> null
}

/** Miniatura de un elemento; si no hay imagen (o falla), el ícono de su tipo. */
@Composable
fun AttachmentThumbnail(attachment: Attachment, file: File?, modifier: Modifier = Modifier) {
    val icon = attachment.kind.icon()
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val model = thumbnailModel(attachment, file)
        if (model == null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        } else {
            val fallback = rememberVectorPainter(icon)
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                error = fallback,
                fallback = fallback,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Abre un archivo con otra app. @return false si no hay ninguna que lo abra. */
fun Context.openWithExternalApp(uri: Uri, mimeType: String?): Boolean =
    startViewIntent(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )

/** Abre un link en el navegador. @return false si no hay ninguno. */
fun Context.openWebLink(url: String): Boolean = startViewIntent(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

private fun Context.startViewIntent(intent: Intent): Boolean =
    try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        log("No app to open content", e)
        false
    }
