package com.dhairya.newsmemory.ui.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dhairya.newsmemory.util.GrantState
import com.dhairya.newsmemory.util.Permissions

/**
 * Three-step onboarding (EDD §2). Each step deep-links to the exact settings screen and
 * re-checks on resume. Step 3 is not skippable without the explicit reliability ack.
 */
@Composable
fun OnboardingScreen(
    batteryAcknowledged: Boolean,
    onAcknowledgeBattery: (Boolean) -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var grants by remember { mutableStateOf(Permissions.grantState(context)) }

    // Detect completion when the user returns from a settings screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) grants = Permissions.grantState(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val postNotifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { grants = Permissions.grantState(context) }

    val step3Satisfied = (grants.exactAlarms && grants.batteryUnrestricted) || batteryAcknowledged
    val canContinue = grants.coreGranted && step3Satisfied

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Welcome to News Memory", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Three permissions make the digests work. Each opens the exact settings screen.",
            style = MaterialTheme.typography.bodyMedium
        )

        StepCard(
            index = 1,
            title = "Notification access",
            done = grants.notificationAccess,
            description = "This app reads notifications only from apps you select on the next " +
                "screen. Everything else is invisible to it — the check happens before a " +
                "notification is ever read.",
            buttonLabel = "Open notification access",
            onClick = { context.startActivity(Permissions.notificationAccessIntent(context)) }
        )

        StepCard(
            index = 2,
            title = "Digest notifications",
            done = grants.postNotifications,
            description = "Needed to deliver the three digest pushes — morning, evening, night. " +
                "Nothing else is ever posted.",
            buttonLabel = "Allow notifications",
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        )

        StepCard(
            index = 3,
            title = "Exact timing & battery",
            done = grants.exactAlarms && grants.batteryUnrestricted,
            description = "Digests fire at exact times, and on Samsung the battery setting is " +
                "the difference between a listener that survives overnight and one that " +
                "silently dies. Set battery to Unrestricted: App info → Battery → Unrestricted.",
            extraContent = {
                if (!grants.exactAlarms) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Permissions.exactAlarmIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Allow exact alarms") }
                }
                if (!grants.batteryUnrestricted) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Permissions.batteryExemptionIntent(context))
                            }.recoverCatching {
                                context.startActivity(Permissions.appInfoIntent(context))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Set battery to Unrestricted") }
                    OutlinedButton(
                        onClick = { context.startActivity(Permissions.appInfoIntent(context)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open App info (Battery → Unrestricted)") }
                }
                if (!(grants.exactAlarms && grants.batteryUnrestricted)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = batteryAcknowledged,
                            onCheckedChange = onAcknowledgeBattery
                        )
                        Text(
                            "I understand digests may be unreliable without these",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        )

        Button(
            onClick = onComplete,
            enabled = canContinue,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Choose apps to listen to") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepCard(
    index: Int,
    title: String,
    done: Boolean,
    description: String,
    buttonLabel: String? = null,
    onClick: (() -> Unit)? = null,
    extraContent: (@Composable () -> Unit)? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (done) "Done" else "Pending",
                    tint = if (done) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text("$index. $title", style = MaterialTheme.typography.titleMedium)
            }
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (!done && buttonLabel != null && onClick != null) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text(buttonLabel)
                }
            }
            extraContent?.invoke()
        }
    }
}
