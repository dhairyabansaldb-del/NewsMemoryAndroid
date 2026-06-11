package com.dhairya.newsmemory.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 2 placeholder home: capture/allowlist status only.
 * Phase 4 replaces this with today's three digest cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    capturedCount: Int,
    allowlistSize: Int,
    onEditAllowlist: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("News Memory") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Capture status", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$allowlistSize apps on the allowlist",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "$capturedCount notifications in the archive",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Digests arrive in Phase 4 — capture begins in Phase 3.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            OutlinedButton(onClick = onEditAllowlist, modifier = Modifier.fillMaxWidth()) {
                Text("Edit allowlist")
            }
        }
    }
}
