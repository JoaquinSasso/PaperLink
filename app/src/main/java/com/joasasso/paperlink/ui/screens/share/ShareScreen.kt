package com.joasasso.paperlink.ui.screens.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.domain.CodeAlphabet
import com.joasasso.paperlink.ui.theme.JetBrainsMono

/**
 * Destino de lo compartido: código nuevo o uno existente.
 *
 * @param onSaved código donde quedó guardado y si es nuevo.
 * @param onClose cancelar o cerrar tras un error.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(
    onSaved: (code: String, createdNew: Boolean) -> Unit,
    onClose: () -> Unit,
    viewModel: ShareViewModel = viewModel(factory = ShareViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var existingCode by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(state.savedCode) {
        state.savedCode?.let { onSaved(it, state.createdNew) }
    }
    LaunchedEffect(state.closed) {
        if (state.closed) onClose()
    }
    val message = state.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    BackHandler { viewModel.cancel() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                navigationIcon = {
                    IconButton(onClick = viewModel::cancel) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(summary(state), style = MaterialTheme.typography.titleMedium)

            when (val phase = state.phase) {
                is ShareViewModel.Phase.Failed -> {
                    Text(stringResource(phase.message), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.share_failed_nothing_saved))
                    Button(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
                is ShareViewModel.Phase.Copying -> {
                    Text(stringResource(R.string.share_copying, phase.fileIndex + 1, phase.fileCount))
                    val total = phase.progress?.totalBytes
                    if (phase.progress != null && total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (phase.progress.copiedBytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                ShareViewModel.Phase.Ready, ShareViewModel.Phase.Saving -> {
                    val enabled = phase == ShareViewModel.Phase.Ready
                    Button(onClick = viewModel::createNew, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.share_new_code))
                    }
                    HorizontalDivider()
                    Text(stringResource(R.string.share_or_existing), style = MaterialTheme.typography.bodyMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = existingCode,
                            onValueChange = { input ->
                                existingCode = CodeAlphabet.normalize(input).take(CodeAlphabet.CODE_LENGTH)
                            },
                            label = { Text(stringResource(R.string.share_existing_hint)) },
                            singleLine = true,
                            textStyle = TextStyle(fontFamily = JetBrainsMono, fontSize = 22.sp, textAlign = TextAlign.Center),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                autoCorrectEnabled = false,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { viewModel.addToExisting(existingCode) }),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { viewModel.addToExisting(existingCode) },
                            enabled = enabled && existingCode.length == CodeAlphabet.CODE_LENGTH,
                        ) {
                            Text(stringResource(R.string.share_add_to_existing))
                        }
                    }
                    if (!enabled) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun summary(state: ShareViewModel.UiState): String {
    val parts = buildList {
        if (state.fileCount > 0) add(pluralStringResource(R.plurals.share_files, state.fileCount, state.fileCount))
        if (state.linkCount > 0) add(pluralStringResource(R.plurals.share_links, state.linkCount, state.linkCount))
        if (state.hasNote) add(stringResource(R.string.share_note))
    }
    return parts.joinToString(" · ")
}
