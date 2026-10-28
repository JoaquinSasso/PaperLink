package com.joasasso.paperlink.ui.screens.code

import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.Attachment.Kind
import com.joasasso.paperlink.ui.common.AddContentSheet
import com.joasasso.paperlink.ui.common.AttachmentThumbnail
import com.joasasso.paperlink.ui.common.BackgroundImportCard
import com.joasasso.paperlink.ui.common.icon
import com.joasasso.paperlink.ui.common.labelRes
import com.joasasso.paperlink.ui.common.openWebLink
import com.joasasso.paperlink.ui.common.openWithExternalApp
import com.joasasso.paperlink.ui.theme.JetBrainsMono
import com.joasasso.paperlink.util.YouTubeUtils
import com.joasasso.paperlink.util.log

/**
 * Pantalla única de un código: se llega al abrirlo y al crearlo. Muestra el código, la etiqueta
 * y la grilla de elementos. Tocar abre: fotos en el visor, notas en su vista, el resto con otra
 * app. Mantener presionado entra al modo selección para borrar varios.
 *
 * @param showDone muestra "Listo" arriba (código recién creado o llegado compartiendo).
 * @param onBack se llama después de guardar la etiqueta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeScreen(
    showDone: Boolean,
    onBack: () -> Unit,
    onOpenPhoto: (code: String, attachmentId: String) -> Unit,
    onOpenNote: (code: String, attachmentId: String) -> Unit,
    onAddWithCamera: (code: String) -> Unit,
    onCodeDeleted: (code: String) -> Unit,
    viewModel: CodeViewModel = viewModel(factory = CodeViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val backgroundImport by viewModel.backgroundImport.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val selecting = ui.selected.isNotEmpty()

    LaunchedEffect(state) {
        if (state is CodeViewModel.State.Loaded && viewModel.shouldConfirm) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            viewModel.onConfirmed()
        }
    }
    LaunchedEffect(ui.done) {
        if (ui.done) onBack()
    }
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
    val deletedText = pluralStringResource(R.plurals.code_items_deleted, ui.deletedIds.size, ui.deletedIds.size)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(ui.deletedIds) {
        val ids = ui.deletedIds.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        // Se marca como atendido después de mostrarlo: hacerlo antes cambia la clave y cancela el snackbar.
        val result = snackbar.showSnackbar(deletedText, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(ids)
        viewModel.onUndoHandled(ids)
    }

    BackHandler {
        if (selecting) viewModel.clearSelection() else viewModel.onDone()
    }

    fun open(attachment: Attachment, unavailable: Boolean) {
        if (unavailable) {
            viewModel.showMessage(R.string.file_unavailable)
            return
        }
        val opened = when (attachment.kind) {
            Kind.IMAGE -> {
                onOpenPhoto(viewModel.code, attachment.id)
                true
            }
            Kind.NOTE -> {
                onOpenNote(viewModel.code, attachment.id)
                true
            }
            Kind.URL -> attachment.url?.let(context::openWebLink) ?: false
            else -> viewModel.uriFor(attachment)?.let { context.openWithExternalApp(it, attachment.mimeType) } ?: false
        }
        if (!opened) viewModel.showMessage(R.string.viewer_no_app)
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(pluralStringResource(R.plurals.code_selected_count, ui.selected.size, ui.selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.code_clear_selection))
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::deleteSelected) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.code_delete_selected))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = viewModel::onDone) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    },
                    actions = {
                        if (showDone) {
                            TextButton(onClick = viewModel::onDone) { Text(stringResource(R.string.code_done)) }
                        }
                        if (state is CodeViewModel.State.Loaded) {
                            OverflowMenu(onDeleteCode = { confirmDelete = true })
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting && state is CodeViewModel.State.Loaded) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.code_add)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when (val current = state) {
            CodeViewModel.State.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            CodeViewModel.State.Missing -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.error_code_not_found), textAlign = TextAlign.Center)
                Button(onClick = onBack) { Text(stringResource(R.string.code_done)) }
            }
            is CodeViewModel.State.Loaded -> {
                LaunchedEffect(Unit) { log("Code shown") }
                val attachments = current.content.link.attachments
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                        Header(
                            code = viewModel.code,
                            justCreated = viewModel.justCreated,
                            label = ui.label,
                            onLabelChange = viewModel::onLabelChange,
                            onLabelDone = { viewModel.saveLabel() },
                        )
                    }
                    backgroundImport?.let { status ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "import") {
                            BackgroundImportCard(
                                status = status,
                                showCode = false,
                                onCancel = viewModel::cancelBackgroundImport,
                                onAcknowledge = viewModel::acknowledgeBackgroundImport,
                            )
                        }
                    }
                    if (attachments.isEmpty() && backgroundImport == null) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                            Text(
                                stringResource(R.string.code_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp),
                            )
                        }
                    }
                    items(attachments, key = { it.id }) { attachment ->
                        val unavailable = attachment.id in current.content.unavailableIds
                        AttachmentTile(
                            attachment = attachment,
                            unavailable = unavailable,
                            selecting = selecting,
                            selected = attachment.id in ui.selected,
                            viewModel = viewModel,
                            onClick = {
                                if (selecting) viewModel.toggleSelection(attachment.id) else open(attachment, unavailable)
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleSelection(attachment.id)
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.code_delete_title, viewModel.code)) },
            text = { Text(stringResource(R.string.code_delete_text)) },
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
        onCamera = { viewModel.saveLabel { onAddWithCamera(viewModel.code) } },
        onSources = viewModel::add,
    )
}

@Composable
private fun OverflowMenu(onDeleteCode: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.code_more_options))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.code_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDeleteCode()
                },
            )
        }
    }
}

@Composable
private fun Header(
    code: String,
    justCreated: Boolean,
    label: String,
    onLabelChange: (String) -> Unit,
    onLabelDone: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val spoken = stringResource(R.string.code_spoken, code.toList().joinToString(" "))
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = code,
            fontFamily = JetBrainsMono,
            fontWeight = FontWeight.Bold,
            fontSize = 64.sp,
            letterSpacing = 4.sp,
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
        )
        if (justCreated) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.code_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        OutlinedTextField(
            value = label,
            onValueChange = onLabelChange,
            label = { Text(stringResource(R.string.code_label_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                onLabelDone()
            }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Elemento de la grilla: miniatura cuadrada con lo justo para reconocerlo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AttachmentTile(
    attachment: Attachment,
    unavailable: Boolean,
    selecting: Boolean,
    selected: Boolean,
    viewModel: CodeViewModel,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(12.dp)
    val kindLabel = stringResource(attachment.kind.labelRes())
    val description = if (unavailable) "$kindLabel. ${stringResource(R.string.file_unavailable)}" else kindLabel
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.code_select_action),
            )
            .semantics {
                contentDescription = description
                if (selecting) this.selected = selected
            },
    ) {
        when {
            unavailable -> CenteredIconLabel(
                icon = { Icon(Icons.Filled.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                text = stringResource(R.string.file_unavailable),
                color = MaterialTheme.colorScheme.error,
            )
            attachment.kind == Kind.NOTE -> Text(
                text = attachment.text.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxSize().padding(10.dp),
            )
            attachment.kind == Kind.IMAGE -> Thumbnail(attachment, viewModel)
            attachment.kind == Kind.VIDEO -> {
                Thumbnail(attachment, viewModel)
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(40.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape).padding(6.dp),
                )
            }
            attachment.kind == Kind.PDF -> {
                Thumbnail(attachment, viewModel)
                KindBadge(attachment.kind, kindLabel)
            }
            attachment.kind == Kind.URL -> {
                val url = attachment.url.orEmpty()
                val host = Uri.parse(url).host?.removePrefix("www.") ?: url
                if (YouTubeUtils.getYouTubeThumbnail(url) != null) {
                    Thumbnail(attachment, viewModel)
                    KindBadge(attachment.kind, host)
                } else {
                    CenteredIconLabel(
                        icon = { Icon(attachment.kind.icon(), contentDescription = null) },
                        text = host,
                    )
                }
            }
            else -> CenteredIconLabel(
                icon = { Icon(attachment.kind.icon(), contentDescription = null) },
                text = buildString {
                    append(kindLabel)
                    attachment.sizeBytes?.let { append(" · ").append(Formatter.formatShortFileSize(context, it)) }
                },
            )
        }
        if (selecting) {
            Icon(
                imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

@Composable
private fun Thumbnail(attachment: Attachment, viewModel: CodeViewModel) {
    AttachmentThumbnail(attachment, viewModel.fileFor(attachment), Modifier.fillMaxSize())
}

@Composable
private fun BoxScope.KindBadge(kind: Kind, text: String) {
    Row(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(6.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(kind.icon(), contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CenteredIconLabel(
    icon: @Composable () -> Unit,
    text: String,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
