package com.dhairya.newsmemory.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.dhairya.newsmemory.pipeline.DigestTimes
import com.dhairya.newsmemory.ui.theme.Eyebrow
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.theme.ThemeMode
import com.dhairya.newsmemory.ui.theme.body
import com.dhairya.newsmemory.ui.theme.display

data class HealthStatus(
    val listenerAliveMinsAgo: Long?,
    val lastCaptureMinsAgo: Long?,
    val batteryUnrestricted: Boolean
)

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit,
    times: DigestTimes,
    onSetTimes: (DigestTimes) -> Unit,
    allowlistSize: Int,
    health: HealthStatus,
    onOpenAllowlist: () -> Unit,
    onExport: () -> Unit
) {
    val a = LocalAlmanac.current
    var editing by remember { mutableStateOf<Int?>(null) }   // 0=morning 1=evening 2=night

    Box(Modifier.fillMaxSize().background(a.bg)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Settings", style = display(27), color = a.ink)

            Group("APPEARANCE") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEach { mode ->
                        Segment(
                            label = mode.name.lowercase().replaceFirstChar { it.uppercase() },
                            selected = mode == themeMode,
                            modifier = Modifier.weight(1f)
                        ) { onSetTheme(mode) }
                    }
                }
            }

            Group("DIGEST TIMES") {
                TimeRow(Icons.Filled.WbSunny, "Morning", times.morningMinutes) { editing = 0 }
                Divider()
                TimeRow(Icons.Filled.WbTwilight, "Evening", times.eveningMinutes) { editing = 1 }
                Divider()
                TimeRow(Icons.Filled.Bedtime, "Night", times.nightMinutes) { editing = 2 }
            }

            Group("SOURCES & DATA") {
                NavRow(Icons.Filled.Layers, "Listening to", "$allowlistSize apps", onOpenAllowlist)
                Divider()
                NavRow(Icons.Filled.Download, "Export archive (JSON)", null, onExport)
            }

            Group("HEALTH") {
                HealthRow("Listener service", health.listenerAliveMinsAgo?.let { if (it < 20) "Active" else "Stale ${it}m" } ?: "Never", ok = (health.listenerAliveMinsAgo ?: 999) < 20)
                Divider()
                HealthRow("Last capture", health.lastCaptureMinsAgo?.let { relMin(it) } ?: "None yet", ok = true)
                Divider()
                HealthRow("Battery", if (health.batteryUnrestricted) "Unrestricted" else "Restricted", ok = health.batteryUnrestricted)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editing != null) {
        val current = when (editing) {
            0 -> times.morningMinutes; 1 -> times.eveningMinutes; else -> times.nightMinutes
        }
        TimePickerDialog(
            initialMinutes = current,
            onDismiss = { editing = null },
            onConfirm = { mins ->
                onSetTimes(
                    when (editing) {
                        0 -> times.copy(morningMinutes = mins)
                        1 -> times.copy(eveningMinutes = mins)
                        else -> times.copy(nightMinutes = mins)
                    }
                )
                editing = null
            }
        )
    }
}

private fun relMin(m: Long): String = when {
    m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago"
}

private fun fmt(min: Int): String {
    val h = min / 60; val mm = min % 60
    val ampm = if (h < 12) "AM" else "PM"
    val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
    return "%d:%02d %s".format(h12, mm, ampm)
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    val a = LocalAlmanac.current
    Column {
        Text(title, style = Eyebrow, color = a.inkMed)
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(a.card)
                .border(1.dp, a.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp)
        ) { content() }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(LocalAlmanac.current.line))
}

@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val a = LocalAlmanac.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) a.accent else a.card)
            .border(1.dp, if (selected) a.accent else a.line, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = body(13.0), color = if (selected) a.accentInk else a.inkMed)
    }
}

@Composable
private fun TimeRow(icon: ImageVector, label: String, minutes: Int, onClick: () -> Unit) {
    val a = LocalAlmanac.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = a.inkMed, modifier = Modifier.size(17.dp))
        Spacer(Modifier.size(12.dp))
        Text(label, style = body(14.0), color = a.ink, modifier = Modifier.weight(1f))
        Text(fmt(minutes), style = display(16), color = a.inkMed)
        Spacer(Modifier.size(6.dp))
        Icon(Icons.Outlined.KeyboardArrowRight, null, tint = a.inkLow)
    }
}

@Composable
private fun NavRow(icon: ImageVector, label: String, value: String?, onClick: () -> Unit) {
    val a = LocalAlmanac.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = a.inkMed, modifier = Modifier.size(17.dp))
        Spacer(Modifier.size(12.dp))
        Text(label, style = body(14.0), color = a.ink, modifier = Modifier.weight(1f))
        value?.let { Text(it, style = display(13), color = a.inkMed); Spacer(Modifier.size(6.dp)) }
        Icon(Icons.Outlined.KeyboardArrowRight, null, tint = a.inkLow)
    }
}

@Composable
private fun HealthRow(label: String, value: String, ok: Boolean) {
    val a = LocalAlmanac.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) a.good else a.warn))
        Spacer(Modifier.size(12.dp))
        Text(label, style = body(14.0), color = a.inkMed, modifier = Modifier.weight(1f))
        Text(value, style = display(14), color = if (ok) a.good else a.warn)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(initialMinutes: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = false
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) }
    )
}
