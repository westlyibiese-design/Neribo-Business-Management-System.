package com.westly.nbms.features.cms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.westly.nbms.core.data.Resource
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField
import com.westly.nbms.core.design.ToastController
import com.westly.nbms.core.design.ToastType
import com.westly.nbms.core.design.nbmsPageTitleStyle
import com.westly.nbms.core.feature.ImageFieldProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val MSG_BANNER_LOAD_FAILED = "This section's content failed to load."
internal const val TITLE_PUBLISHED = "Published"

/** What one open page banner editor shows. [draft] is what is typed, [saved] the last copy read or saved. */
data class PageHeroEditorState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val draft: PageHeroContent = PageHeroContent(),
    val saved: PageHeroContent = PageHeroContent(),
    val saving: Boolean = false
) {
    val changed: Boolean get() = draft.trimmed() != saved.trimmed()
}

/**
 * One page banner (`cms_content/{section.docId}`): reads its own document live and saves it whole with
 * [CmsSource.saveObject]. UI-free; independent of every other tab. [scope] is the scope the document is watched in
 * (cancel it to stop watching). A save that cannot go ahead (load failed, still loading, already saving) writes nothing and
 * calls `onDone(false)`.
 */
class PageHeroEditorModel(
    private val source: CmsSource,
    private val toast: ToastController,
    val section: PageHeroSection,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(PageHeroEditorState())
    val state: StateFlow<PageHeroEditorState> = _state.asStateFlow()

    private val guard = CmsSaveGuard()

    init {
        scope.launch {
            source.observeDoc(section.docId)
                .catch { _state.update { s -> s.copy(loading = false, loadFailed = true) } }
                .collect { resource ->
                    when (resource) {
                        is Resource.Loading -> Unit
                        is Resource.Error -> _state.update { it.copy(loading = false, loadFailed = true) }
                        is Resource.Success -> _state.update { s ->
                            val live = PageHeroContent.parse(resource.data.data)
                            // text the person has typed is kept; an untouched form follows the live copy
                            s.copy(loading = false, loadFailed = false, draft = if (s.changed) s.draft else live, saved = live)
                        }
                    }
                }
        }
    }

    fun update(transform: (PageHeroContent) -> PageHeroContent) {
        _state.update { it.copy(draft = transform(it.draft)) }
    }

    /** Saves `{title, subtitle, description, buttonText, buttonLink, image}` (all trimmed text) and audits `cms_updated:{docId}`. */
    fun save(onDone: (Boolean) -> Unit = {}) {
        val s = _state.value
        if (s.loadFailed) {
            toast.show(message = MSG_CMS_LOAD_FAILED_SAVE, type = ToastType.Error, title = TITLE_CANT_SAVE_YET)
            onDone(false)
            return
        }
        if (s.loading) {
            toast.show(message = MSG_CMS_STILL_LOADING, type = ToastType.Error, title = TITLE_NOT_SAVED)
            onDone(false)
            return
        }
        if (!guard.tryStart()) {
            onDone(false)
            return
        }
        _state.update { it.copy(saving = true) }
        val clean = s.draft.trimmed()
        scope.launch {
            var ok = false
            try {
                source.saveObject(section.docId, clean.toMap())
                _state.update { it.copy(saved = clean) }
                toast.show(message = CmsFormRules.bannerPublishedMessage(section), type = ToastType.Success, title = TITLE_PUBLISHED)
                ok = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast.show(message = e.message ?: MSG_SOMETHING_WRONG, type = ToastType.Error, title = TITLE_ERROR)
            } finally {
                _state.update { it.copy(saving = false) }
                guard.finish()
            }
            onDone(ok)
        }
    }
}

private val WIDE_LAYOUT_MIN_WIDTH = 640.dp

