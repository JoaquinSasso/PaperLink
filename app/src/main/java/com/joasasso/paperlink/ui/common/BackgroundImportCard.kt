package com.joasasso.paperlink.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.preferences.PendingImport
import com.joasasso.paperlink.data.repository.BatchImporter

/**
 * Progreso de una carga en segundo plano o, si terminó con fallas, cuántos elementos no se guardaron.
 *
 * @param showCode mostrar el código (en la Home, donde puede haber cargas de varios códigos).
 */
@Composable
fun BackgroundImportCard(
    status: BatchImporter.Status,
    showCode: Boolean,
    onCancel: () -> Unit,
    onAcknowledge: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val failedOnly = !status.running
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (failedOnly) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val text = when {
                status.running && showCode -> stringResource(R.string.import_bg_progress_code, status.code, status.done, status.total)
                status.running -> stringResource(R.string.import_bg_progress, status.done, status.total)
                // No se guardó nada: el código existe (ya está anotado) y hay que volver a agregarlos.
                status.saved == 0 -> stringResource(R.string.import_bg_all_failed, status.code, status.total)
                showCode -> stringResource(R.string.import_bg_failed_code, status.code, status.failed, status.total)
                else -> stringResource(R.string.import_bg_failed, status.failed, status.total)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (status.running && status.done == 0) {
                // Todavía no terminó ninguno (por ejemplo, un video grande): barra sin porcentaje.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else if (status.running) {
                LinearProgressIndicator(
                    progress = { status.done.toFloat() / status.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (status.running) {
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                } else {
                    TextButton(onClick = onAcknowledge) { Text(stringResource(R.string.action_ok)) }
                }
            }
        }
    }
}

/** Aviso de una carga que se cortó porque la app se cerró a mitad. */
@Composable
fun InterruptedImportCard(pending: PendingImport, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.import_bg_interrupted, pending.code, pending.saved, pending.total),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        }
    }
}
