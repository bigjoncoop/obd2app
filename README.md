# OBD2 Logger (Android — Kotlin, Jetpack Compose, Material 3)

Native Android app (`minSdk 26`, `targetSdk 35`) for connecting to any ELM327-compatible OBD2 adapter (Bluetooth Classic SPP, Bluetooth LE GATT, Wi-Fi TCP, or built-in Simulator), reading diagnostic trouble codes and vehicle info, and recording Torque Pro–compatible 1 Hz CSV logs for AI OBD2 diagnostics.

## Features Implemented

### Phase 1 — Adapter Transports & ELM327 Protocol
- **Transports (`ObdTransport`)**:
  - **Bluetooth Classic (SPP)**: Scans paired and newly discovered devices, handles PIN pairing (`1234` / `0000` / `6789`), cancels discovery before connecting, and falls back to `createRfcommSocket(1)` if standard SPP fails.
  - **Bluetooth LE (GATT)**: Requests MTU 517, auto-probes common GATT service/characteristic pairs (`FFF0`, `FFE0`, `18F0`, Nordic UART `6E400001...`, and notify+write fallback), queues GATT operations sequentially, and buffers notifications until `>`.
  - **Wi-Fi (TCP)**: Binds socket connections to the active Wi-Fi network via `ConnectivityManager` and probes the saved `IP:port` first, followed by common adapter defaults (`192.168.0.10:35000`, `192.168.4.1:35000`, `192.168.1.5:35000`, `192.168.0.74:23`, `192.168.1.1:35000`).
  - **Simulator**: Virtual ELM327 v1.5 adapter selectable in the Connect sheet with a prominent `SIMULATOR` banner.
- **ELM327 Queue & States**:
  - Single-command queue (`commandMutex`) waiting for `>` prompt; `ATZ` -> `ATE0` -> `ATL0` -> `ATS0` -> `ATH0` -> `ATAT1` -> `ATSP0` -> `0100` -> `0100..01A0` bitmask discovery.
  - States: **Disconnected** (Red) -> **Connecting** (Yellow pulsing) -> **Adapter Ready** (Yellow) -> **Connected** (Green).
  - 3-second idle heartbeat and automatic reconnect on unexpected transport drops.

### Phase 2 — Prioritized Live Mode 01 Polling
- Data-driven `PidTable` (`id`, `name`, `expectedBytes`, `formula`, `imperialUnit`, `priority`, `decimals`) with `Mass Air Flow (MAF)` in **`g/s`**.
- `SensorPollingEngine` polls only vehicle-supported PIDs using `HIGH` (every cycle), `MEDIUM` (every 3rd cycle), and `LOW` (every 8th cycle) tiers, with multi-PID batching (`010C0D11`) and automatic single-PID fallback.

### Phase 3 — Diagnostics, Foreground 1 Hz CSV Logging & UI
- **Session-Start Diagnostic Sequence (`SessionDiagnosticsRunner`)**:
  - Gracefully pauses live polling when **START LOGGING** is tapped so commands never overlap.
  - Reads Stored (`03`), Pending (`07`), and Permanent (`0A`) DTCs decoded via a bundled offline SAE J2012 dictionary (`SaeDtcTable`), MIL status & readiness monitors (`0101`, `0141`), Freeze Frame (`020200` + key Mode 02 values), Mode 09 Vehicle Info (`0902` VIN, `0904` Calibration ID, `0906` CVN, `090A` ECU Name), OBD Standard (`011C`), and Protocol (`ATDP`).
  - Displays a read-only **Diagnostic Results Card** on Home (codes, MIL status, VIN, freeze frame summary; no clear-codes feature).
- **1 Hz Torque-Compatible CSV Logging (`CsvSchema`, `CsvWriter`, `CsvLogStorage`, `ObdLoggingService`)**:
  - Saved via `MediaStore` to `Documents/OBDLogs` as `trackLog-YYYY-Mon-DD_HH-MM-SS.csv`.
  - Fixed **137-column** schema across every file regardless of vehicle PID support (`"-"` for unsupported or unavailable cells, RFC 4180 escaping for commas/quotes/newlines, `Device Time` as `DD-Mon-YYYY HH:MM:SS.mmm` local time).
  - Runs inside `ObdLoggingService` (foreground service + partial wake lock) with per-row disk flushing; keeps the file open and writes `"-"` sensor cells if the adapter disconnects mid-log, then resumes seamlessly on auto-reconnect.
- **UI**:
  - True black (`#000000`) background, `#1E1E1E` rounded cards, `#FFC107` yellow accents, Material 3 dark theme.
  - 3-card first-run onboarding, Home screen (connection card, huge START/STOP LOGGING button with timer & row count, Diagnostic Results card, Live Sensors), Debug Terminal tab, Past Logs tab (open, share, delete), Log Saved screen (Share & Open Folder), and Settings (Wi-Fi IP/port, polling speed, keep screen on, optional GPS).

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

## Extending the App

### Adding a New CSV Column or Mode 01 PID
1. **If it is a new Mode 01 PID**, add one `PidDefinition(...)` entry to `PidTable.entries` in `app/src/main/java/com/obd2logger/data/protocol/PidTable.kt`.
2. **Add one `CsvColumn(...)` entry** to `CsvSchema.columns` in `app/src/main/java/com/obd2logger/data/logging/CsvSchema.kt` (e.g. `add(pidCol("My Sensor(unit)", 0xXX))`).
3. Keep the column header Torque-compatible (`"Name(Unit)"` with no space before `(`) and return `"-"` whenever data is unavailable.

### Adding a New Transport
1. Implement `ObdTransport` (`app/src/main/java/com/obd2logger/domain/transport/ObdTransport.kt`).
2. Add the transport variant to `TransportType` and `AdapterConfig` (`app/src/main/java/com/obd2logger/domain/model/AdapterConfig.kt`).
3. Wire it in `ElmSessionManager.createTransport()` (`app/src/main/java/com/obd2logger/data/protocol/ElmSessionManager.kt`).

---

## CSV Schema Reference (137 Fixed Columns)

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
