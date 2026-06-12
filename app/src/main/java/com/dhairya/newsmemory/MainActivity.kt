package com.dhairya.newsmemory

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dhairya.newsmemory.pipeline.DigestNotifier
import com.dhairya.newsmemory.pipeline.DigestSlot
import com.dhairya.newsmemory.pipeline.WindowCalculator
import com.dhairya.newsmemory.ui.allowlist.AllowlistScreen
import com.dhairya.newsmemory.ui.allowlist.AllowlistViewModel
import com.dhairya.newsmemory.ui.archive.ArchiveScreen
import com.dhairya.newsmemory.ui.digest.DigestDetailScreen
import com.dhairya.newsmemory.ui.home.HomeScreen
import com.dhairya.newsmemory.ui.home.SlotCard
import com.dhairya.newsmemory.ui.onboarding.OnboardingScreen
import com.dhairya.newsmemory.ui.theme.NewsMemoryTheme
import com.dhairya.newsmemory.util.Permissions
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private object Routes {
    const val ONBOARDING = "onboarding"
    const val ALLOWLIST_SETUP = "allowlist_setup"
    const val ALLOWLIST = "allowlist"
    const val HOME = "home"
    const val ARCHIVE = "archive"
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
            NewsMemoryTheme {
                NewsMemoryApp(container, pendingDigestId.value) {
                    pendingDigestId.value = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingDigestId.value = intent.getStringExtra(DigestNotifier.EXTRA_DIGEST_ID)
    }
}

@Composable
private fun NewsMemoryApp(
    container: AppContainer,
    pendingDigestId: String?,
    onDigestConsumed: () -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val onboardingDone by container.settingsStore.onboardingDone.collectAsState(initial = null)
    val batteryAck by container.settingsStore.batteryAcknowledged.collectAsState(initial = false)
    val allowlist by container.settingsStore.allowlist.collectAsState(initial = emptySet())
    val capturedCount by container.database.rawNotificationDao().count().collectAsState(initial = 0)
    val lastAlive by container.settingsStore.lastAlive.collectAsState(initial = null)
    val lastCaptured by container.database.rawNotificationDao().lastCapturedFlow()
        .collectAsState(initial = null)
    val times by container.settingsStore.digestTimes
        .collectAsState(initial = com.dhairya.newsmemory.pipeline.DigestTimes())
    val allDigests by container.database.digestDao().allDigests()
        .collectAsState(initial = emptyList())

    // Today's three slot cards
    val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val slotCards = DigestSlot.entries.map { slot ->
        val id = "$today-${slot.code}"
        SlotCard(
            slot = slot,
            digest = allDigests.firstOrNull { it.id == id },
            deliveryMinutes = times.minutesFor(slot)
        )
    }

    // Re-shown if any grant is revoked (EDD §8)
    var coreGranted by remember { mutableStateOf(Permissions.grantState(context).coreGranted) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                coreGranted = Permissions.grantState(context).coreGranted
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val done = onboardingDone ?: return
    val startRoute = if (done && coreGranted) Routes.HOME else Routes.ONBOARDING

    // Notification tap → straight to that digest
    LaunchedEffect(pendingDigestId, done) {
        if (pendingDigestId != null && done) {
            navController.navigate(Routes.digest(pendingDigestId))
            onDigestConsumed()
        }
    }

    val allowlistVmFactory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AllowlistViewModel(
                    context.packageManager,
                    container.settingsStore,
                    context.packageName
                ) as T
        }
    }

    NavHost(navController = navController, startDestination = startRoute) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                batteryAcknowledged = batteryAck,
                onAcknowledgeBattery = { value ->
                    scope.launch { container.settingsStore.setBatteryAcknowledged(value) }
                },
                onComplete = { navController.navigate(Routes.ALLOWLIST_SETUP) }
            )
        }
        composable(Routes.ALLOWLIST_SETUP) {
            AllowlistScreen(
                viewModel = viewModel(factory = allowlistVmFactory),
                continueLabel = "Done",
                onContinue = {
                    scope.launch { container.settingsStore.setOnboardingDone(true) }
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.ALLOWLIST) {
            AllowlistScreen(
                viewModel = viewModel(factory = allowlistVmFactory),
                continueLabel = null,
                onContinue = {}
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                slotCards = slotCards,
                times = times,
                capturedCount = capturedCount,
                allowlistSize = allowlist.size,
                listenerLastAlive = lastAlive,
                lastCapturedAt = lastCaptured,
                onOpenDigest = { navController.navigate(Routes.digest(it)) },
                onOpenArchive = { navController.navigate(Routes.ARCHIVE) },
                onEditAllowlist = { navController.navigate(Routes.ALLOWLIST) }
            )
        }
        composable(Routes.ARCHIVE) {
            ArchiveScreen(
                db = container.database,
                onOpenDigest = { navController.navigate(Routes.digest(it)) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            Routes.DIGEST,
            arguments = listOf(navArgument("digestId") { type = NavType.StringType })
        ) { backStack ->
            val digestId = backStack.arguments?.getString("digestId") ?: return@composable
            DigestDetailScreen(
                digestId = digestId,
                db = container.database,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
