package com.joasasso.paperlink.ui.screens.txt

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.R
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TxtViewerScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val application = context.applicationContext as PaperLinkApp
    
    val saveSuccessMsg = stringResource(R.string.txt_save_success)
    
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    val savedStateHandle = (viewModelStoreOwner as? NavBackStackEntry)?.savedStateHandle
        ?: throw IllegalStateException("TxtViewerScreen debe estar dentro de un NavHost")

    val viewModel: TxtViewerViewModel = viewModel(
        factory = TxtViewerViewModel.provideFactory(application, savedStateHandle)
    )
    
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel.events) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is TxtViewerEvent.SaveSuccess -> {
                    Toast.makeText(context, saveSuccessMsg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    BackHandler {
        viewModel.saveChanges(onNavigateBack)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.code) },
                navigationIcon = {
                    IconButton(onClick = { viewModel.saveChanges(onNavigateBack) }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.txt_back_save_cd)
                        )
                    }
                },
                actions = {
                    if (uiState.isMarkdown) {
                        Button(
                            onClick = { viewModel.toggleEditMode() },
                            modifier = Modifier.padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (uiState.isEditMode) Icons.Default.Visibility else Icons.Default.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isEditMode) stringResource(R.string.txt_view_btn) else stringResource(R.string.txt_edit_btn),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                    Button(
                        onClick = { 
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.saveChanges({}) 
                        },
                        modifier = Modifier.padding(end = 8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.txt_save_btn),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.errorMessage != null -> {
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text(stringResource(R.string.txt_error_title)) },
                        text = { Text(uiState.errorMessage!!) },
                        confirmButton = {
                            TextButton(onClick = onNavigateBack) {
                                Text(stringResource(R.string.txt_exit_no_save_btn))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { viewModel.saveChanges(onNavigateBack) }) {
                                Text(stringResource(R.string.txt_retry_btn))
                            }
                        }
                    )
                }
                else -> {
                    if (uiState.isEditMode) {
                        TextField(
                            value = uiState.text,
                            onValueChange = { viewModel.onTextChanged(it) },
                            modifier = Modifier.fillMaxSize(),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge
                        )
                    } else {
                        MarkdownText(
                            text = uiState.text,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val annotatedString = parseMarkdown(text)
    Text(
        text = annotatedString,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium
    )
}

/**
 * Parser de Markdown ligero y nativo.
 * Maneja:
 * - Headings (#)
 * - Bold (**)
 * - Lists (-)
 */
fun parseMarkdown(text: String): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        lines.forEachIndexed { index, line ->
            when {
                // Headings
                line.startsWith("# ") -> {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Black, fontSize = 24.sp)) {
                        append(line.removePrefix("# "))
                    }
                }
                line.startsWith("## ") -> {
                    withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)) {
                        append(line.removePrefix("## "))
                    }
                }
                // List Items
                line.startsWith("- ") -> {
                    append("  •  ")
                    parseInlineStyles(line.removePrefix("- "))
                }
                // Normal Lines
                else -> {
                    parseInlineStyles(line)
                }
            }
            if (index < lines.size - 1) append("\n")
        }
    }
}

fun AnnotatedString.Builder.parseInlineStyles(text: String) {
    // Regex simple para negritas **texto**
    val boldRegex = Regex("""\*\*(.*?)\*\*""")
    var lastIndex = 0
    
    boldRegex.findAll(text).forEach { match ->
        append(text.substring(lastIndex, match.range.first))
        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
            append(match.groupValues[1])
        }
        lastIndex = match.range.last + 1
    }
    append(text.substring(lastIndex))
}
