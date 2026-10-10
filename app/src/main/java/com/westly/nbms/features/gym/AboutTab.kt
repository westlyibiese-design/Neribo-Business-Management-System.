package com.westly.nbms.features.gym

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms

internal const val ABOUT_LABEL = "Introductory text shown below the hero banner"
internal const val ABOUT_PLACEHOLDER = "Describe your fitness center — atmosphere, standout features, who it's for…"

/** About tab: one 5-line text; Save is enabled only when the text changed. */
@Composable
internal fun AboutTab(vm: GymContentViewModel, state: GymContentUiState) {
    val saved = state.content.about
    var text by remember(saved) { mutableStateOf(saved) }

    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AboutField(value = text, onValueChange = { text = it })
            NbmsButton(
                text = "Save",
                onClick = { vm.saveAbout(text) },
                loading = state.saving,
                enabled = GymFormRules.aboutChanged(saved, text) && !state.saving,
                leadingIcon = NbmsIcons.Check
            )
        }
    }
}

/** A text area that is five lines tall from the start (the shared text field is only one or two lines). */
@Composable
private fun AboutField(value: String, onValueChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Column {
        Text(ABOUT_LABEL, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            minLines = 5,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, if (focused) MaterialTheme.nbms.ring else MaterialTheme.nbms.inputBorder, MaterialTheme.shapes.small)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    if (value.isEmpty()) {
                        Text(ABOUT_PLACEHOLDER, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        )
    }
}
