package com.joasasso.paperlink.ui.screens.subjects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.Subject
import com.joasasso.paperlink.ui.components.ColorPickerRow
import com.joasasso.paperlink.ui.theme.SubjectColorPalette
import com.joasasso.paperlink.ui.theme.parseSubjectColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubjectsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SubjectsViewModel = viewModel(factory = SubjectsViewModel.Factory)
) {
    val subjects by viewModel.subjects.collectAsState()
    val dialog by viewModel.dialog.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.subjects_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::openAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.subjects_add))
            }
        }
    ) { padding ->
        if (subjects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.subjects_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(subjects, key = { it.id }) { subject ->
                    SubjectRow(
                        subject = subject,
                        onEdit = { viewModel.openEdit(subject) },
                        onDelete = { viewModel.delete(subject) }
                    )
                }
            }
        }
    }

    if (dialog.visible) {
        SubjectDialog(
            state = dialog,
            onNameChange = viewModel::onNameChange,
            onColorChange = viewModel::onColorChange,
            onConfirm = viewModel::save,
            onDismiss = viewModel::dismissDialog
        )
    }
}

@Composable
private fun SubjectRow(
    subject: Subject,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(parseSubjectColor(subject.colorHex))
            )
            Spacer(Modifier.size(16.dp))
            Text(
                text = subject.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.subjects_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.subjects_delete))
            }
        }
    }
}

@Composable
private fun SubjectDialog(
    state: SubjectDialogState,
    onNameChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (state.editingId == null) stringResource(R.string.subjects_add)
                else stringResource(R.string.subjects_edit)
            )
        },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.subjects_name_hint)) },
                    singleLine = true,
                    isError = state.error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.error != null) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.subjects_color),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(8.dp))
                ColorPickerRow(
                    colors = SubjectColorPalette,
                    selected = state.colorHex,
                    onSelect = onColorChange
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.subjects_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.subjects_cancel)) }
        }
    )
}
