package com.rostamvpn.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rostamvpn.android.ui.theme.Danger
import com.rostamvpn.android.ui.theme.Interactive1
import com.rostamvpn.android.ui.theme.Success1
import com.rostamvpn.android.ui.theme.Warning1
import kotlinx.coroutines.delay

/**
 * Toast types matching Figma toast design system:
 * - Success (green)
 * - Info (blue)
 * - Warning (amber)
 * - Error (red)
 */
enum class ToastType {
    SUCCESS,
    INFO,
    WARNING,
    ERROR
}

/**
 * Data class for toast messages with type and content
 */
data class ToastData(
    val message: String,
    val type: ToastType = ToastType.INFO
)

/**
 * Top notification bar that slides down from the top of the screen.
 * Supports 4 types: Success (green), Info (blue), Warning (amber), Error (red).
 * Auto-dismisses after 4 seconds with dismiss X button.
 */
@Composable
fun RostamToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    type: ToastType = ToastType.INFO
) {
    var visible by remember { mutableStateOf(false) }

    // Show toast when message is non-null, auto-dismiss after 4 seconds
    LaunchedEffect(message) {
        if (message != null) {
            visible = true
            delay(4000L)
            visible = false
            // Small delay for exit animation before calling onDismiss
            delay(300L)
            onDismiss()
        } else {
            visible = false
        }
    }

    val (backgroundColor, icon) = when (type) {
        ToastType.SUCCESS -> Success1 to Icons.Default.CheckCircle
        ToastType.INFO -> Interactive1 to Icons.Default.Info
        ToastType.WARNING -> Warning1 to Icons.Default.Warning
        ToastType.ERROR -> Danger to Icons.Default.Warning
    }

    AnimatedVisibility(
        visible = visible && message != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(backgroundColor)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                onClick = {
                    visible = false
                    onDismiss()
                },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
