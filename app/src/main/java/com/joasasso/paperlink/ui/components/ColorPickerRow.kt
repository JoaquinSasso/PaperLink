package com.joasasso.paperlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.joasasso.paperlink.ui.theme.parseSubjectColor

/**
 * Fila horizontal scrolleable de círculos de color. El seleccionado lleva borde.
 * Reutilizado por la gestión de materias y por el alta de materia al vuelo en AddLink.
 */
@Composable
fun ColorPickerRow(
    colors: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        colors.forEach { hex ->
            val isSelected = hex.equals(selected, ignoreCase = true)
            Row(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(parseSubjectColor(hex))
                    .border(
                        width = if (isSelected) 3.dp else 0.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape
                    )
                    .clickable { onSelect(hex) }
            ) {}
        }
    }
}
