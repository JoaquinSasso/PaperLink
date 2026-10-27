package com.joasasso.paperlink.ui.screens.code

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.ui.common.AddContentSheet
import com.joasasso.paperlink.ui.common.BackgroundImportCard
import com.joasasso.paperlink.ui.theme.JetBrainsMono

/**
 * Muestra un código ya guardado: el momento en que el usuario lo copia al papel.
 */
@Composable
fun CodeScreen(
    onDone: () -> Unit,
    onAddWithCamera: (code: String) -> Unit,
    viewModel: CodeViewModel = viewModel(factory = CodeViewModel.Factory),
) {
    val link by viewModel.link.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backgroundImport by viewModel.backgroundImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(link) {
        if (link is CodeViewModel.LinkState.Loaded && viewModel.shouldConfirm) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            viewModel.onConfirmed()
        }
    }
    LaunchedEffect(state.done) {
        if (state.done) onDone()
    }
    val message = state.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        when (val current = link) {
            CodeViewModel.LinkState.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            CodeViewModel.LinkState.Missing -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_code_not_found), textAlign = TextAlign.Center)
                Button(onClick = onDone) { Text(stringResource(R.string.code_done)) }
            }
            is CodeViewModel.LinkState.Loaded -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.code_title), style = MaterialTheme.typography.titleMedium)
                val spoken = stringResource(R.string.code_spoken, viewModel.code.toList().joinToString(" "))
                Text(
                    text = viewModel.code,
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 88.sp,
                    letterSpacing = 6.sp,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
                )
                Text(
                    text = stringResource(R.string.code_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                val count = current.link.attachments.size
                Text(
                    text = pluralStringResource(R.plurals.code_attachment_count, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                backgroundImport?.let { status ->
                    BackgroundImportCard(
                        status = status,
                        showCode = false,
                        onCancel = viewModel::cancelBackgroundImport,
                        onAcknowledge = viewModel::acknowledgeBackgroundImport,
                    )
                }
                OutlinedTextField(
                    value = state.label,
                    onValueChange = viewModel::onLabelChange,
                    label = { Text(stringResource(R.string.code_label_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus()
                        viewModel.saveLabel()
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { showAddSheet = true }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.code_add_more))
                    }
                    Button(onClick = viewModel::onDone, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.code_done))
                    }
                }
            }
        }
    }

    AddContentSheet(
        visible = showAddSheet,
        onDismiss = { showAddSheet = false },
        onCamera = { viewModel.saveLabel { onAddWithCamera(viewModel.code) } },
        onSources = viewModel::add,
    )
}
