package com.dhairya.newsmemory

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dhairya.newsmemory.data.ArchiveExporter
import com.dhairya.newsmemory.pipeline.DigestAlarmScheduler
import com.dhairya.newsmemory.pipeline.DigestNotifier
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.pipeline.DigestTimes
import com.dhairya.newsmemory.ui.allowlist.AllowlistScreen
import com.dhairya.newsmemory.ui.allowlist.AllowlistViewModel
import com.dhairya.newsmemory.ui.archive.ArchiveScreen
import com.dhairya.newsmemory.ui.digest.DigestDetailScreen
import com.dhairya.newsmemory.ui.home.HomeScreen
import com.dhairya.newsmemory.ui.home.SlotCard
import com.dhairya.newsmemory.ui.onboarding.OnboardingScreen
import com.dhairya.newsmemory.ui.settings.HealthStatus
import com.dhairya.newsmemory.ui.settings.SettingsScreen
import com.dhairya.newsmemory.ui.theme.LocalAlmanac
import com.dhairya.newsmemory.ui.theme.NewsMemoryTheme
import com.dhairya.newsmemory.ui.theme.ThemeMode
import com.dhairya.newsmemory.ui.theme.body
import com.dhairya.newsmemory.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private object Routes {
    const val ONBOARDING = "onboarding"
    const val ALLOWLIST_SETUP = "allowlist_setup"
    const val ALLOWLIST = "allowlist"
    const val TODAY = "today"
    const val ARCHIVE = "archive"
    const val SETTINGS = "settings"
    const val DIGEST = "digest/{digestId}"
    fun digest(id: String) = "digest/$id"
}

class MainActivity : ComponentActivity() {
    private val pendingDigestId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDigestId.value = intent.getStringExtra(DigestNotifier.EXTRA_DIGEST_ID)
        val container = (application as App).container
        setContent {
            val mode by container.settingsStore.themeMode.collectAsState(initial = "AUTO")
            NewsMemoryTheme(themeMode = ThemeMode.from(mode)) {
                NewsMemoryApp(container, pendingDigestId.value) { pendingDigestId.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDigestId.value = intent.getStringExtra(DigestNotifier.EXTRA_DIGEST_ID)
    }
}

@Composable
private fun NewsMemoryApp(container: AppContainer, pendingDigestId: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val a = LocalAlmanac.current

    // JSON export (EDD §3): SAF "create document" picker → write the whole archive to it.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val payload = ArchiveExporter.toJson(container.database)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray()) }
                        ?: error("could not open $uri")
                }
            }.isSuccess
            Toast.makeText(context, if (ok) "Archive exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    val onboardingDone by container.settingsStore.onboardingDone.collectAsState(initial = null)
    val batteryAck by container.settingsStore.batteryAcknowledged.collectAsState(initial = false)
    val allowlist by container.settingsStore.allowlist.collectAsState(initial = emptySet())
    val lastAlive by container.settingsStore.lastAlive.collectAsState(initial = null)
    val lastCaptured by container.database.rawNotificationDao().lastCapturedFlow().collectAsState(initial = null)
    val times by container.settingsStore.digestTimes.collectAsState(initial = DigestTimes())
    val themeMode by container.settingsStore.themeMode.collectAsState(initial = "AUTO")
    val allDigests by container.database.digestDao().allDigests().collectAsState(initial = emptyList())

    val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val todayItems by container.database.digestDao().itemsForDate(today).collectAsState(initial = emptyList())

    val slotCards = DigestSlot.entries.map { slot ->
        val id = "$today-${slot.code}"
        SlotCard(slot, allDigests.firstOrNull { it.id == id }, times.minutesFor(slot))
    }
    val topicCounts = todayItems.groupingBy { it.topicLabel }.eachCount()
        .entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }

