package com.joasasso.paperlink.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joasasso.paperlink.R
import com.joasasso.paperlink.domain.CodeAlphabet
import com.joasasso.paperlink.ui.theme.JetBrainsMono

/**
 * Mayúsculas, solo letras y dígitos, a lo sumo 4. Se aplica como transformación de entrada
 * (no en `onValueChange`) para no desincronizarse con el texto que el teclado está componiendo:
 * cambiarlo desde afuera hacía perder caracteres al escribir rápido.
 */
private object CodeInputTransformation : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val current = asCharSequence().toString()
        val sanitized = CodeAlphabet.sanitizeInput(current)
        if (sanitized != current) replace(0, length, sanitized)
    }
}

/**
 * Campo de código: 4 cajas grandes en JetBrains Mono. Por debajo es un único campo de texto,
 * así funcionan pegar, el teclado y TalkBack ("Código, 2 de 4 caracteres").
 */
@Composable
fun CodeInputField(
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val description = stringResource(R.string.code_input_cd)
    val progress = stringResource(R.string.code_input_state, value.length, CodeAlphabet.CODE_LENGTH)
    val errorText = stringResource(R.string.code_input_error_cd)
    val state = rememberTextFieldState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    // Lo escrito sube al ViewModel; lo que el ViewModel cambia (por ejemplo, vaciarlo) baja al campo.
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { latestOnValueChange(it) }
    }
    LaunchedEffect(value) {
        if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value)
    }

    BasicTextField(
        state = state,
        inputTransformation = CodeInputTransformation,
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier.semantics {
            contentDescription = description
            stateDescription = progress
            if (isError) error(errorText)
        },
        decorator = { innerTextField ->
            Box(contentAlignment = Alignment.Center) {
                // El campo real queda invisible: las cajas muestran el contenido.
                Box(Modifier.alpha(0f)) { innerTextField() }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(CodeAlphabet.CODE_LENGTH) { index ->
                        val char = value.getOrNull(index)
                        val current = focused && index == value.length.coerceAtMost(CodeAlphabet.CODE_LENGTH - 1)
                        val color = when {
                            isError -> MaterialTheme.colorScheme.error
                            current -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        }
                        Box(
                            modifier = Modifier
                                .size(width = 60.dp, height = 76.dp)
                                .border(if (current || isError) 2.dp else 1.dp, color, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = char?.toString().orEmpty(),
                                fontFamily = JetBrainsMono,
                                fontWeight = FontWeight.Bold,
                                fontSize = 40.sp,
                                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        },
    )
}
