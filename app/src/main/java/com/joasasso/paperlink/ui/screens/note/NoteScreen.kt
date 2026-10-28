package com.joasasso.paperlink.ui.screens.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.ui.theme.JetBrainsMono

/** Nota a pantalla completa, con el texto seleccionable. Arriba, el código y la etiqueta. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(
    onBack: () -> Unit,
    viewModel: NoteViewModel = viewModel(factory = NoteViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val spoken = stringResource(R.string.code_spoken, viewModel.code.toList().joinToString(" "))
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            viewModel.code,
                            fontFamily = JetBrainsMono,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { contentDescription = spoken },
                        )
                        val label = (state as? NoteViewModel.State.Loaded)?.label
                        if (!label.isNullOrBlank()) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            NoteViewModel.State.Loading -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            NoteViewModel.State.Missing -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                Text(stringResource(R.string.file_unavailable))
            }
            is NoteViewModel.State.Loaded -> SelectionContainer(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                Text(current.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