/** The editor of one page banner with its live preview: below the form on a phone, beside it on a tablet. */
@Composable
internal fun PageHeroEditor(
    section: PageHeroSection,
    model: PageHeroEditorModel,
    providers: Set<ImageFieldProvider>,
    modifier: Modifier = Modifier
) {
    val state by model.state.collectAsStateWithLifecycle()

    if (state.loading) {
        Row(
            modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            Text("Loading banner…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= WIDE_LAYOUT_MIN_WIDTH) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { BannerForm(section, model, state, providers) }
                Column(Modifier.weight(1f)) { BannerPreviewPanel(section, state.draft) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                BannerForm(section, model, state, providers)
                BannerPreviewPanel(section, state.draft)
            }
        }
    }
}

@Composable
private fun BannerForm(
    section: PageHeroSection,
    model: PageHeroEditorModel,
    state: PageHeroEditorState,
    providers: Set<ImageFieldProvider>
) {
    val draft = state.draft
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.loadFailed) {
            Text(MSG_BANNER_LOAD_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        CmsImageField(
            providers = providers,
            label = "background image",
            folder = section.imageFolder,
            value = draft.image,
            onChange = { v -> model.update { it.copy(image = v) } },
            previewHeight = 160.dp
        )
        NbmsTextField(
            value = draft.title,
            onValueChange = { v -> model.update { it.copy(title = v) } },
            label = "Title",
            placeholder = "e.g. Get in Touch"
        )
        NbmsTextField(
            value = draft.subtitle,
            onValueChange = { v -> model.update { it.copy(subtitle = v) } },
            label = "Subtitle",
            placeholder = "A short supporting line"
        )
        CmsTextArea(
            value = draft.description,
            onValueChange = { v -> model.update { it.copy(description = v) } },
            label = "Description / Body Text",
            placeholder = "Optional longer text shown under the subtitle",
            lines = 3
        )
        if (section.supportsButton) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NbmsTextField(
                    value = draft.buttonText,
                    onValueChange = { v -> model.update { it.copy(buttonText = v) } },
                    label = "Button Text",
                    placeholder = "e.g. Book Now",
                    modifier = Modifier.weight(1f)
                )
                NbmsTextField(
                    value = draft.buttonLink,
                    onValueChange = { v -> model.update { it.copy(buttonLink = v) } },
                    label = "Button Link",
                    placeholder = "/booking",
                    modifier = Modifier.weight(1f)
                )
            }
        }
        NbmsButton(
            text = if (state.saving) "Publishing…" else "Save & Publish",
            onClick = { model.save() },
            loading = state.saving,
            enabled = !state.saving && !state.loadFailed,
            leadingIcon = NbmsIcons.Check
        )
        Text(
            CmsFormRules.bannerHelper(section),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "Preview" with an eye icon, the preview box, and the line saying it is not live until saved. */
@Composable
private fun BannerPreviewPanel(section: PageHeroSection, content: PageHeroContent) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(NbmsIcons.Eye, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                "Preview",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        BannerPreview(section, content)
        Text(
            "This preview updates as you type. It won't affect the live site until you save.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A 256dp rounded box: the picture cropped to fill (or a diagonal primary → secondary gradient when there is none), a 50% black
 * scrim, and centred white text. The title falls back to the section label; the button pill shows only when the section has a
 * button and button text was typed.
 */
@Composable
internal fun BannerPreview(section: PageHeroSection, content: PageHeroContent, modifier: Modifier = Modifier) {
    val text = CmsFormRules.bannerPreview(content, section)
    val image = content.image.trim()
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(256.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(scheme.primary, scheme.secondary)))
    ) {
        // a picture that cannot load simply leaves the gradient showing
        if (image.isNotEmpty()) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (text.subtitle != null) {
                Text(
                    text.subtitle.uppercase(),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, letterSpacing = 2.sp),
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text.title,
                style = nbmsPageTitleStyle().copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (text.description != null) {
                Text(
                    text.description,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = Color.White.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (text.buttonText != null) {
                Text(
                    text.buttonText,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(scheme.secondary)
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
    }
}
