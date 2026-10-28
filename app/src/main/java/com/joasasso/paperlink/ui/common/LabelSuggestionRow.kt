package com.joasasso.paperlink.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joasasso.paperlink.R

/** Fila deslizable de etiquetas ya usadas; tocar una la elige. No ocupa lugar si no hay ninguna. */
@Composable
fun LabelSuggestionRow(
    labels: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return
    val description = stringResource(R.string.label_suggestions)
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        items(labels, key = { it }) { label ->
            SuggestionChip(
                onClick = { onPick(label) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(Icons.AutoMirrored.Outlined.Label, contentDescription = null) },
            )
        }
    }
}
