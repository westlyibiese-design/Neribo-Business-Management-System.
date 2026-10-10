package com.westly.nbms.features.cms

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.design.BadgeTone
import com.westly.nbms.core.design.ButtonSize
import com.westly.nbms.core.design.ButtonVariant
import com.westly.nbms.core.design.NbmsBadge
import com.westly.nbms.core.design.NbmsButton
import com.westly.nbms.core.design.NbmsCard
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.session.SessionState
import com.westly.nbms.core.util.Format
import com.westly.nbms.core.util.toInstant
import kotlinx.datetime.TimeZone

private val REVIEWS_MAX_WIDTH = 768.dp

internal const val REVIEWS_TITLE = "Guest Reviews"
internal const val REVIEWS_SUBTITLE =
    "Reviews guests submit publicly stay hidden until approved here. Deleting a review removes it from the website immediately."
internal const val REVIEWS_EMPTY_PENDING = "No reviews waiting for approval."
internal const val REVIEWS_EMPTY_APPROVED = "No approved reviews yet."

/** The business time zone; Africa/Lagos when the saved name is missing or unknown. */
private fun reviewsZone(id: String?): TimeZone = try {
    if (id.isNullOrBlank()) TimeZone.of("Africa/Lagos") else TimeZone.of(id)
} catch (e: Exception) {
    TimeZone.of("Africa/Lagos")
}

/** The Guest Reviews page (`reviews`, Super Admin and Manager). */
@Composable
fun ReviewsScreen(session: SessionState.SignedIn) {
    val vm: ReviewsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<Review?>(null) }
    val zone = remember(session.business.timezone) { reviewsZone(session.business.timezone) }

    Column(
        modifier = Modifier.widthIn(max = REVIEWS_MAX_WIDTH).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        CmsPageHeader(icon = NbmsIcons.Reviews, title = REVIEWS_TITLE, subtitle = REVIEWS_SUBTITLE)

        if (state.loading) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            }
        } else {
            if (state.loadFailed) {
                Text(MSG_REVIEWS_LOAD_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            ReviewTabs(
                selected = tab,
                onSelect = { tab = it },
                pendingCount = state.pending.size,
                approvedCount = state.approved.size
            )

            val shown = if (tab == 0) state.pending else state.approved
            if (shown.isEmpty()) {
                Text(
                    if (tab == 0) REVIEWS_EMPTY_PENDING else REVIEWS_EMPTY_APPROVED,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
                )
            } else {
                shown.forEach { review ->
                    ReviewCard(
                        review = review,
                        zone = zone,
                        busy = review.id in state.busyIds,
                        onApprove = { vm.approve(review.id) },
                        onDelete = { pendingDelete = review }
                    )
                }
            }
        }
    }

    pendingDelete?.let { review ->
        CmsDeleteDialog(
            title = "Delete Review?",
            body = ReviewsRules.deleteBody(ReviewsRules.displayName(review)),
            onConfirm = {
                pendingDelete = null
                vm.delete(review.id)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Two pill tabs, "Pending" (count badge when above 0) and "Approved" (count badge). */
@Composable
private fun ReviewTabs(selected: Int, onSelect: (Int) -> Unit, pendingCount: Int, approvedCount: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReviewTab("Pending", if (pendingCount > 0) pendingCount else null, BadgeTone.Warning, selected == 0) { onSelect(0) }
        ReviewTab("Approved", approvedCount, BadgeTone.Success, selected == 1) { onSelect(1) }
    }
}

@Composable
private fun RowScope.ReviewTab(label: String, count: Int?, tone: BadgeTone, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(if (active) scheme.background else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) scheme.onBackground else scheme.onSurfaceVariant,
                maxLines = 1
            )
            if (count != null) NbmsBadge(text = count.toString(), tone = tone)
        }
    }
}

@Composable
private fun ReviewCard(review: Review, zone: TimeZone, busy: Boolean, onApprove: () -> Unit, onDelete: () -> Unit) {
    NbmsCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(NbmsIcons.User, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ReviewsRules.displayName(review),
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (review.isPending) NbmsBadge(text = "Pending", tone = BadgeTone.Warning)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Format.date(review.createdAt.toInstant(), zone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        CmsStars(review.rating)
                    }
                }
            }
            Text(review.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (review.isPending) {
                    NbmsButton(
                        text = "Approve",
                        onClick = onApprove,
                        variant = ButtonVariant.Outline,
                        size = ButtonSize.Sm,
                        loading = busy,
                        enabled = !busy,
                        leadingIcon = NbmsIcons.Check
                    )
                }
                CmsIconAction(NbmsIcons.Trash, "Delete", !busy, onDelete)
            }
        }
    }
}
