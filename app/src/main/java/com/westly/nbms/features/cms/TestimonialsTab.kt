package com.westly.nbms.features.cms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.FlowChips
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

internal const val TESTIMONIALS_EMPTY = "No testimonials yet. Add your first one above."

/** Testimonials tab: `cms_content/testimonials` → a list of guest quotes with a 1–5 star rating. */
@Composable
internal fun TestimonialsTab(vm: WebsiteCmsViewModel, state: WebsiteCmsUiState) {
    if (state.testimonialsLoad == SectionLoad.LOADING) {
        CmsLoadingRow("Loading testimonials…")
        return
    }
    var pendingDelete by remember { mutableStateOf<TestimonialItem?>(null) }
    val items = state.testimonials
    val draft = state.testimonialDraft
    val canAdd = CmsFormRules.canAddTestimonial(items.size)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                CmsFormRules.testimonialsHeading(items.size),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (draft == null) {
                NbmsButton(
                    text = "Add Testimonial",
                    onClick = { vm.openTestimonialForm() },
                    size = ButtonSize.Sm,
                    enabled = canAdd && !state.saving,
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }
        if (!canAdd) {
            Text(MSG_TESTIMONIALS_LIMIT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (draft != null && draft.id == null) {
            TestimonialForm(title = "New Testimonial", saveText = "Save", draft = draft, vm = vm, saving = state.saving)
        }

        if (items.isEmpty()) {
            Text(
                TESTIMONIALS_EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
            )
        } else {
            items.forEach { item ->
                if (draft != null && draft.id == item.id) {
                    TestimonialForm(title = "Edit Testimonial", saveText = "Save Changes", draft = draft, vm = vm, saving = state.saving)
                } else {
                    TestimonialRow(
                        item = item,
                        busy = state.saving,
                        onEdit = { vm.openTestimonialForm(TestimonialDraft(item.id, item.author, item.role, item.text, item.rating)) },
                        onDelete = { pendingDelete = item }
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        CmsDeleteDialog(
            title = "Delete Testimonial?",
            body = CmsFormRules.deleteTestimonialBody(item.author),
            onConfirm = {
                pendingDelete = null
                vm.deleteTestimonial(item.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun TestimonialRow(item: TestimonialItem, busy: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowChips(horizontalSpacing = 6.dp, verticalSpacing = 2.dp) {
                    Text(
                        item.author,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (item.role.isNotBlank()) {
                        Text("· ${item.role}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CmsStars(item.rating, starSize = 14.dp)
                }
                Text(
                    "\"${item.text}\"",
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column {
                CmsIconAction(NbmsIcons.Pencil, "Edit", !busy, onEdit)
                CmsIconAction(NbmsIcons.Trash, "Delete", !busy, onDelete)
            }
        }
    }
}

@Composable
private fun TestimonialForm(title: String, saveText: String, draft: TestimonialDraft, vm: WebsiteCmsViewModel, saving: Boolean) {
    CmsFormCard {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NbmsTextField(
                value = draft.author,
                onValueChange = { v -> vm.updateTestimonialDraft { it.copy(author = v) } },
                label = "Guest Name *",
                placeholder = "Jane Doe",
                modifier = Modifier.weight(1f)
            )
            NbmsTextField(
                value = draft.role,
                onValueChange = { v -> vm.updateTestimonialDraft { it.copy(role = v) } },
                label = "Stay Type / Label",
                placeholder = "Honeymoon, Business Trip…",
                modifier = Modifier.weight(1f)
            )
        }
        CmsTextArea(
            value = draft.text,
            onValueChange = { v -> vm.updateTestimonialDraft { it.copy(text = v) } },
            label = "Review Text *",
            placeholder = "Share the guest's experience…",
            lines = 4
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rating", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            CmsStars(draft.rating, starSize = 32.dp, onSelect = { v -> vm.updateTestimonialDraft { it.copy(rating = v) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = saveText,
                onClick = { vm.saveTestimonial() },
                loading = saving,
                enabled = CmsFormRules.isValidTestimonial(draft.author, draft.text) && !saving,
                leadingIcon = NbmsIcons.Check
            )
            NbmsButton(text = "Cancel", onClick = { vm.closeTestimonialForm() }, variant = ButtonVariant.Outline, enabled = !saving)
        }
    }
}
