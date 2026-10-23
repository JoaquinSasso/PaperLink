package com.joasasso.paperlink.ui.common

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.domain.normalizeUrl

private enum class TextInput { LINK, NOTE }

/**
 * Opciones para agregar contenido: cámara, fotos y videos (Photo Picker, sin permisos),
 * archivos, link y nota. Los selectores se registran fuera de la hoja para que sigan vivos
 * cuando la hoja se cierra.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContentSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onCamera: () -> Unit,
    onSources: (List<AttachmentSource>) -> Unit,
) {
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onSources(uris.map { AttachmentSource.Content(it) })
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onSources(uris.map { AttachmentSource.Content(it) })
    }
    var textInput by rememberSaveable { mutableStateOf<TextInput?>(null) }

    if (visible) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            val options = listOf<Triple<ImageVector, Int, () -> Unit>>(
                Triple(Icons.Filled.PhotoCamera, R.string.add_option_camera, onCamera),
                Triple(Icons.Filled.PhotoLibrary, R.string.add_option_media) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                Triple(Icons.Filled.AttachFile, R.string.add_option_files) { pickFiles.launch(arrayOf("*/*")) },
                Triple(Icons.Filled.Link, R.string.add_option_link) { textInput = TextInput.LINK },
                Triple(Icons.AutoMirrored.Filled.Notes, R.string.add_option_note) { textInput = TextInput.NOTE },
            )
            for ((icon, label, action) in options) {
                ListItem(
                    headlineContent = { Text(stringResource(label)) },
                    leadingContent = { Icon(icon, contentDescription = null) },
                    modifier = Modifier.clickable {
                        onDismiss()
                        action()
                    },
                )
            }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 8.dp))
        }
    }

    when (textInput) {
        TextInput.LINK -> TextInputDialog(
            title = R.string.add_link_title,
            label = R.string.add_link_label,
            singleLine = true,
            keyboardType = KeyboardType.Uri,
            transform = ::normalizeUrl,
            invalidMessage = R.string.add_link_invalid,
            onConfirm = { url ->
                textInput = null
                onSources(listOf(AttachmentSource.WebUrl(url)))
            },
            onDismiss = { textInput = null },
        )
        TextInput.NOTE -> TextInputDialog(
            title = R.string.add_note_title,
            label = R.string.add_note_label,
            singleLine = false,
            keyboardType = KeyboardType.Text,
            transform = { it.trim().ifEmpty { null } },
            invalidMessage = null,
            onConfirm = { text ->
                textInput = null
                onSources(listOf(AttachmentSource.Note(text)))
            },
            onDismiss = { textInput = null },
        )
        null -> Unit
    }
}

/** Diálogo con un campo de texto. [transform] valida y normaliza; null = inválido. */
@Composable
private fun TextInputDialog(
    title: Int,
    label: Int,
    singleLine: Boolean,
    keyboardType: KeyboardType,
    transform: (String) -> String?,
    invalidMessage: Int?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var showInvalid by rememberSaveable { mutableStateOf(false) }
    val confirm = {
        val value = transform(text)
        if (value != null) onConfirm(value) else showInvalid = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    showInvalid = false
                },
                label = { Text(stringResource(label)) },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                isError = showInvalid,
                supportingText = if (showInvalid && invalidMessage != null) {
                    { Text(stringResource(invalidMessage)) }
                } else null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
