package com.westly.nbms.features.rooms

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.westly.nbms.core.design.NbmsTextField

/** "One URL per line" text to a clean list: each line trimmed, blank lines dropped. */
internal fun parseImageUrls(text: String): List<String> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Fallback image input used until the image-upload phase exists: a multi-line box where each line is one image URL.
 * [onChange] always receives the trimmed list without blank lines.
 */
@Composable
fun ImageUrlListField(
    label: String,
    urls: List<String>,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    // The raw text is kept here so a new empty line is not removed while the person is still typing.
    var text by rememberSaveable { mutableStateOf(urls.joinToString("\n")) }

    // If the list is changed from outside (not by typing here), show the new list.
    LaunchedEffect(urls) {
        if (urls != parseImageUrls(text)) text = urls.joinToString("\n")
    }

    NbmsTextField(
        value = text,
        onValueChange = {
            text = it
            onChange(parseImageUrls(it))
        },
        label = label,
        modifier = modifier,
        placeholder = "https://…",
        keyboardType = KeyboardType.Uri,
        singleLine = false
    )
}
