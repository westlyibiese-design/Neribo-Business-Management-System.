package com.westly.nbms.core.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.channels.BufferOverflow

enum class ToastType { Success, Error, Info }

data class ToastEvent(
    val message: String,
    val type: ToastType,
    val title: String? = null,
    val id: Long = 0L
)

/**
 * App-wide toast sender. Inject it anywhere with Hilt (or use `hiltViewModel<ToastViewModel>().controller`
 * inside a screen) and call `show("Saved")`. One toast is visible at a time; a new one replaces the old.
 */
@Singleton
class ToastController @Inject constructor() {
    private val counter = AtomicLong(0L)
    private val _events = MutableSharedFlow<ToastEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ToastEvent> = _events.asSharedFlow()

    fun show(message: String, type: ToastType = ToastType.Success, title: String? = null) {
        _events.tryEmit(ToastEvent(message = message, type = type, title = title, id = counter.incrementAndGet()))
    }
}

/** Gives Compose screens access to the singleton [ToastController]. */
@HiltViewModel
class ToastViewModel @Inject constructor(val controller: ToastController) : ViewModel()

/**
 * Place once near the top of a screen (inside a Box that fills the screen).
 * Toasts appear at the top, last 5 seconds, can be swiped sideways or closed with the X.
 * Success = green, Error = red, Info = navy.
 */
@Composable
fun NbmsSnackbarHost(
    controller: ToastController,
    modifier: Modifier = Modifier
) {
    var current by remember { mutableStateOf<ToastEvent?>(null) }

    LaunchedEffect(controller) {
        controller.events.collectLatest { event ->
            current = event
            delay(5_000)
            current = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .safeDrawingPadding()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = current != null,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            // Keep showing the last toast's content while it animates out.
            val shown = remember { mutableStateOf<ToastEvent?>(null) }
            current?.let { shown.value = it }
            shown.value?.let { ToastCard(it, onClose = { current = null }) }
        }
    }
}

@Composable
private fun ToastCard(event: ToastEvent, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val nbms = MaterialTheme.nbms
    val (container, content) = when (event.type) {
        ToastType.Success -> nbms.success to nbms.onSuccess
        ToastType.Error -> scheme.error to scheme.onError
        ToastType.Info -> nbms.toastInfo to Color.White
    }
    val shape = MaterialTheme.shapes.small
    var dragX by remember(event.id) { mutableStateOf(0f) }

    Row(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .offset { IntOffset(dragX.roundToInt(), 0) }
            .alpha(1f - (kotlin.math.abs(dragX) / 600f).coerceIn(0f, 0.8f))
            .nbmsShadow(8.dp, shape)
            .clip(shape)
            .background(container)
            .pointerInput(event.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (kotlin.math.abs(dragX) > 120f) onClose() else dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { _, delta -> dragX += delta }
                )
            }
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            if (event.title != null) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = content
                )
            }
            Text(
                event.message,
                style = MaterialTheme.typography.bodyMedium,
                color = content.copy(alpha = if (event.title != null) 0.9f else 1f)
            )
        }
        Icon(
            Icons.Outlined.Close,
            contentDescription = "Dismiss",
            tint = content,
            modifier = Modifier
                .size(16.dp)
                .alpha(0.7f)
                .clickable(role = Role.Button, onClick = onClose)
        )
    }
}
