package com.joasasso.paperlink.ui.screens.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.joasasso.paperlink.R
import com.joasasso.paperlink.data.local.PaperLink
import com.joasasso.paperlink.ui.theme.JetBrainsMono

/**
 * Pantalla Principal (HomeScreen).
 *
 * NOTA: en esta entrega (Organización Parte 1) solo se agregó la TopAppBar con
 * acceso a la gestión de materias. El cuerpo (campo de código + recientes) queda
 * igual; el toggle Código | Buscar y los chips de filtro llegan en la Parte 2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToAdd: () -> Unit,
    onNavigateToDetail: (String) -> Unit,
    onNavigateToSubjects: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsState()
    val recentLinks by viewModel.recentLinks.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onNavigateToSubjects) {
                        Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = stringResource(R.string.home_subjects_action)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.home_fab_content_description)
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(0.3f))

            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                label = { Text(stringResource(R.string.home_search_hint)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = JetBrainsMono,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
                trailingIcon = {
                    if (uiState.isQueryValid) {
                        IconButton(onClick = { onNavigateToDetail(uiState.searchQuery) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.cd_search)
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(0.2f))

            Text(
                text = stringResource(R.string.home_recent_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (recentLinks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_empty_state),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recentLinks) { link ->
                        RecentLinkItem(link = link, onClick = { onNavigateToDetail(link.code) })
                    }
                }
            }
        }
    }
}

@Composable
fun RecentLinkItem(link: PaperLink, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = link.code,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(80.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = link.displayName ?: link.contentType.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                if (!link.note.isNullOrBlank()) {
                    Text(
                        text = link.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
