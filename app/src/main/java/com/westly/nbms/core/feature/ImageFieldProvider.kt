package com.westly.nbms.core.feature

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Phase 30 binds one implementation (`@Binds @IntoSet`) that uploads to Supabase Storage.
 * Until then the set is empty and forms use a plain "Image URLs (one per line)" text field.
 *
 * Declared here exactly as in the project contract (A.6.4). Phase 11 adds this file because Phase 3 did not create it.
 */
interface ImageFieldProvider {
    @Composable
    fun MultiImageField(
        label: String,
        folder: String,
        urls: List<String>,
        onChange: (List<String>) -> Unit,
        modifier: Modifier = Modifier
    )

    @Composable
    fun SingleImageField(
        label: String,
        folder: String,
        url: String?,
        onChange: (String?) -> Unit,
        modifier: Modifier = Modifier
    )
}
