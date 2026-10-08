package com.westly.nbms.features.notifications

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.westly.nbms.core.feature.ShellNavigator
import com.westly.nbms.core.feature.TopBarAction
import com.westly.nbms.core.session.SessionState
import javax.inject.Inject
import javax.inject.Singleton

private val BadgeRed = Color(0xFFEF4444)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The bell in the top bar (order 10). Besides drawing the unread badge it also:
 * - starts [PushRegistrar] and asks for the Android notification permission once per sign-in, and
 * - opens the screen named by the `link` extra when the app is opened from a push notification.
 */
@Singleton
class NotificationBell @Inject constructor(
    private val repository: NotificationsRepository,
    private val registrar: PushRegistrar,
    private val navigator: ShellNavigator
) : TopBarAction {

    override val order: Int = 10

    @Composable
    override fun Content(session: SessionState.SignedIn) {
        val unread by repository.unreadCount.collectAsStateWithLifecycle(initialValue = 0)
        val context = LocalContext.current
        val scheme = MaterialTheme.colorScheme

        // Android 13+ permission prompt, once per sign-in.
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Whatever the answer, the token is registered; without permission Android simply shows nothing.
        }
        LaunchedEffect(session.user.uid) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                registrar.isEnabled() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                registrar.consumePermissionPrompt(session.user.uid)
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // A tapped push arrives as a `link` extra on the activity's intent.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, context) {
            fun consumeLink() {
                val activity = context.findActivity() ?: return
                val link = activity.intent?.getStringExtra(EXTRA_LINK)
                if (!link.isNullOrBlank()) {
                    activity.intent.removeExtra(EXTRA_LINK)
                    navigator.open(link)
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) consumeLink()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            consumeLink()
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .semantics { contentDescription = bellDescription(unread) }
                .clickable(role = Role.Button) { navigator.open("notifications") },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            if (unread > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-2).dp, y = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(scheme.surface)
                        .padding(2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(BadgeRed)
                        .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        badgeLabel(unread),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
