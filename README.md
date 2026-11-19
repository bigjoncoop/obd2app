# OBD2 Logger (Android — Kotlin, Jetpack Compose, Material 3)

Native Android app (`minSdk 26`, `targetSdk 35`) for connecting to any ELM327-compatible OBD2 adapter (Bluetooth Classic SPP, Bluetooth LE GATT, Wi-Fi TCP, or built-in Simulator), reading diagnostic trouble codes, Mode 06 monitor tests, and vehicle info, and recording Torque Pro–compatible 1 Hz CSV logs + companion session exports for AI OBD2 diagnostics.

## Features Implemented

### Phase 1 — Adapter Transports & ELM327 Protocol
- **Transports (`ObdTransport`)**:
  - **Bluetooth Classic (SPP)**: Scans paired and newly discovered devices, handles PIN pairing (`1234` / `0000` / `6789`), cancels discovery before connecting, and falls back to `createRfcommSocket(1)` if standard SPP fails.
  - **Bluetooth LE (GATT)**: Requests MTU 517, auto-probes common GATT service/characteristic pairs (`FFF0`, `FFE0`, `18F0`, Nordic UART `6E400001...`, and notify+write fallback), queues GATT operations sequentially, and buffers notifications until `>`.
  - **Wi-Fi (TCP)**: Binds socket connections to the active Wi-Fi network via `ConnectivityManager` and probes the saved `IP:port` first, followed by common adapter defaults (`192.168.0.10:35000`, `192.168.4.1:35000`, `192.168.1.5:35000`, `192.168.0.74:23`, `192.168.1.1:35000`).
  - **Simulator**: Virtual ELM327 v1.5 adapter selectable in the Connect sheet with a prominent `SIMULATOR` banner (supports Mode 01, 02, 03, 04, 06, 07, 09, 0A, and sample Mode 22 `ATSH` commands).
- **ELM327 Queue & States**:
  - Single-command queue (`commandMutex`) waiting for `>` prompt; `ATZ` -> `ATE0` -> `ATL0` -> `ATS0` -> `ATH0` -> `ATAT1` -> `ATSP0` -> `0100` -> `0100..01A0` bitmask discovery.
  - States: **Disconnected** (Red) -> **Connecting** (Yellow pulsing) -> **Adapter Ready** (Yellow) -> **Connected** (Green).
  - 3-second idle heartbeat and automatic reconnect on unexpected transport drops.

### Phase 2 — Prioritized Live Mode 01 Polling
- Data-driven `PidTable` (`id`, `name`, `expectedBytes`, `formula`, `imperialUnit`, `priority`, `decimals`) with `Mass Air Flow (MAF)` in **`g/s`**.
- `SensorPollingEngine` polls only vehicle-supported PIDs using `HIGH` (every cycle), `MEDIUM` (every 3rd cycle), and `LOW` (every 8th cycle) tiers, with multi-PID batching (`010C0D11`) and automatic single-PID fallback.

### Phase 3 — Diagnostics & Standalone 137-Column 1 Hz CSV Logging
- **Session-Start Diagnostic Sequence (`SessionDiagnosticsRunner`)**:
  - Gracefully pauses live polling when **START LOGGING** is tapped so commands never overlap.
  - Reads Stored (`03`), Pending (`07`), and Permanent (`0A`) DTCs decoded via a bundled offline SAE J2012 dictionary (`SaeDtcTable`), MIL status & readiness monitors (`0101`, `0141`), Freeze Frame (`020200` + key Mode 02 values), Mode 09 Vehicle Info (`0902` VIN, `0904` Calibration ID, `0906` CVN, `090A` ECU Name), OBD Standard (`011C`), and Protocol (`ATDP`).
- **1 Hz Torque-Compatible Standalone Main CSV**:
  - Saved via `MediaStore` to `Documents/OBDLogs` as `trackLog-YYYY-Mon-DD_HH-MM-SS.csv`.
  - Fixed **137-column** schema across every file regardless of vehicle PID support (`"-"` for unsupported/unavailable cells, RFC 4180 escaping, `Device Time` in `DD-Mon-YYYY HH:MM:SS.mmm` local time).
  - Runs inside `ObdLoggingService` (foreground service + partial wake lock) with per-row disk flushing; keeps the CSV open and writes `"-"` sensor cells on mid-log disconnects until auto-reconnect resumes.

