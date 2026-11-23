package com.gravity.ide.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gravity.ide.ui.theme.GravityColors
import com.gravity.ide.ui.theme.MonoFamily
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

fun formatTime(ms: Long): String = if (ms <= 0) "—" else SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))

fun formatTimeFull(ms: Long): String = if (ms <= 0) "—" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

fun relativeTime(ms: Long): String {
    if (ms <= 0) return "never"
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        d < 7 * 86400 -> "${d / 86400}d ago"
        else -> formatTime(ms)
    }
}

fun statusColor(status: String): Color = when (status.uppercase()) {
    "COMPLETED", "SUCCEEDED", "PASS", "READY", "OK", "ONLINE" -> GravityColors.Green
    "RUNNING", "DOWNLOADING", "VERIFYING", "ACTIVE" -> GravityColors.Cyan
    "PAUSED", "WAITING_APPROVAL", "WAITING_NETWORK", "INTERRUPTED", "QUEUED", "PENDING", "WARN", "SKIPPED" -> GravityColors.Amber
    "FAILED", "FAIL", "ERROR", "CANCELLED", "OFFLINE" -> GravityColors.Red
    else -> GravityColors.Muted
}

@Composable
fun StatusPill(text: String, color: Color = statusColor(text), modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(text.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), letterSpacing = 0.8.sp)
        action?.invoke()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(12.dp), onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(12.dp))
            action()
        }
    }
}

@Composable
fun MessageBanner(text: String, isError: Boolean = false, onDismiss: (() -> Unit)? = null) {
    val color = if (isError) GravityColors.Red else GravityColors.Blue
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (isError) Icons.Filled.Error else Icons.Filled.Info, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
fun LoadingRow(text: String) {
    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = if (mono) MonoFamily else null, modifier = Modifier.weight(1f))
    }
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(10.dp),
        fontFamily = MonoFamily,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

data class MenuAction(val label: String, val icon: ImageVector? = null, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
fun OverflowMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in actions) {
                val icon = a.icon
                DropdownMenuItem(
                    text = { Text(a.label, color = if (a.destructive) GravityColors.Red else MaterialTheme.colorScheme.onSurface) },
                    leadingIcon = if (icon != null) {
                        { Icon(icon, null, tint = if (a.destructive) GravityColors.Red else MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        a.onClick()
                    },
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String = "Confirm", destructive: Boolean = false, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel, color = if (destructive) GravityColors.Red else MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirmLabel: String = "OK",
    singleLine: Boolean = true,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = {
                onConfirm(value.trim())
                onDismiss()
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A compact dropdown selector (avoids the experimental ExposedDropdownMenu API). */
@Composable
fun <T> Selector(label: String, options: List<T>, selected: T?, display: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.let(display) ?: "Select…", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) {
                DropdownMenuItem(text = { Text(display(o)) }, onClick = {
                    open = false
                    onSelect(o)
                })
            }
        }
    }
}

@Composable
fun Arranged(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}
