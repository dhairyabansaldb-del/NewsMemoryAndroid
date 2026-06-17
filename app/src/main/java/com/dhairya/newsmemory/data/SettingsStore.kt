package com.dhairya.newsmemory.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dhairya.newsmemory.pipeline.DigestTimes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Preferences: allowlist, digest times, heartbeat, onboarding acks. EDD §2, §4.3.
 * The allowlist is additionally exposed as a hot [StateFlow] snapshot so the listener's
 * first-line gate never suspends (EDD §4.1).
 */
class SettingsStore(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val ALLOWLIST = stringSetPreferencesKey("allowlist")
        val MORNING = intPreferencesKey("morning_minutes")
        val EVENING = intPreferencesKey("evening_minutes")
        val NIGHT = intPreferencesKey("night_minutes")
        val LAST_ALIVE = longPreferencesKey("listener_last_alive")
        val BATTERY_ACK = booleanPreferencesKey("battery_risk_acknowledged")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val LIMITED_SUPPORT = stringSetPreferencesKey("limited_support_packages")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    // --- Allowlist ---

    val allowlist: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.ALLOWLIST] ?: emptySet() }

    /** Hot snapshot for the capture gate; collected once, updated on every edit. */
    private val allowlistState: StateFlow<Set<String>> =
        allowlist.stateIn(scope, SharingStarted.Eagerly, runBlocking {
            context.dataStore.data.map { it[Keys.ALLOWLIST] ?: emptySet() }.first()
        })

    fun allowlistSnapshot(): Set<String> = allowlistState.value

    suspend fun setAllowlist(packages: Set<String>) {
        context.dataStore.edit { it[Keys.ALLOWLIST] = packages }
    }

    // --- Digest times ---

    val digestTimes: Flow<DigestTimes> = context.dataStore.data.map { prefs ->
        DigestTimes(
            morningMinutes = prefs[Keys.MORNING] ?: DigestTimes().morningMinutes,
            eveningMinutes = prefs[Keys.EVENING] ?: DigestTimes().eveningMinutes,
            nightMinutes = prefs[Keys.NIGHT] ?: DigestTimes().nightMinutes
        )
    }

    suspend fun digestTimesSnapshot(): DigestTimes = digestTimes.first()

    suspend fun setDigestTimes(times: DigestTimes) {
        context.dataStore.edit {
            it[Keys.MORNING] = times.morningMinutes
            it[Keys.EVENING] = times.eveningMinutes
            it[Keys.NIGHT] = times.nightMinutes
        }
    }

    // --- Listener heartbeat (EDD §4.3) ---

    val lastAlive: Flow<Long?> = context.dataStore.data.map { it[Keys.LAST_ALIVE] }

    suspend fun heartbeat(now: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[Keys.LAST_ALIVE] = now }
    }

    // --- Onboarding ---

    val batteryAcknowledged: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.BATTERY_ACK] ?: false }

    suspend fun setBatteryAcknowledged(value: Boolean) {
        context.dataStore.edit { it[Keys.BATTERY_ACK] = value }
    }

    val onboardingDone: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setOnboardingDone(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = value }
    }

    // --- Apps whose notifications can't be parsed (allowlist "limited support" flag) ---

    val limitedSupport: Flow<Set<String>> =
        context.dataStore.data.map { it[Keys.LIMITED_SUPPORT] ?: emptySet() }

    suspend fun flagLimitedSupport(packageName: String) {
        context.dataStore.edit {
            it[Keys.LIMITED_SUPPORT] = (it[Keys.LIMITED_SUPPORT] ?: emptySet()) + packageName
        }
    }

    // --- Theme (light / dark / auto; default auto) ---

    val themeMode: Flow<String> =
        context.dataStore.data.map { it[Keys.THEME_MODE] ?: "AUTO" }

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }
}