### Phase 4 — Mode 06, Clear Codes, Manufacturer PIDs & Session ZIP
- **Mode 06 On-Board Monitor Tests (`Mode06Decoder`, `Mode06Tables`, `Mode06Runner`)**:
  - Queries supported MIDs (`0600`, `0620`, `0640`, ...) at session start (`Phase = Start`), polls misfire-counter MIDs (`0xA1..0xB1`) every ~30s during logging (`Phase = During`), and re-reads all MIDs on stop (`Phase = End`).
  - Handles CAN (9-byte multi-frame ISO-TP) and legacy non-CAN (6-byte) responses, decodes SAE MIDs/TIDs/UASIDs with PASS/FAIL/UNKNOWN evaluation, and logs `"Unknown"` with raw values when an ID is unknown.
  - Displayed in the **Monitor Tests (Mode 06)** card with failing tests highlighted at the top.
- **Clear Codes with Snapshot Guard (`ClearCodesManager`, `ClearCodesWarningDialog`)**:
  - **Clear Codes (Mode 04)** button in the results card is enabled **only** when Connected and NOT logging.
  - Always re-reads DTCs, freeze frame, and readiness monitors and saves `trackLog-<timestamp>_precleared.json` in `Documents/OBDLogs` **before** sending `04` (clearing is blocked if snapshot creation fails).
  - Requires typing `CLEAR` in a black/yellow warning dialog explaining emissions readiness reset, freeze frame erasure, MIL recurrence, and non-clearable permanent codes.
- **Manufacturer-Specific Read-Only PIDs (`ManufacturerPidRepository`, `ManufacturerPidProber`, `SafeFormulaEvaluator`)**:
  - JSON files bundled in `app/src/main/assets/mfr_pids/` (`gm.json`, `ford.json`, `toyota_lexus.json`, `honda.json`, `hyundai_kia.json`, `vw_audi.json`, `bmw.json`) plus SAF file import for user JSON files.
  - Uses `ATSH` (and optional `ATCRA` / `ATFCSH` / `ATFCSD` / `ATFCSM`) and **always** restores the default functional header (`7DF` / `18DB33F1`) in a `finally` block.
  - Uses a safe arithmetic expression parser (never `eval` or scripting) and strictly enforces a read-only service whitelist (`01`, `09`, `1A`, `21`, `22`).
- **Companion Files & Streaming Session ZIP (`SessionZipBuilder`)**:
  - Main CSV remains completely standalone and unchanged for single-file uploads.
  - When logging stops, `trackLog-<ts>_session.zip` is streamed in the background (8 KiB buffer) containing `manifest.json`, the main CSV, `_mode06.csv`, `_extended.csv` (if any), and `_precleared.json` (if any).
  - **Log Saved** screen offers **Share Main CSV** and **Share Full Session (ZIP)**; **Past Logs** groups files per session with Share Main CSV, Share ZIP, Open Folder, and Delete All Session Files.

---

## How to Build & Run Tests

```bash
# Run all unit tests
./gradlew testDebugUnitTest

# Build debug APK
./gradlew assembleDebug
```

Debug APK output path:
`app/build/outputs/apk/debug/app-debug.apk`

---

## Companion File Formats

All files for a session live in `Documents/OBDLogs` and share the exact timestamp stem `trackLog-YYYY-Mon-DD_HH-MM-SS` and `Session ID`:

### 1. Main CSV: `trackLog-<ts>.csv`
Standalone 137-column Torque-compatible sensor & diagnostic log (see Schema Reference below).

### 2. Mode 06 Monitor Tests CSV: `trackLog-<ts>_mode06.csv`
One row per monitor test record (`Start`, `During`, `End`):
```text
Device Time,Session ID,VIN,Phase,MID,MID Name,TID,UASID,Unit,Test Value,Min Limit,Max Limit,Result,Raw Hex
```
- `Phase`: `Start`, `During`, or `End`
- `Result`: `PASS`, `FAIL`, or `UNKNOWN`

### 3. Manufacturer Extended CSV: `trackLog-<ts>_extended.csv`
Created when one or more manufacturer PIDs are enabled:
```text
Device Time,Session ID,<Pid Name 1(Unit)>,<Pid Name 2(Unit)>,...
```
- Unavailable or disconnected cells write `"-"` (never fake zeros).

### 4. Pre-Clear Backup JSON: `trackLog-<ts>_precleared.json`
Saved before every Mode 04 clear operation:
```json
{
  "timestampIso": "2026-11-18T19:30:00Z",
  "deviceTime": "18-Nov-2026 14:30:00.000",
  "vin": "1HGCM82633A004352",
  "milOn": true,
  "dtcCount": 2,
  "storedDtcs": [{"code": "P0171", "description": "System Too Lean (Bank 1)"}],
  "pendingDtcs": [],
  "permanentDtcs": [],
  "freezeFrame": { ... },
  "readinessSinceCleared": { ... },
  "readinessThisDriveCycle": { ... }
}
```

