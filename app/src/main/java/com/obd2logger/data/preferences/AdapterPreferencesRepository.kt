package com.obd2logger.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.obd2logger.domain.model.AdapterConfig
import com.obd2logger.domain.model.TransportType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "obd2_preferences")

data class SavedWifiEndpoint(
    val ip: String = "192.168.0.10",
    val port: Int = 35000
)

enum class PollingSpeed(
    val label: String,
    val description: String,
    val interCommandDelayMs: Long,
    val cycleDelayMs: Long
) {
    FAST("Fast", "Maximum update rate (25ms / 40ms)", 25L, 40L),
    NORMAL("Balanced", "Moderate rate for older adapters (60ms / 120ms)", 60L, 120L),
    SLOW("Battery Saver", "Relaxed rate (120ms / 300ms)", 120L, 300L)
}

@Singleton
class AdapterPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val LAST_TRANSPORT_TYPE = stringPreferencesKey("last_transport_type")
        val LAST_BT_ADDRESS = stringPreferencesKey("last_bt_address")
        val LAST_BT_NAME = stringPreferencesKey("last_bt_name")
        val LAST_BT_PIN = stringPreferencesKey("last_bt_pin")
        val WIFI_IP = stringPreferencesKey("wifi_ip")
        val WIFI_PORT = intPreferencesKey("wifi_port")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val POLLING_SPEED = stringPreferencesKey("polling_speed")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }

    /**
     * Reactive stream of the last connected adapter configuration for one-tap reconnect.
     */
    val lastAdapterConfig: Flow<AdapterConfig?> = context.dataStore.data.map { prefs ->
        val typeStr = prefs[Keys.LAST_TRANSPORT_TYPE] ?: return@map null
        val type = runCatching { TransportType.valueOf(typeStr) }.getOrNull() ?: return@map null
        when (type) {
            TransportType.BLUETOOTH_CLASSIC -> {
                val addr = prefs[Keys.LAST_BT_ADDRESS] ?: return@map null
                val name = prefs[Keys.LAST_BT_NAME] ?: ""
                val pin = prefs[Keys.LAST_BT_PIN] ?: "1234"
                AdapterConfig.BluetoothClassic(address = addr, name = name, preferredPin = pin)
            }
            TransportType.BLUETOOTH_LE -> {
                val addr = prefs[Keys.LAST_BT_ADDRESS] ?: return@map null
                val name = prefs[Keys.LAST_BT_NAME] ?: ""
                AdapterConfig.BluetoothLe(address = addr, name = name)
            }
            TransportType.WIFI -> {
                val ip = prefs[Keys.WIFI_IP] ?: "192.168.0.10"
                val port = prefs[Keys.WIFI_PORT] ?: 35000
                AdapterConfig.Wifi(ip = ip, port = port)
            }
            TransportType.SIMULATOR -> AdapterConfig.Simulator
        }
    }

    /**
     * Reactive stream of the user's saved Wi-Fi IP and port.
     */
    val savedWifiEndpoint: Flow<SavedWifiEndpoint> = context.dataStore.data.map { prefs ->
        SavedWifiEndpoint(
            ip = prefs[Keys.WIFI_IP] ?: "192.168.0.10",
            port = prefs[Keys.WIFI_PORT] ?: 35000
        )
    }

    /**
     * Reactive stream of the preferred Bluetooth pairing PIN (1234 / 0000 / 6789).
     */
    val preferredBtPin: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[Keys.LAST_BT_PIN] ?: "1234"
    }

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    val pollingSpeed: Flow<PollingSpeed> = context.dataStore.data.map { prefs ->
        val name = prefs[Keys.POLLING_SPEED] ?: PollingSpeed.FAST.name
        runCatching { PollingSpeed.valueOf(name) }.getOrDefault(PollingSpeed.FAST)
    }

    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.KEEP_SCREEN_ON] ?: false
    }

    suspend fun saveLastAdapter(config: AdapterConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_TRANSPORT_TYPE] = config.transportType.name
            when (config) {
                is AdapterConfig.BluetoothClassic -> {
                    prefs[Keys.LAST_BT_ADDRESS] = config.address
                    prefs[Keys.LAST_BT_NAME] = config.name
                    prefs[Keys.LAST_BT_PIN] = config.preferredPin
                }
                is AdapterConfig.BluetoothLe -> {
                    prefs[Keys.LAST_BT_ADDRESS] = config.address
                    prefs[Keys.LAST_BT_NAME] = config.name
                }
                is AdapterConfig.Wifi -> {
                    prefs[Keys.WIFI_IP] = config.ip
                    prefs[Keys.WIFI_PORT] = config.port
                }
                AdapterConfig.Simulator -> Unit
            }
        }
    }

    suspend fun saveWifiEndpoint(ip: String, port: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WIFI_IP] = ip.trim()
            prefs[Keys.WIFI_PORT] = port
        }
    }

    suspend fun savePreferredPin(pin: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.LAST_BT_PIN] = pin.trim()
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setPollingSpeed(speed: PollingSpeed) {
        context.dataStore.edit { prefs ->
            prefs[Keys.POLLING_SPEED] = speed.name
        }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.KEEP_SCREEN_ON] = enabled
        }
    }
}
