package com.moontvplus.nativetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** Shared visual states for both native focus and the player's direction-key cursor. */
internal object TvStyle {
    val surface = Color(0xFF252B3D)
    val focusedSurface = Color(0xFF46516B)
    val selectedSurface = Color(0xFF6955AA)
    val focusedSelectedSurface = Color(0xFF8770C9)
    val disabledSurface = Color(0xFF202432)
    val secondaryText = Color(0xFFB8C3D9)
    val focusWidth = 3.dp

    fun container(focused: Boolean, selected: Boolean = false, enabled: Boolean = true): Color = when {
        !enabled -> disabledSurface
        focused && selected -> focusedSelectedSurface
        focused -> focusedSurface
        selected -> selectedSurface
        else -> surface
    }
}

internal fun Modifier.tvFocusBorder(focused: Boolean, shape: Shape): Modifier =
    border(BorderStroke(TvStyle.focusWidth, if (focused) Color.White else Color.Transparent), shape)

internal enum class CurrentMarker { EPISODE, SOURCE }

@Composable
internal fun CurrentStateMarker(marker: CurrentMarker) {
    // Draw the marks rather than depending on a TV font containing these glyphs.
    Canvas(Modifier.size(16.dp)) {
        if (marker == CurrentMarker.EPISODE) {
            val triangle = Path().apply {
                moveTo(size.width * .2f, size.height * .1f)
                lineTo(size.width * .9f, size.height * .5f)
                lineTo(size.width * .2f, size.height * .9f)
                close()
            }
            drawPath(triangle, Color.White)
        } else {
            val check = Path().apply {
                moveTo(size.width * .12f, size.height * .5f)
                lineTo(size.width * .4f, size.height * .78f)
                lineTo(size.width * .9f, size.height * .2f)
            }
            drawPath(check, Color.White,
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
internal fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    marker: CurrentMarker? = null,
    onFocused: () -> Unit = {},
    content: @Composable RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onFocused()
        }.semantics { this.selected = selected },
        shape = shape,
        border = BorderStroke(TvStyle.focusWidth, if (focused && enabled) Color.White else Color.Transparent),
        colors = ButtonDefaults.buttonColors(
            containerColor = TvStyle.container(focused, selected, enabled),
            contentColor = Color.White,
            disabledContainerColor = TvStyle.disabledSurface,
            disabledContentColor = Color(0xFF8993A7)
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            if (selected && marker != null) CurrentStateMarker(marker)
            content()
        }
    }
}

@Composable
internal fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.onFocusChanged { focused = it.isFocused }.tvFocusBorder(focused, shape),
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        shape = shape,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White, unfocusedLabelColor = TvStyle.secondaryText,
            focusedPlaceholderColor = TvStyle.secondaryText, unfocusedPlaceholderColor = TvStyle.secondaryText,
            focusedContainerColor = Color(0xFF283247), unfocusedContainerColor = Color(0xFF151B29),
            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color(0xFF66718A),
            cursorColor = Color.White
        )
    )
}