### 5. Session Archive & Manifest: `trackLog-<ts>_session.zip`
Contains all produced session files plus `manifest.json`:
```json
{
  "appVersion": "1.0.0",
  "sessionId": "550e8400-e29b-41d4-a716-446655440000",
  "vin": "1HGCM82633A004352",
  "startTime": "18-Nov-2026 14:32:05.000",
  "endTime": "18-Nov-2026 14:42:05.000",
  "schemaVersion": "1.0-phase4",
  "units": "imperial",
  "files": [
    {"filename": "trackLog-2026-Nov-18_14-32-05.csv", "type": "main", "rowCount": 600},
    {"filename": "trackLog-2026-Nov-18_14-32-05_mode06.csv", "type": "mode06", "rowCount": 14},
    {"filename": "trackLog-2026-Nov-18_14-32-05_extended.csv", "type": "extended", "rowCount": 600}
  ]
}
```

---

## Extending the App

### Adding Manufacturer-Specific PIDs (No Code Changes)
Add entries to any file in `app/src/main/assets/mfr_pids/*.json`, or import a `.json` file at runtime from the **Mfr PIDs** tab (`IMPORT JSON`):

```json
{
  "pids": [
    {
      "id": "gm_tft_1940",
      "name": "GM Transmission Fluid Temp",
      "makes": ["GM", "Chevrolet", "GMC"],
      "models": [],
      "years": "2007-2020",
      "moduleName": "TCM",
      "requestHeader": "7E2",
      "receiveAddress": "7EA",
      "command": "221940",
      "expectedBytes": 1,
      "formula": "(A - 40) * 9 / 5 + 32",
      "unit": "°F",
      "min": -40,
      "max": 350,
      "decimals": 0,
      "category": "Transmission",
      "source": "GM Mode 22 DID 1940"
    }
  ]
}
```
- **Safe Formula Syntax**: Arithmetic `+`, `-`, `*`, `/`, `%`, parentheses, unsigned bytes `A..H`, signed bytes `S_A..S_D`, and helper functions `u16(hi, lo)`, `signed8(x)`, `signed16(hi, lo)`, `bit(x, n)`, `min(a, b)`, `max(a, b)`, `abs(x)`.
- **Optional CAN Flow-Control Fields**: `receiveAddress` (`ATCRA`), `flowControlHeader` (`ATFCSH`), `flowControlData` (`ATFCSD`), `flowControlMode` (`ATFCSM`).

### Safety Notes
- **Read-Only Enforcement**: `ManufacturerPidParser` strictly permits only read-only service prefixes (`01`, `09`, `1A`, `21`, `22`). Write (`2E`), actuator test (`2F`), routine control (`31`), security access (`27`), ECU reset (`11`), and flashing services (`34`–`37`) are rejected on load.
- **Header Restoration**: `ManufacturerPidProber` restores the default functional CAN header (`ATSH 7DF` / `18DB33F1`) and clears `ATCRA` / `ATFCSM` in a `finally` block after every probe or read cycle.
- **Clear Codes Guard**: Mode `04` is never triggered automatically and is blocked if the pre-clear JSON snapshot fails to save.

### Adding a New Main CSV Column or Mode 01 PID
1. **If it is a new Mode 01 PID**, add one `PidDefinition(...)` entry to `PidTable.entries` in `app/src/main/java/com/obd2logger/data/protocol/PidTable.kt`.
2. **Add one `CsvColumn(...)` entry** to `CsvSchema.columns` in `app/src/main/java/com/obd2logger/data/logging/CsvSchema.kt` (note: adding columns changes the 137-column schema).

---

## Main CSV Schema Reference (137 Fixed Columns)

