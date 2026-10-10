package com.westly.nbms.features.cms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Monitor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.FlowChips
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.session.SessionState

private val CMS_MAX_WIDTH = 768.dp
private val CMS_BANNERS_MAX_WIDTH = 1024.dp

internal const val CMS_TITLE = "Website CMS"
internal const val CMS_SUBTITLE = "Edit public website content without touching code"

/** The Website CMS page (`cms`, Super Admin only): header, load-error banner, the tab row and the six tabs. */
@Composable
fun WebsiteCmsScreen(session: SessionState.SignedIn) {
    val vm: WebsiteCmsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .widthIn(max = if (state.tab == CmsTab.BANNERS) CMS_BANNERS_MAX_WIDTH else CMS_MAX_WIDTH)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        CmsPageHeader(icon = NbmsIcons.BookOpen, title = CMS_TITLE, subtitle = CMS_SUBTITLE)

        if (state.loadFailed) {
            Text(MSG_CMS_LOAD_FAILED_BANNER, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        CmsTabRow(selected = state.tab, changed = state.changedTabs, onSelect = vm::selectTab)

        when (state.tab) {
            CmsTab.HERO -> HeroTab(vm, state)
            CmsTab.ABOUT -> AboutTab(vm, state)
            CmsTab.BANNERS -> PageBannersTab()
            CmsTab.CONTACT -> ContactTab(vm, state)
            CmsTab.TESTIMONIALS -> TestimonialsTab(vm, state)
            CmsTab.FAQS -> FaqsTab(vm, state)
        }
    }
}

private fun CmsTab.icon(): ImageVector = when (this) {
    CmsTab.HERO -> Icons.Outlined.Monitor
    CmsTab.ABOUT -> Icons.Outlined.Info
    CmsTab.BANNERS -> NbmsIcons.Images
    CmsTab.CONTACT -> NbmsIcons.Mail
    CmsTab.TESTIMONIALS -> NbmsIcons.Users
    CmsTab.FAQS -> Icons.AutoMirrored.Outlined.HelpOutline
}

/**
 * Pill row that wraps onto more lines on a narrow screen; the selected pill is filled with the primary colour. A small dot on a
 * pill means that tab holds typing that is not saved yet.
 */
@Composable
private fun CmsTabRow(selected: CmsTab, changed: Set<CmsTab>, onSelect: (CmsTab) -> Unit) {
    FlowChips(horizontalSpacing = 8.dp, verticalSpacing = 8.dp, modifier = Modifier.fillMaxWidth()) {
        CmsFormRules.TABS.forEach { tab ->
            val active = tab == selected
            val scheme = MaterialTheme.colorScheme
            val contentColor = if (active) scheme.onPrimary else scheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(CircleShape)
                    .background(if (active) scheme.primary else scheme.surfaceVariant)
                    .clickable(role = Role.Tab) { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(tab.icon(), contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
                Text(tab.label, style = MaterialTheme.typography.labelLarge, color = contentColor, maxLines = 1)
                if (tab in changed) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.nbms.warning))
                }
            }
        }
    }
}

// ───────────────────────── shared pieces used by the tabs ─────────────────────────

/** A card with a title and the tab's form inside it. */
@Composable
internal fun CmsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            content()
        }
    }
}

/** A spinner with a short line, shown while a tab's document is still loading. */
@Composable
internal fun CmsLoadingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A multi-line text field that is [lines] lines tall from the start (the shared text field is only one or two lines). Looks
 * like the shared text field: label above, 1dp border that turns to the ring colour on focus.
 */
@Composable
internal fun CmsTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    lines: Int = 3,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
            Spacer(Modifier.height(8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            minLines = lines,
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
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    }
                    inner()
                }
            }
        )
    }
}
