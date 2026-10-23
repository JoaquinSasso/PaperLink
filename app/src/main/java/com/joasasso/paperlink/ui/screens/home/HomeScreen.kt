package com.joasasso.paperlink.ui.screens.home

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Attachment
import com.joasasso.paperlink.data.local.LinkWithAttachments
import com.joasasso.paperlink.ui.common.AddContentSheet
import com.joasasso.paperlink.ui.common.AttachmentThumbnail
import com.joasasso.paperlink.ui.common.CodeInputField
import com.joasasso.paperlink.ui.common.ImportProgressDialog
import com.joasasso.paperlink.ui.theme.JetBrainsMono
import java.io.File

/**
 * Home: campo de código arriba (abre al cuarto carácter), búsqueda por etiqueta y recientes.
 *
 * @param deletedCode código recién borrado desde su detalle, para ofrecer "Deshacer".
 */
@Composable
fun HomeScreen(
    onOpenCode: (code: String) -> Unit,
    onCodeCreated: (code: String) -> Unit,
    onOpenCamera: () -> Unit,
    deletedCode: String?,
    onDeletedCodeHandled: (code: String) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val code by viewModel.code.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val lookup by viewModel.lookup.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val results by viewModel.searchResults.collectAsStateWithLifecycle()
    val importStatus by viewModel.importer.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAddSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(events.open) {
        events.open?.let {
            viewModel.onOpenHandled()
            onOpenCode(it)
        }
    }
    LaunchedEffect(events.created) {
        events.created?.let {
            viewModel.onCreatedHandled()
            onCodeCreated(it)
        }
    }
    val message = events.message?.let { stringResource(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onMessageShown()
        }
    }
    val deletedMessage = deletedCode?.let { stringResource(R.string.home_code_deleted, it) }
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(deletedCode) {
        if (deletedCode == null || deletedMessage == null) return@LaunchedEffect
        // Se marca como atendido después de mostrarlo: hacerlo antes cambia la clave y cancela el snackbar.
        val result = snackbar.showSnackbar(deletedMessage, actionLabel = undoLabel, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete(deletedCode)
        onDeletedCodeHandled(deletedCode)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new_code)) },
            )
        },
    ) { padding ->
        val list = results ?: recent
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_enter_code), style = MaterialTheme.typography.titleMedium)
                    CodeInputField(
                        value = code,
                        onValueChange = viewModel::onCodeChange,
                        isError = lookup is HomeViewModel.Lookup.NotFound,
                    )
                    (lookup as? HomeViewModel.Lookup.NotFound)?.let { NotFoundHint(it, onSuggestion = viewModel::open) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::onQueryChange,
                        placeholder = { Text(stringResource(R.string.home_search_label)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = if (query.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.onQueryChange("") }) {
                                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.home_search_clear))
                                }
                            }
                        } else null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Text(
                    text = stringResource(if (results != null) R.string.home_results else R.string.home_recent),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            when {
                list == null -> item {
                    CircularProgressIndicator(Modifier.padding(24.dp))
                }
                list.isEmpty() -> item {
                    Text(
                        text = stringResource(if (results != null) R.string.home_no_results else R.string.home_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
                else -> items(list, key = { it.link.code }) { item ->
                    LinkRow(item, fileFor = viewModel::fileFor, onClick = { viewModel.open(item.link.code) })
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

@Composable
private fun NotFoundHint(lookup: HomeViewModel.Lookup.NotFound, onSuggestion: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = if (lookup.hasInvalidChars) stringResource(R.string.home_invalid_chars)
            else stringResource(R.string.home_code_not_found, lookup.code),
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
        if (lookup.suggestions.isNotEmpty()) {
            Text(stringResource(R.string.home_did_you_mean), style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (suggestion in lookup.suggestions) {
                    val spoken = stringResource(R.string.code_spoken, suggestion.toList().joinToString(" "))
                    SuggestionChip(
                        onClick = { onSuggestion(suggestion) },
                        label = { Text(suggestion, fontFamily = JetBrainsMono, fontSize = 18.sp) },
                        modifier = Modifier.semantics { contentDescription = spoken },
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkRow(
    item: LinkWithAttachments,
    fileFor: (Attachment) -> File?,
    onClick: () -> Unit,
) {
    val first = item.attachments.firstOrNull()
    val count = item.attachments.size
    val date = DateUtils.getRelativeTimeSpanString(
        item.link.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    val spoken = stringResource(R.string.code_spoken, item.link.code.toList().joinToString(" "))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (first != null) {
            AttachmentThumbnail(first, fileFor(first), Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
        } else {
            Spacer(Modifier.size(56.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = item.link.code,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            item.link.label?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                text = pluralStringResource(R.plurals.home_row_details, count, count, date),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