| Group | Columns |
|---|---|
| **A) Time / GPS** | `GPS Time`, `Device Time`, `Longitude`, `Latitude`, `GPS Speed (Meters/second)`, `Altitude` |
| **B) Core Engine** | `Engine RPM(rpm)`, `Speed (OBD)(mph)`, `Engine Load(%)`, `Absolute Load Value(%)`, `Throttle Position(Manifold)(%)`, `Relative Throttle Position(%)`, `Absolute Throttle Position B(%)`, `Accelerator Pedal Position D(%)`, `Accelerator Pedal Position E(%)`, `Accelerator Pedal Position F(%)`, `Relative Accelerator Pedal Position(%)`, `Commanded Throttle Actuator(%)`, `Timing Advance(°)`, `Run time since engine start(s)` |
| **C) Air / Fuel** | `Mass Air Flow Rate(g/s)`, `Intake Manifold Pressure(psi)`, `Intake Air Temperature(°F)`, `Barometric pressure (from vehicle)(psi)`, `Ambient air temp(°F)`, `Fuel Trim Bank 1 Short Term(%)`, `Fuel Trim Bank 1 Long Term(%)`, `Fuel Trim Bank 2 Short Term(%)`, `Fuel Trim Bank 2 Long Term(%)`, `Fuel Status 1`, `Fuel Status 2`, `Fuel pressure(psi)`, `Fuel Rail Pressure(psi)`, `Fuel Rail Pressure (Absolute)(psi)`, `Fuel Level (From Engine ECU)(%)`, `Engine Fuel Rate(gal/h)`, `Fuel Injection Timing(°)`, `Commanded Equivalence Ratio(lambda)`, `Ethanol Fuel %(%)`, `Fuel Type` |
| **D) Temperatures** | `Engine Coolant Temperature(°F)`, `Engine oil temperature(°F)`, `Catalyst Temperature (Bank 1 Sensor 1)(°F)`, `Catalyst Temperature (Bank 2 Sensor 1)(°F)`, `Catalyst Temperature (Bank 1 Sensor 2)(°F)`, `Catalyst Temperature (Bank 2 Sensor 2)(°F)` |
| **E) Oxygen / Wideband** | `O2 Bank 1 Sensor 1 Voltage(V)`, `O2 Bank 1 Sensor 2 Voltage(V)`, `O2 Bank 2 Sensor 1 Voltage(V)`, `O2 Bank 2 Sensor 2 Voltage(V)`, `O2 Bank 1 Sensor 1 Short Term Trim(%)`, `O2 Bank 1 Sensor 2 Short Term Trim(%)`, `O2 Bank 2 Sensor 1 Short Term Trim(%)`, `O2 Bank 2 Sensor 2 Short Term Trim(%)`, `O2 Sensor 1..8 Wide Range Lambda` (8 columns), `O2 Sensor Locations` |
| **F) Emissions** | `Commanded EGR(%)`, `EGR Error(%)`, `Commanded Evaporative Purge(%)`, `Evap System Vapor Pressure(Pa)`, `Secondary Air Status` |
| **G) Electrical** | `Voltage (Control Module)(V)`, `Voltage (OBD Adapter)(V)` |
| **H) Torque** | `Driver's Demand Engine Torque(%)`, `Actual Engine Torque(%)`, `Engine Reference Torque(lb-ft)` |
| **I) Turbo / Diesel** | `Turbo/Boost Pressure(psi)`, `Exhaust Pressure(psi)`, `Turbocharger RPM(rpm)`, `Exhaust Gas Temp Bank 1(°F)`, `Exhaust Gas Temp Bank 2(°F)`, `DPF Inlet Temp(°F)`, `DPF Outlet Temp(°F)`, `DPF Differential Pressure`, `NOx Sensor Readings`, `Engine Friction Torque(%)` |
| **J) Counters** | `Distance travelled with MIL/CEL lit(mi)`, `Time run with MIL on(min)`, `Distance travelled since codes cleared(mi)`, `Time since codes cleared(min)`, `Warm-ups since codes cleared(count)` |
| **K) Readiness Monitors** | `Misfire Monitor`, `Fuel System Monitor`, `Components Monitor`, `Catalyst Monitor`, `Heated Catalyst Monitor`, `Evap Monitor`, `Secondary Air Monitor`, `A/C Refrigerant Monitor`, `O2 Sensor Monitor`, `O2 Sensor Heater Monitor`, `EGR/VVT Monitor`, `Ignition Type`, plus the same 12 columns suffixed with ` This Drive Cycle` |
| **L) Trouble Codes & Freeze Frame** | `MIL Status (On/Off)`, `DTC Count`, `Stored DTCs`, `Pending DTCs`, `Permanent DTCs`, `Freeze Frame DTC`, `FF Engine RPM`, `FF Speed`, `FF Engine Load`, `FF Coolant Temp`, `FF Fuel Trim B1 Short`, `FF Fuel Trim B1 Long`, `FF Intake Manifold Pressure`, `FF Intake Air Temp` |
| **M) Vehicle / Session Info** | `VIN`, `Calibration ID`, `CVN`, `OBD Standard`, `ECU Name`, `Protocol`, `Adapter Type`, `Adapter Firmware`, `App Version`, `Supported PIDs`, `Session ID` |
