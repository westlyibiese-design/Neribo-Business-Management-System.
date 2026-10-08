package com.westly.nbms.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role as A11yRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.westly.nbms.core.design.NbmsIcons
import com.westly.nbms.core.design.nbms
import com.westly.nbms.core.design.nbmsBrandSmallStyle
import com.westly.nbms.core.feature.NavSpec
import com.westly.nbms.core.session.SessionState

private val DrawerWidth = 240.dp

/**
 * The always-dark navy drawer (both themes): header, scrollable navigation, then the user card with Lock / Sign Out.
 * It only draws what it is given; the shell decides what is visible.
 */
@Composable
fun DrawerContent(
    session: SessionState.SignedIn,
    entries: List<DrawerEntry>,
    currentRoute: String?,
    showLock: Boolean,
    showEndBorder: Boolean,
    onNavigate: (String) -> Unit,
    onProfile: () -> Unit,
    onLock: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val nbms = MaterialTheme.nbms
    Column(
        modifier = modifier
            .width(DrawerWidth)
            .fillMaxHeight()
            .background(nbms.drawerBackground)
            .then(
                if (showEndBorder) {
                    Modifier.drawBehind {
                        val w = 1.dp.toPx()
                        drawRect(nbms.drawerBorder, topLeft = Offset(size.width - w, 0f), size = Size(w, size.height))
                    }
                } else Modifier
            )
    ) {
        DrawerHeader()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            entries.forEach { entry ->
                when (entry) {
                    is DrawerEntry.Leaf -> NavLeafItem(
                        spec = entry.spec,
                        session = session,
                        active = entry.spec.route == currentRoute,
                        onClick = { onNavigate(entry.spec.route) }
                    )
                    is DrawerEntry.Group -> NavGroupItem(
                        group = entry,
                        session = session,
                        currentRoute = currentRoute,
                        onNavigate = onNavigate
                    )
                }
            }
        }
        DrawerFooter(
            session = session,
            showLock = showLock,
            onProfile = onProfile,
            onLock = onLock,
            onSignOut = onSignOut
        )
    }
}

@Composable
private fun DrawerDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.nbms.drawerBorder)
    )
}

@Composable
private fun DrawerHeader() {
    val nbms = MaterialTheme.nbms
    Column(Modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Logo mark: navy square with a gold "N".
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF203A6F)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "N",
                    style = nbmsBrandSmallStyle().copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = nbms.drawerPrimary
                )
            }
            Column {
                Text("NBMS", style = nbmsBrandSmallStyle(), color = nbms.drawerForeground)
                Text(
                    "MANAGEMENT",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
                    color = nbms.drawerForeground.copy(alpha = 0.5f)
                )
            }
        }
        DrawerDivider()
    }
}

/** One navigation row: 40dp high, 8dp radius, 16dp icon, 14sp label. */
@Composable
private fun NavRow(
    label: String,
    icon: ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    medium: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {}
) {
    val nbms = MaterialTheme.nbms
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val background = when {
        active -> nbms.drawerPrimary
        pressed -> nbms.drawerAccent
        else -> Color.Transparent
    }
    val foreground = if (active) nbms.drawerPrimaryForeground else nbms.drawerForeground.copy(alpha = 0.7f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(interactionSource = source, indication = null, role = A11yRole.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal
            ),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        trailing()
    }
}

/** A top-level or child item. Shows the unread-style badge when the feature supplies one. */
@Composable
fun NavLeafItem(
    spec: NavSpec,
    session: SessionState.SignedIn,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val badge = badgeText(spec.badge?.invoke(session))
    NavRow(
        label = spec.label,
        icon = spec.icon,
        active = active,
        onClick = onClick,
        medium = true,
        modifier = modifier
    ) {
        if (badge != null) NavBadge(badge, active)
    }
}

@Composable
private fun NavBadge(text: String, active: Boolean) {
    val nbms = MaterialTheme.nbms
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .widthIn(min = 20.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(if (active) nbms.drawerPrimaryForeground.copy(alpha = 0.2f) else nbms.drawerPrimary)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold),
            color = nbms.drawerPrimaryForeground,
            maxLines = 1
        )
    }
}

/** A collapsible group. It opens by itself when one of its children is the page on screen. */
@Composable
fun NavGroupItem(
    group: DrawerEntry.Group,
    session: SessionState.SignedIn,
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val nbms = MaterialTheme.nbms
    val containsCurrent = group.contains(currentRoute)
    var expanded by rememberSaveable(group.label) { mutableStateOf(containsCurrent) }
    LaunchedEffect(currentRoute) {
        if (containsCurrent) expanded = true
    }
    val chevronRotation by animateFloatAsState(if (expanded) 0f else -90f, label = "groupChevron")
    val groupIcon = group.children.first().icon

    Column(modifier) {
        NavRow(
            label = group.label,
            icon = groupIcon,
            active = false,
            onClick = { expanded = !expanded },
            medium = false
        ) {
            Icon(
                NbmsIcons.ChevronDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = nbms.drawerForeground.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevronRotation)
            )
        }
        AnimatedVisibility(visible = expanded) {
            val guide = nbms.drawerBorder
            Column(
                modifier = Modifier
                    .padding(start = 16.dp, top = 4.dp)
                    .drawBehind {
                        drawRect(guide, topLeft = Offset.Zero, size = Size(1.dp.toPx(), size.height))
                    }
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                group.children.forEach { child ->
                    NavLeafItem(
                        spec = child,
                        session = session,
                        active = child.route == currentRoute,
                        onClick = { onNavigate(child.route) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerFooter(
    session: SessionState.SignedIn,
    showLock: Boolean,
    onProfile: () -> Unit,
    onLock: () -> Unit,
    onSignOut: () -> Unit
) {
    Column(Modifier.navigationBarsPadding()) {
        DrawerDivider()
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            UserCard(session = session, onClick = onProfile)
            Spacer(Modifier.height(4.dp))
            if (showLock && !session.user.usesPin) {
                FooterButton(label = "Lock", icon = NbmsIcons.Lock, onClick = onLock)
            }
            FooterButton(
                label = if (session.user.usesPin) "End Session" else "Sign Out",
                icon = NbmsIcons.LogOut,
                onClick = onSignOut
            )
        }
    }
}

/** Avatar, name and role. Tapping it opens the profile sheet. */
@Composable
fun UserCard(session: SessionState.SignedIn, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val nbms = MaterialTheme.nbms
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(nbms.drawerAccent)
            .clickable(role = A11yRole.Button, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(nbms.drawerPrimary),
            contentAlignment = Alignment.Center
        ) {
            Text(
                initialOf(session.user.name),
                color = nbms.drawerPrimaryForeground,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                session.user.name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = nbms.drawerForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                Text(
                    session.user.role.label,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                    color = nbms.drawerForeground.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (session.user.usesPin) {
                    Text(
                        "(PIN)",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
                        color = nbms.drawerPrimary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/** Ghost button for the dark drawer (the normal ghost button uses the page text colour). */
@Composable
private fun FooterButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val nbms = MaterialTheme.nbms
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) nbms.drawerAccent else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = A11yRole.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = nbms.drawerForeground.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = nbms.drawerForeground.copy(alpha = 0.6f),
            maxLines = 1
        )
    }
}
