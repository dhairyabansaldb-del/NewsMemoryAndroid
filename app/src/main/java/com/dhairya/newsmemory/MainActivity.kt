package com.dhairya.newsmemory

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dhairya.newsmemory.ui.allowlist.AllowlistScreen
import com.dhairya.newsmemory.ui.allowlist.AllowlistViewModel
import com.dhairya.newsmemory.ui.home.HomeScreen
import com.dhairya.newsmemory.ui.onboarding.OnboardingScreen
import com.dhairya.newsmemory.ui.theme.NewsMemoryTheme
import com.dhairya.newsmemory.util.Permissions
import kotlinx.coroutines.launch

private object Routes {
    const val ONBOARDING = "onboarding"
    const val ALLOWLIST_SETUP = "allowlist_setup"   // first-run, with continue button
    const val ALLOWLIST = "allowlist"               // from home/settings, no continue button
    const val HOME = "home"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as App).container
        setContent {
            NewsMemoryTheme {
                NewsMemoryApp(container)
            }
        }
    }
}

@Composable
private fun NewsMemoryApp(container: AppContainer) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()

    val onboardingDone by container.settingsStore.onboardingDone.collectAsState(initial = null)
    val batteryAck by container.settingsStore.batteryAcknowledged.collectAsState(initial = false)
    val allowlist by container.settingsStore.allowlist.collectAsState(initial = emptySet())
    val capturedCount by container.database.rawNotificationDao().count().collectAsState(initial = 0)

    // Re-shown if any grant is revoked (EDD §8): re-check core grants on every resume.
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

    val done = onboardingDone ?: return   // settle DataStore before routing
    val startRoute = if (done && coreGranted) Routes.HOME else Routes.ONBOARDING

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
                capturedCount = capturedCount,
                allowlistSize = allowlist.size,
                onEditAllowlist = { navController.navigate(Routes.ALLOWLIST) }
            )
        }
    }
}
