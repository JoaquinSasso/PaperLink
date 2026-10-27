package com.joasasso.paperlink.ui.screens.detail

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.ui.common.AddContentSheet
import com.joasasso.paperlink.ui.common.AttachmentThumbnail
import com.joasasso.paperlink.ui.common.BackgroundImportCard
import com.joasasso.paperlink.ui.common.labelRes
import com.joasasso.paperlink.ui.theme.JetBrainsMono

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    onBack: () -> Unit,
    onOpenAttachment: (code: String, attachmentId: String) -> Unit,
    onAddWithCamera: (code: String) -> Unit,
    onCodeDeleted: (code: String) -> Unit,
    viewModel: DetailViewModel = viewModel(factory = DetailViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val backgroundImport by viewModel.backgroundImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(ui.codeDeleted) {
        if (ui.codeDeleted) onCodeDeleted(viewModel.code)
    }
    val message = ui.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    val deletedText = stringResource(R.string.detail_item_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(ui.deletedAttachmentId) {
        val id = ui.deletedAttachmentId ?: return@LaunchedEffect
        // Se marca como atendido después de mostrarlo: hacerlo antes cambia la clave y cancela el snackbar.
        val result = snackbar.showSnackbar(deletedText, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteAttachment(id)
        viewModel.onAttachmentUndoHandled(id)
    }

    val spoken = stringResource(R.string.code_spoken, viewModel.code.toList().joinToString(" "))
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        viewModel.code,
                        fontFamily = JetBrainsMono,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.detail_delete_code))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.code_add_more)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            DetailViewModel.State.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            DetailViewModel.State.Missing -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(stringResource(R.string.error_code_not_found))
            }
            is DetailViewModel.State.Loaded -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                backgroundImport?.let { status ->
                    item {
                        BackgroundImportCard(
                            status = status,
                            showCode = false,
                            onCancel = viewModel::cancelBackgroundImport,
                            onAcknowledge = viewModel::acknowledgeBackgroundImport,
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = ui.label,
                        onValueChange = viewModel::onLabelChange,
                        label = { Text(stringResource(R.string.code_label_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                val attachments = current.content.link.attachments
                if (attachments.isEmpty()) {
                    item { Text(stringResource(R.string.viewer_empty), modifier = Modifier.padding(16.dp)) }
                }
                items(attachments, key = { it.id }) { attachment ->
                    AttachmentRow(
                        attachment = attachment,
                        unavailable = attachment.id in current.content.unavailableIds,
                        viewModel = viewModel,
                        onClick = { onOpenAttachment(viewModel.code, attachment.id) },
                        onDelete = { viewModel.deleteAttachment(attachment) },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_code_title, viewModel.code)) },
            text = { Text(stringResource(R.string.detail_delete_code_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteCode()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    AddContentSheet(
        visible = showAddSheet,
        onDismiss = { showAddSheet = false },
        onCamera = { onAddWithCamera(viewModel.code) },
        onSources = viewModel::add,
    )
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    unavailable: Boolean,
    viewModel: DetailViewModel,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val title = when (attachment.kind) {
        Kind.URL -> attachment.url.orEmpty()
        Kind.NOTE -> attachment.text.orEmpty()
        else -> stringResource(attachment.kind.labelRes())
    }
    val subtitle = when {
        unavailable -> stringResource(R.string.file_unavailable)
        attachment.sizeBytes != null -> Formatter.formatShortFileSize(context, attachment.sizeBytes)
        else -> stringResource(attachment.kind.labelRes())
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AttachmentThumbnail(attachment, viewModel.fileFor(attachment), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.detail_delete_item))
        }
    }
}
