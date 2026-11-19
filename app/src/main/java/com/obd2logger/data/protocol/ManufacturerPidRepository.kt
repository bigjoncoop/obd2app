package com.obd2logger.data.protocol

import android.content.Context
import android.net.Uri
import com.obd2logger.data.logging.RawSessionLogRepository
import com.obd2logger.domain.model.ManufacturerPidDefinition
import com.obd2logger.domain.model.ManufacturerPidState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads bundled read-only manufacturer PID JSON files from `assets/mfr_pids/`
 * plus user-imported JSON files from `filesDir/custom_mfr_pids/`, manages per-PID
 * detection/enable states, and coordinates VIN-based auto-probing.
 */
@Singleton
class ManufacturerPidRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prober: ManufacturerPidProber,
    private val logRepository: RawSessionLogRepository
) {
    private val _pidStates = MutableStateFlow<List<ManufacturerPidState>>(emptyList())
    val pidStates: StateFlow<List<ManufacturerPidState>> = _pidStates.asStateFlow()

    private val _detectedMakes = MutableStateFlow<Set<String>>(emptySet())
    val detectedMakes: StateFlow<Set<String>> = _detectedMakes.asStateFlow()

    private val _lastImportMessage = MutableStateFlow<String?>(null)
    val lastImportMessage: StateFlow<String?> = _lastImportMessage.asStateFlow()

    private val customDir: File
        get() = File(context.filesDir, "custom_mfr_pids").apply { mkdirs() }

    init {
        reloadAllDatabases()
    }

    fun reloadAllDatabases() {
        val merged = linkedMapOf<String, ManufacturerPidDefinition>()

        // 1. Load bundled JSON files from assets/mfr_pids/
        runCatching {
            val assetFiles = context.assets.list(ASSETS_DIR).orEmpty().filter { it.endsWith(".json") }
            for (fileName in assetFiles.sorted()) {
                val jsonText = context.assets.open("$ASSETS_DIR/$fileName")
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }
                ManufacturerPidParser.parseJson(jsonText, isUserImported = false)
                    .onSuccess { list -> list.forEach { merged[it.id] = it } }
                    .onFailure { err -> logRepository.logError("Failed to parse asset $fileName: ${err.message}") }
            }
        }

        // 2. Load user-imported JSON files from filesDir/custom_mfr_pids/
        customDir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                runCatching { file.readText(Charsets.UTF_8) }
                    .mapCatching { text -> ManufacturerPidParser.parseJson(text, isUserImported = true).getOrThrow() }
                    .onSuccess { list -> list.forEach { merged[it.id] = it } }
                    .onFailure { err -> logRepository.logError("Failed to parse custom PID file ${file.name}: ${err.message}") }
            }

        val previousById = _pidStates.value.associateBy { it.definition.id }
        _pidStates.value = merged.values.map { def ->
            val prev = previousById[def.id]
            ManufacturerPidState(
                definition = def,
                detected = prev?.detected ?: false,
                enabled = prev?.enabled ?: false,
                latestValue = prev?.latestValue ?: "-",
                lastUpdatedMs = prev?.lastUpdatedMs
            )
        }
    }

    /**
     * Imports a user-supplied JSON file from storage (SAF Uri), validating syntax,
     * safe formulas, and read-only OBD/UDS services before persisting.
     */
    fun importJsonFromUri(uri: Uri): Result<Int> {
        return runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalArgumentException("Unable to open selected file")

            val parsed = ManufacturerPidParser.parseJson(text, isUserImported = true).getOrThrow()
            val targetName = "custom_${System.currentTimeMillis()}.json"
            File(customDir, targetName).writeText(text, Charsets.UTF_8)
            reloadAllDatabases()
            val msg = "Imported ${parsed.size} read-only PID(s) from JSON."
            _lastImportMessage.value = msg
            logRepository.logInfo(msg)
            parsed.size
        }.onFailure { err ->
            val msg = "PID import rejected: ${err.message}"
            _lastImportMessage.value = msg
            logRepository.logError(msg)
        }
    }

    /**
     * Selects candidate PIDs matching the VIN's WMI make (plus any universal PIDs with empty `makes`),
     * probes each once while polling is paused, marks responding PIDs as detected & enabled by default,
     * and restores the default OBD header.
     */
    suspend fun autoDetectFromVin(vin: String, protocol: String): List<ManufacturerPidDefinition> {
        val makes = VinWmiDecoder.detectMakesFromVin(vin)
        _detectedMakes.value = makes

        val allDefs = _pidStates.value.map { it.definition }
        val candidates = if (makes.isEmpty()) {
            allDefs
        } else {
            allDefs.filter { def ->
                def.makes.isEmpty() || def.makes.any { make ->
                    makes.any { detected -> detected.equals(make, ignoreCase = true) }
                }
            }
        }

        val responded = prober.probeCandidates(candidates, protocol)
        val now = System.currentTimeMillis()

        _pidStates.update { current ->
            current.map { state ->
                val valStr = responded[state.definition.id]
                if (valStr != null) {
                    state.copy(
                        detected = true,
                        enabled = true,
                        latestValue = valStr,
                        lastUpdatedMs = now
                    )
                } else {
                    state.copy(
                        detected = false,
                        // Default off unless detected
                        enabled = state.enabled && state.detected
                    )
                }
            }
        }

        logRepository.logInfo(
            "Manufacturer PID auto-detect (makes=${makes.ifEmpty { setOf("Unknown/All") }}): " +
                "${responded.size}/${candidates.size} responded."
        )
        return _pidStates.value.filter { it.enabled }.map { it.definition }
    }

    fun setPidEnabled(pidId: String, enabled: Boolean) {
        _pidStates.update { current ->
            current.map { state ->
                if (state.definition.id == pidId) state.copy(enabled = enabled) else state
            }
        }
    }

    fun enabledDefinitions(): List<ManufacturerPidDefinition> {
        return _pidStates.value.filter { it.enabled }.map { it.definition }
    }

    fun updateValues(valuesById: Map<String, String>, timestampMs: Long) {
        _pidStates.update { current ->
            current.map { state ->
                if (!state.enabled) {
                    state
                } else {
                    val v = valuesById[state.definition.id]
                    if (v != null) {
                        state.copy(latestValue = v, lastUpdatedMs = timestampMs)
                    } else {
                        state.copy(latestValue = "-")
                    }
                }
            }
        }
    }

    fun clearValuesOnDisconnect() {
        _pidStates.update { current ->
            current.map { it.copy(latestValue = "-") }
        }
    }

    companion object {
        private const val ASSETS_DIR = "mfr_pids"
    }
}
