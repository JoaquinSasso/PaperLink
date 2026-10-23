package com.joasasso.paperlink.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.repository.ImportProgress

/** Progreso de una importación, con opción de cancelar. Se muestra mientras [progress] exista. */
@Composable
fun ImportProgressDialog(progress: ImportProgress?, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.import_progress_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (progress != null && progress.fileCount > 1) {
                    Text(stringResource(R.string.import_progress_file, progress.fileIndex + 1, progress.fileCount))
                }
                val total = progress?.totalBytes
                if (progress != null && total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (progress.copiedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
