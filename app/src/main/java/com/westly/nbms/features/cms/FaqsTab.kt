package com.westly.nbms.features.cms

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.NbmsTextField

internal const val FAQS_EMPTY = "No FAQs yet. Add your first one above."

/** FAQs tab: `cms_content/faqs` → questions and answers in order; every save renumbers `order` = position + 1. */
@Composable
internal fun FaqsTab(vm: WebsiteCmsViewModel, state: WebsiteCmsUiState) {
    if (state.faqsLoad == SectionLoad.LOADING) {
        CmsLoadingRow("Loading FAQs…")
        return
    }
    var pendingDelete by remember { mutableStateOf<FaqItem?>(null) }
    val items = state.faqs
    val draft = state.faqDraft
    val canAdd = CmsFormRules.canAddFaq(items.size)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                CmsFormRules.faqsHeading(items.size),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            if (draft == null) {
                NbmsButton(
                    text = "Add FAQ",
                    onClick = { vm.openFaqForm() },
                    size = ButtonSize.Sm,
                    enabled = canAdd && !state.saving,
                    leadingIcon = NbmsIcons.Plus
                )
            }
        }
        if (!canAdd) {
            Text(MSG_FAQS_LIMIT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (draft != null && draft.id == null) {
            FaqForm(title = "New FAQ", saveText = "Save FAQ", draft = draft, vm = vm, saving = state.saving)
        }

        if (items.isEmpty()) {
            Text(
                FAQS_EMPTY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
            )
        } else {
            items.forEachIndexed { index, item ->
                if (draft != null && draft.id == item.id) {
                    FaqForm(title = "Edit FAQ", saveText = "Save Changes", draft = draft, vm = vm, saving = state.saving)
                } else {
                    FaqRow(
                        position = index + 1,
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < items.lastIndex,
                        busy = state.saving,
                        onUp = { vm.moveFaq(item.id, -1) },
                        onDown = { vm.moveFaq(item.id, 1) },
                        onEdit = { vm.openFaqForm(FaqDraft(item.id, item.question, item.answer)) },
                        onDelete = { pendingDelete = item }
                    )
                }
            }
        }
    }

    pendingDelete?.let { item ->
        CmsDeleteDialog(
            title = "Delete FAQ?",
            body = CmsFormRules.deleteFaqBody(item.question),
            onConfirm = {
                pendingDelete = null
                vm.deleteFaq(item.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun FaqRow(
    position: Int,
    item: FaqItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    busy: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    position.toString(),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            CmsReorderButtons(canMoveUp = canMoveUp, canMoveDown = canMoveDown, enabled = !busy, onUp = onUp, onDown = onDown)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.question,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    item.answer,
                    style = MaterialTheme.typography.bodySmall,
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
private fun FaqForm(title: String, saveText: String, draft: FaqDraft, vm: WebsiteCmsViewModel, saving: Boolean) {
    CmsFormCard {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onBackground
        )
        NbmsTextField(
            value = draft.question,
            onValueChange = { v -> vm.updateFaqDraft { it.copy(question = v) } },
            label = "Question *",
            placeholder = "What are your check-in times?"
        )
        CmsTextArea(
            value = draft.answer,
            onValueChange = { v -> vm.updateFaqDraft { it.copy(answer = v) } },
            label = "Answer *",
            placeholder = "Provide a clear, helpful answer…",
            lines = 5
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NbmsButton(
                text = saveText,
                onClick = { vm.saveFaq() },
                loading = saving,
                enabled = CmsFormRules.isValidFaq(draft.question, draft.answer) && !saving,
                leadingIcon = NbmsIcons.Check
            )
            NbmsButton(text = "Cancel", onClick = { vm.closeFaqForm() }, variant = ButtonVariant.Outline, enabled = !saving)
        }
    }
}
