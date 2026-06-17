package com.dhairya.newsmemory.ui.allowlist

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dhairya.newsmemory.data.SettingsStore
import com.dhairya.newsmemory.ui.components.SourceMonogram
import com.dhairya.newsmemory.ui.theme.Eyebrow
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.theme.body
import com.dhairya.newsmemory.ui.theme.display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class InstalledApp(val packageName: String, val label: String)

class AllowlistViewModel(
    private val packageManager: PackageManager,
    private val settingsStore: SettingsStore,
    private val ownPackage: String
) : ViewModel() {

    private val _apps = MutableStateFlow<List<InstalledApp>?>(null)
    val apps: StateFlow<List<InstalledApp>?> = _apps

    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected

    private val _limited = MutableStateFlow<Set<String>>(emptySet())
    val limited: StateFlow<Set<String>> = _limited

    init {
        viewModelScope.launch {
            _selected.value = settingsStore.allowlist.first()
            _limited.value = settingsStore.limitedSupport.first()
            launch(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                _apps.value = packageManager.queryIntentActivities(intent, 0)
                    .map { it.activityInfo.applicationInfo }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != ownPackage }
                    .map { InstalledApp(it.packageName, it.loadLabel(packageManager).toString()) }
                    .sortedBy { it.label.lowercase() }
            }
        }
    }

    fun toggle(packageName: String) {
        val next = _selected.value.let { if (packageName in it) it - packageName else it + packageName }
        _selected.value = next
        viewModelScope.launch { settingsStore.setAllowlist(next) }
    }

    fun clearAll() {
        _selected.value = emptySet()
        viewModelScope.launch { settingsStore.setAllowlist(emptySet()) }
    }
}

@Composable
fun AllowlistScreen(
    viewModel: AllowlistViewModel,
    showBack: Boolean,
    onBack: () -> Unit
) {
    val a = LocalAlmanac.current
    val apps by viewModel.apps.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val limited by viewModel.limited.collectAsState()
    var query by remember { mutableStateOf("") }

    val list = apps.orEmpty()
    val filtered = list.filter {
        query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)
    }
    val on = filtered.filter { it.packageName in selected }
    val available = filtered.filter { it.packageName !in selected }

    Box(Modifier.fillMaxSize().background(a.bg)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item(span = { GridCells.Fixed(4).let { androidx.compose.foundation.lazy.grid.GridItemSpan(4) } }) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = a.ink,
                                modifier = Modifier.size(24.dp).clickable(onClick = onBack)
                            )
                            Spacer(Modifier.size(12.dp))
                        }
                        Text("Listening to", style = display(27), color = a.ink)
                    }
                    Spacer(Modifier.height(12.dp))
                    PrivacyNote()
                    Spacer(Modifier.height(12.dp))
                    SearchField(query) { query = it }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ON · ${on.size} SOURCES", style = Eyebrow, color = a.inkMed, modifier = Modifier.weight(1f))
                        if (on.isNotEmpty()) Text(
                            "Clear all", style = body(12.0, FontWeight.SemiBold), color = a.accent,
                            modifier = Modifier.clickable { viewModel.clearAll() }
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                }
            }

            items(on, key = { "on-${it.packageName}" }) { app ->
                AppTile(app, on = true, limited = app.packageName in limited) { viewModel.toggle(app.packageName) }
            }

            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(4) }) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("AVAILABLE", style = Eyebrow, color = a.inkMed)
                    Spacer(Modifier.height(9.dp))
                }
            }

            items(available, key = { "off-${it.packageName}" }) { app ->
                AppTile(app, on = false, limited = false) { viewModel.toggle(app.packageName) }
            }
        }
    }
}

@Composable
private fun PrivacyNote() {
    val a = LocalAlmanac.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(a.card)
            .border(1.dp, a.line, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Shield, null, tint = a.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            "This reader only sees notifications from apps switched on here. Everything else stays invisible.",
            style = body(11.5), color = a.inkMed
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val a = LocalAlmanac.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(a.card)
            .border(1.dp, a.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null, tint = a.inkLow, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text("Search installed apps", style = body(13.0), color = a.inkLow)
            BasicTextField(
                value = query, onValueChange = onChange,
                textStyle = body(13.0).copy(color = a.ink),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(a.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun AppTile(app: InstalledApp, on: Boolean, limited: Boolean, onToggle: () -> Unit) {
    val a = LocalAlmanac.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = remember(app.packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(a.card)
                .border(
                    if (on) 1.5.dp else 1.dp,
                    if (on) a.accent else a.line,
                    RoundedCornerShape(16.dp)
                )
                .alpha(if (on) 1f else 0.66f)
                .clickable(onClick = onToggle)
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(38.dp))
            } else {
                SourceMonogram(app.label, size = 38)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                app.label, style = body(11.0, FontWeight.Medium), color = a.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
            if (limited) Text("limited", style = body(9.0), color = a.warn)
        }
        if (on) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(a.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Check, null, tint = a.accentInk, modifier = Modifier.size(11.dp))
            }
        }
    }
}
