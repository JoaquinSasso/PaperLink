package com.joasasso.paperlink.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.ui.common.AddContentSheet
import com.joasasso.paperlink.ui.common.ImportProgressDialog

/** Home temporal de la reescritura (se reemplaza en la fase 3). */
@Composable
fun HomeScreen(
    onOpenCamera: () -> Unit,
    onOpenCode: (code: String, justCreated: Boolean) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val count by viewModel.linkCount.collectAsStateWithLifecycle()
    val lastCode by viewModel.lastCreatedCode.collectAsStateWithLifecycle()
    val created by viewModel.created.collectAsStateWithLifecycle()
    val importStatus by viewModel.importer.status.collectAsStateWithLifecycle()
    val messageRes by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(created) {
        created?.let {
            viewModel.onCreatedHandled()
            onOpenCode(it, true)
        }
    }
    val message = messageRes?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(
                text = stringResource(R.string.home_rewrite_notice),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            val current = count
            if (current == null) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = pluralStringResource(R.plurals.home_link_count, current, current),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Button(onClick = { showAddSheet = true }) { Text(stringResource(R.string.home_new_code)) }
            lastCode?.let { code ->
                OutlinedButton(onClick = { onOpenCode(code, false) }) {
                    Text(stringResource(R.string.home_last_code, code))
                }
            }
        }
    }

    AddContentSheet(
        visible = showAddSheet,
        onDismiss = { showAddSheet = false },
        onCamera = onOpenCamera,
        onSources = viewModel::create,
    )
    importStatus?.let { ImportProgressDialog(it.progress, onCancel = viewModel.importer::cancel) }
}