    // App labels for the listening strip monograms (cheap for a handful of packages)
    val listeningLabels = remember(allowlist) {
        allowlist.mapNotNull { pkg ->
            runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(pkg, 0)
                ).toString()
            }.getOrNull()
        }
    }

    var coreGranted by remember { mutableStateOf(Permissions.grantState(context).coreGranted) }
    var batteryOk by remember { mutableStateOf(Permissions.grantState(context).batteryUnrestricted) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                val g = Permissions.grantState(context)
                coreGranted = g.coreGranted; batteryOk = g.batteryUnrestricted
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val done = onboardingDone ?: return
    val start = if (done && coreGranted) Routes.TODAY else Routes.ONBOARDING

    androidx.compose.runtime.LaunchedEffect(pendingDigestId, done) {
        if (pendingDigestId != null && done) { nav.navigate(Routes.digest(pendingDigestId)); onConsumed() }
    }

    val vmFactory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(c: Class<T>): T =
                AllowlistViewModel(context.packageManager, container.settingsStore, context.packageName) as T
        }
    }

    val backEntry by nav.currentBackStackEntryAsState()
    val route = backEntry?.destination?.route
    val showNav = route in setOf(Routes.TODAY, Routes.ARCHIVE, Routes.SETTINGS)

    fun minsAgo(t: Long?): Long? = t?.let { (System.currentTimeMillis() - it) / 60000 }

    Scaffold(
        containerColor = a.bg,
        bottomBar = { if (showNav) BottomNav(route) { nav.navigateTab(it) } }
    ) { pad ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(pad)) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    batteryAcknowledged = batteryAck,
                    onAcknowledgeBattery = { v -> scope.launch { container.settingsStore.setBatteryAcknowledged(v) } },
                    onComplete = { nav.navigate(Routes.ALLOWLIST_SETUP) }
                )
            }
            composable(Routes.ALLOWLIST_SETUP) {
                AllowlistScreen(viewModel(factory = vmFactory), showBack = false, onBack = {})
                // continue affordance: a simple bottom button via overlay
                SetupContinue {
                    scope.launch { container.settingsStore.setOnboardingDone(true) }
                    nav.navigate(Routes.TODAY) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            }
            composable(Routes.ALLOWLIST) {
                AllowlistScreen(viewModel(factory = vmFactory), showBack = true, onBack = { nav.popBackStack() })
            }
            composable(Routes.TODAY) {
                HomeScreen(
                    dateLabel = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                    slotCards = slotCards, times = times, topicCounts = topicCounts,
                    listeningCount = allowlist.size, listeningLabels = listeningLabels,
                    onOpenDigest = { nav.navigate(Routes.digest(it)) },
                    onEditAllowlist = { nav.navigate(Routes.ALLOWLIST) }
                )
            }
            composable(Routes.ARCHIVE) {
                ArchiveScreen(container.database) { nav.navigate(Routes.digest(it)) }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    themeMode = ThemeMode.from(themeMode),
                    onSetTheme = { scope.launch { container.settingsStore.setThemeMode(it.name) } },
                    times = times,
                    onSetTimes = {
                        scope.launch {
                            container.settingsStore.setDigestTimes(it)
                            // Re-arm the next-slot alarm — without this a time change only takes
                            // effect after the next digest/catch-up run (the stale alarm still fires
                            // at the old time).
                            DigestAlarmScheduler.scheduleNext(context)
                        }
                    },
                    allowlistSize = allowlist.size,
                    health = HealthStatus(minsAgo(lastAlive), minsAgo(lastCaptured), batteryOk),
                    onOpenAllowlist = { nav.navigate(Routes.ALLOWLIST) },
                    onExport = { exportLauncher.launch(ArchiveExporter.suggestedFileName()) }
                )
            }
            composable(
                Routes.DIGEST,
                arguments = listOf(navArgument("digestId") { type = NavType.StringType })
            ) { entry ->
                val id = entry.arguments?.getString("digestId") ?: return@composable
                DigestDetailScreen(id, container.database) { nav.popBackStack() }
            }
        }
    }
}

private fun androidx.navigation.NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(Routes.TODAY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun BottomNav(current: String?, onSelect: (String) -> Unit) {
    val a = LocalAlmanac.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(a.card)
            .navigationBarsPadding()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        NavItem("Today", Icons.Filled.Today, current == Routes.TODAY) { onSelect(Routes.TODAY) }
        NavItem("Archive", Icons.Filled.Inventory2, current == Routes.ARCHIVE) { onSelect(Routes.ARCHIVE) }
        NavItem("Settings", Icons.Filled.Settings, current == Routes.SETTINGS) { onSelect(Routes.SETTINGS) }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val a = LocalAlmanac.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(13.dp))
                .background(if (active) a.accent.copy(alpha = 0.13f) else androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 18.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) a.accent else a.inkMed, modifier = Modifier.size(22.dp))
        }
        Text(label, style = body(10.5), color = if (active) a.ink else a.inkLow)
    }
}

/** First-run "Done" affordance pinned at the bottom of the setup allowlist. */
@Composable
private fun SetupContinue(onDone: () -> Unit) {
    val a = LocalAlmanac.current
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.BottomCenter) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(a.accent)
                .clickable(onClick = onDone)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Done", style = body(14.0), color = a.accentInk)
        }
    }
}
