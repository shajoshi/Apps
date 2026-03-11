# MetricsCalculator Service + Vehicle Profiles + Settings Screen

A new `MetricsCalculator` singleton computes all primary and derived OBD2/GPS metrics, backed by a `VehicleProfile` settings system (multi-profile, fuel type, tank, price, polling delays) and a `MetricsLogger` that writes JSON timeline logs to a user-selected folder; the existing Settings screen is expanded to host all new configuration.

---

## Codebase Audit — What Already Exists

| Component | Current State | Plan Action |
|---|---|---|
| `Obd2Service` / `BluetoothObd2Service` / `MockObd2Service` | Polls 21 PIDs → `StateFlow<List<Obd2DataItem>>` | **Modify** `BluetoothObd2Service` to read polling delays from `AppSettings` |
| `GpsDataSource` | `StateFlow<GpsDataItem?>` with speed + MSL altitude | Collect as-is |
| `Obd2CommandRegistry` | 21 PIDs defined | No change |
| `SettingsFragment` | Only has auto-connect toggle; "coming soon" placeholder for polling interval | **Expand significantly** |
| `fragment_settings.xml` | Minimal layout | **Replace** with sectioned scrollable layout |
| `DashboardEditorFragment` | Per-PID OBD2/GPS collectors | **Modify** — switch to `MetricsCalculator.metrics` collector |
| No `VehicleProfile`, no `MetricsCalculator`, no `MetricsLogger` | — | **New files** |

---

## New Package Structure

```
com.sj.obd2app/
├── metrics/
│   ├── VehicleMetrics.kt          ← all-fields data snapshot
│   ├── TripState.kt               ← mutable accumulator (internal)
│   ├── MetricsCalculator.kt       ← singleton service
│   └── MetricsLogger.kt           ← JSON timeline logger
└── settings/
    ├── VehicleProfile.kt          ← data class (Gson-serialisable)
    ├── VehicleProfileRepository.kt← CRUD + active-profile management (SharedPreferences + JSON)
    ├── AppSettings.kt             ← global settings singleton (polling delays, log folder, etc.)
    ├── SettingsFragment.kt        ← expand existing
    └── VehicleProfileEditSheet.kt ← BottomSheet for create/edit a profile
```

---

## 1. `VehicleProfile` Data Class

```kotlin
data class VehicleProfile(
    val id: String,               // UUID
    val name: String,             // e.g. "Maruti Brezza"
    val fuelType: FuelType,       // PETROL | DIESEL | CNG
    val tankCapacityL: Float,     // e.g. 48.0
    val fuelPricePerLitre: Float, // e.g. 103.5
    val enginePowerBhp: Float,    // e.g. 103.0
    val obdPollingDelayMs: Long?,  // null = use global default
    val obdCommandDelayMs: Long?   // null = use global default
)

enum class FuelType { PETROL, DIESEL, CNG }
```

- Stored as JSON array in `SharedPreferences` key `"vehicle_profiles"`
- Active profile ID stored in `"active_profile_id"`

---

## 2. `AppSettings` Singleton

Global settings backed by `SharedPreferences("obd2_prefs")`:

| Key | Type | Default | Description |
|---|---|---|---|
| `global_polling_delay_ms` | Long | 500 | ms between full OBD2 poll cycles |
| `global_command_delay_ms` | Long | 50 | ms between individual PID commands |
| `log_folder_uri` | String? | null → Downloads | SAF-picked folder URI |
| `logging_enabled` | Boolean | false | master log on/off |
| `active_profile_id` | String? | null | active `VehicleProfile` UUID |

`AppSettings.activeProfile: VehicleProfile?` — merges global polling delays with any profile override.

---

## 3. `VehicleMetrics` Data Class

One immutable snapshot per calculation cycle:

**Primary OBD2 (nullable — absent if PID not supported):**
`rpm`, `vehicleSpeedKmh`, `engineLoadPct`, `throttlePct`, `coolantTempC`, `intakeTempC`, `oilTempC`, `ambientTempC`, `fuelLevelPct`, `fuelPressureKpa`, `fuelRateLh`, `mafGs`, `intakeMapKpa`, `baroPressureKpa`, `timingAdvanceDeg`, `stftPct`, `ltftPct`, `o2Voltage`, `controlModuleVoltage`, `runTimeSec`, `distanceMilOnKm`, `distanceSinceCleared`

**Primary GPS:** `gpsSpeedKmh`, `altitudeMslM`, `gpsAccuracyM`, `gpsBearingDeg`

**Derived — Fuel Efficiency:**

| Field | Formula |
|---|---|
| `fuelRateEffectiveLh` | PID 015E if present; else MAF fallback: `mafGs × fuelType.massToVolumeFactor × 3600` (petrol factor = 0.0746 L/g, diesel = 0.0594 L/g) |
| `instantLper100km` | `(fuelRateEffectiveLh × 100) ÷ speedKmh`; null if speed = 0 |
| `instantKpl` | `100 ÷ instantLper100km` |
| `tripFuelUsedL` | `Σ(fuelRateEffectiveLh × Δt_hr)` |
| `tripAvgLper100km` | `(tripFuelUsedL × 100) ÷ tripDistanceKm` |
| `tripAvgKpl` | `100 ÷ tripAvgLper100km` |
| `fuelFlowCcMin` | `fuelRateEffectiveLh × 1000 ÷ 60` |
| `rangeRemainingKm` | `(fuelLevelPct/100 × tankCapacityL) ÷ (tripAvgLper100km/100)` |
| `fuelCostEstimate` | `tripFuelUsedL × profile.fuelPricePerLitre` |
| `avgCo2gPerKm` | `tripAvgLper100km × co2Factor` (petrol=23.1, diesel=26.4, CNG=16.0) |

**Derived — Trip Computer:**

| Field | Formula |
|---|---|
| `tripDistanceKm` | `Σ(gpsSpeedKmh × Δt_sec / 3600)` |
| `tripTimeSec` | `now − tripStartMs` |
| `movingTimeSec` | accumulated when `speed > 2 km/h` |
| `stoppedTimeSec` | accumulated when `speed ≤ 2 km/h` |
| `tripAvgSpeedKmh` | `tripDistanceKm ÷ (movingTimeSec / 3600)` |
| `tripMaxSpeedKmh` | peak GPS speed this trip |
| `spdDiffKmh` | `gpsSpeedKmh − vehicleSpeedKmh` |

**Derived — Drive Mode (rolling 60 s):**
`pctCity`, `pctHighway`, `pctIdle`

---

## 4. `MetricsCalculator` Singleton

```kotlin
class MetricsCalculator private constructor(context: Context) {
    val metrics: StateFlow<VehicleMetrics>
    fun startTrip()   // resets TripState
    fun stopTrip()    // pauses accumulation
    companion object { fun getInstance(context: Context): MetricsCalculator }
}
```

- Reads active `VehicleProfile` from `AppSettings` on each cycle
- `combine(obd2Data, gpsData)` → `calculate()` → emit `VehicleMetrics`
- Forwards each snapshot to `MetricsLogger` if `AppSettings.loggingEnabled`

---

## 5. `MetricsLogger`

- Writes `<ProfileName>_obdlog_<YYYY-MM-DD_HHmmss>.json` (e.g. `Maruti_Brezza_obdlog_2026-03-08_183012.json`) to SAF-chosen folder (or `Downloads` as default) using `DocumentFile` + `ContentResolver`
- Profile name is sanitised for filesystem use (spaces → underscores, special chars stripped)
- File is named at `open()` time using the trip start timestamp — one file per trip
- On Android 10+ uses `MediaStore` for `Downloads`; on older versions direct `File` write
- `open(context, profile, supportedPids)` / `append(metrics)` / `close()` / `getShareUri(context): Uri?`

### Log File JSON Structure

```json
{
  "header": {
    "appVersion": "1.0.0",
    "logStartedAt": "2026-03-08T18:30:00+05:30",
    "logStartedAtMs": 1741441800000,
    "vehicleProfile": {
      "name": "Maruti Brezza",
      "fuelType": "PETROL",
      "tankCapacityL": 48.0,
      "fuelPricePerLitre": 103.5,
      "enginePowerBhp": 103.0,
      "obdPollingDelayMs": 500,
      "obdCommandDelayMs": 50
    },
    "supportedPids": [
      { "pid": "0104", "name": "Calculated Engine Load", "unit": "%" },
      { "pid": "0105", "name": "Coolant Temperature",    "unit": "°C" },
      { "pid": "010C", "name": "Engine RPM",             "unit": "rpm" }
    ]
  },
  "samples": [
    { "timestampMs": 1741441800500, "rpm": 850.0, "vehicleSpeedKmh": 0.0, "coolantTempC": 88.0, "instantLper100km": null, … },
    { "timestampMs": 1741441801500, "rpm": 920.0, "vehicleSpeedKmh": 12.5, … }
  ]
}
```

- **`header`** — written once when `open()` is called; includes active `VehicleProfile` fields and the list of PIDs the ECU reported as supported (name + unit from registry, not just hex codes)
- **`samples`** — streaming array; each `append(metrics)` call writes one line-delimited JSON object into the array
- File is kept valid JSON throughout: opening `{` + `"header":{…},"samples":[` written on open; each sample appended as `{…},\n`; closing `]}` written on `close()`
- If the app is killed before `close()`, the file is repairable (truncate trailing comma, add `]}`) — a `repairIfNeeded()` utility handles this on next open

---

## 6. Expanded Settings Screen

The existing `SettingsFragment` / `fragment_settings.xml` is expanded with four new sections:

### Section: Vehicle Profiles
- List of saved profiles (RecyclerView card per profile, active one highlighted)
- "Add Profile" FAB → opens `VehicleProfileEditSheet` (BottomSheet)
- Long-press → Edit / Delete
- Tap → Set as Active

### `VehicleProfileEditSheet` fields:
- Profile Name (EditText)
- Fuel Type (RadioGroup: Petrol / Diesel / CNG)
- Tank Capacity (EditText, L)
- Fuel Price per Litre (EditText, currency)
- Engine Power (EditText, BHP)
- OBD2 Polling Cycle (EditText, ms — placeholder shows global default, blank = use global)
- OBD2 Command Gap (EditText, ms — same)

### Section: OBD2 Polling (global defaults)
- Polling cycle delay slider + value label (100ms – 2000ms, default 500ms)
- Command gap delay slider + value label (20ms – 500ms, default 50ms)

### Section: Connection (existing — expanded)
- Auto-connect toggle (keep as-is)
- **Enable Trip Logging** toggle (SwitchMaterial, new) — when ON, every trip start automatically opens a new log file; when OFF, no log is written even if Start Trip is pressed

### Section: Data Logging
- Log folder row — shows current path, "Change…" button → SAF `ACTION_OPEN_DOCUMENT_TREE` picker
- "Share Latest Log" button (visible only when a log file exists)

---

## 7. Full SAE J1979 PID Registry Expansion

### Why only 21 today?
`BluetoothObd2Service` **already discovers** which PIDs the ECU supports via the standard 0x00/0x20/0x40/0x60 bitmask queries (up to 128 PIDs across 4 ranges). However, `startPolling()` then filters against only the 21 entries currently in `Obd2CommandRegistry` — **any ECU-supported PID not in the registry is silently dropped**.

### Fix: expand `Obd2CommandRegistry` to all ~80 Mode 01 PIDs
Add every SAE J1979 / ISO 15031-5 Mode 01 PID with its correct byte-count and parse formula. Grouped below:

| PID | Name | Bytes | Formula |
|---|---|---|---|
| 0101 | Monitor Status (MIL + DTC count) | 4 | bit-field |
| 0102 | Freeze DTC | 2 | raw hex |
| 0103 | Fuel System Status | 2 | enum |
| 0104 | Calculated Engine Load | 1 | A×100/255 |
| 0105 | Coolant Temp | 1 | A−40 |
| 0106 | STFT Bank 1 | 1 | A×100/128−100 |
| 0107 | LTFT Bank 1 | 1 | A×100/128−100 |
| 0108 | STFT Bank 2 | 1 | A×100/128−100 |
| 0109 | LTFT Bank 2 | 1 | A×100/128−100 |
| 010A | Fuel Pressure | 1 | A×3 kPa |
| 010B | Intake MAP | 1 | A kPa |
| 010C | Engine RPM | 2 | (A×256+B)/4 |
| 010D | Vehicle Speed | 1 | A km/h |
| 010E | Timing Advance | 1 | A/2−64 |
| 010F | Intake Air Temp | 1 | A−40 |
| 0110 | MAF Air Flow | 2 | (A×256+B)/100 g/s |
| 0111 | Throttle Position | 1 | A×100/255 |
| 0112 | Secondary Air Status | 1 | enum |
| 0113 | O2 Sensors Present (2-bank) | 1 | bit-field |
| 0114 | O2 S1-1 Voltage | 2 | A/200 V |
| 0115 | O2 S1-2 Voltage | 2 | A/200 V |
| 0116 | O2 S2-1 Voltage | 2 | A/200 V |
| 0117 | O2 S2-2 Voltage | 2 | A/200 V |
| 0118–011B | O2 S3–S4 (bank 1&2) | 2 each | A/200 V |
| 011C | OBD Standard | 1 | enum |
| 011D | O2 Sensors Present (4-bank) | 1 | bit-field |
| 011E | Aux Input Status | 1 | bit |
| 011F | Run Time Since Start | 2 | A×256+B sec |
| 0121 | Distance with MIL On | 2 | A×256+B km |
| 0122 | Fuel Rail Pressure (vacuum) | 2 | (A×256+B)×0.079 kPa |
| 0123 | Fuel Rail Pressure (direct) | 2 | (A×256+B)×10 kPa |
| 0124–012B | O2 Wide-range Sensors 1–4 | 4 each | ratio + voltage |
| 012C | Commanded EGR | 1 | A×100/255 % |
| 012D | EGR Error | 1 | A×100/128−100 % |
| 012E | Commanded Evap Purge | 1 | A×100/255 % |
| 012F | Fuel Tank Level | 1 | A×100/255 % |
| 0130 | Warm-ups Since Clear | 1 | A |
| 0131 | Distance Since Codes Cleared | 2 | A×256+B km |
| 0132 | Evap Sys Vapour Pressure | 2 | signed (A×256+B)/4 Pa |
| 0133 | Barometric Pressure | 1 | A kPa |
| 0134–013B | O2 Wide-range Sensors 5–8 | 4 each | ratio + current |
| 013C | Catalyst Temp B1S1 | 2 | (A×256+B)/10−40 °C |
| 013D–013F | Catalyst Temp B2S1, B1S2, B2S2 | 2 each | same |
| 0141 | Monitor Status This Drive Cycle | 4 | bit-field |
| 0142 | Control Module Voltage | 2 | (A×256+B)/1000 V |
| 0143 | Absolute Load Value | 2 | (A×256+B)×100/255 % |
| 0144 | Commanded AF Ratio | 2 | (A×256+B)/32768 |
| 0145 | Relative Throttle Position | 1 | A×100/255 % |
| 0146 | Ambient Air Temp | 1 | A−40 °C |
| 0147 | Throttle B Position | 1 | A×100/255 % |
| 0148 | Throttle C Position | 1 | A×100/255 % |
| 0149 | Accel Pedal D Position | 1 | A×100/255 % |
| 014A | Accel Pedal E Position | 1 | A×100/255 % |
| 014B | Accel Pedal F Position | 1 | A×100/255 % |
| 014C | Commanded Throttle Actuator | 1 | A×100/255 % |
| 014D | Time with MIL On | 2 | A×256+B min |
| 014E | Time Since Codes Cleared | 2 | A×256+B min |
| 0151 | Fuel Type | 1 | enum (1=Petrol,2=Methanol…) |
| 0152 | Ethanol Fuel % | 1 | A×100/255 % |
| 0153 | Abs Evap System Vapour Pressure | 2 | (A×256+B)/200 kPa |
| 0154 | Evap System Vapour Pressure 2 | 2 | signed A×256+B Pa |
| 0159 | Fuel Rail Abs Pressure | 2 | (A×256+B)×10 kPa |
| 015A | Relative Accel Pedal Position | 1 | A×100/255 % |
| 015B | Hybrid Battery Pack Life | 1 | A×100/255 % |
| 015C | Engine Oil Temp | 1 | A−40 °C |
| 015D | Fuel Injection Timing | 2 | (A×256+B)/128−210 ° |
| 015E | Engine Fuel Rate | 2 | (A×256+B)/20 L/h |
| 015F | Emission Requirements | 1 | enum |
| 0161 | Driver Demand Torque | 1 | A−125 % |
| 0162 | Actual Torque | 1 | A−125 % |
| 0163 | Engine Reference Torque | 2 | A×256+B Nm |
| 0164 | Engine Pct Torque Data | 5 | 5 operating points |
| 0167 | Coolant Temp 2 (2-sensor) | 3 | A−40, B−40 °C |
| 0168 | Intake Air Temp Sensor | 3 | A−40, B−40 °C |

### `Obd2Command` data class — no changes needed
The existing struct (`pid`, `name`, `unit`, `bytesReturned`, `parse`) handles all of the above. The registry simply gets more entries.

### Enum/bit-field PIDs
For PIDs that return enums or bit-fields (0101, 0103, 011C, 0151, etc.) the `parse` lambda returns a **human-readable string** (e.g. `"MIL ON, 3 DTCs"`, `"Open loop"`, `"Petrol"`). They display in Numeric widgets as text.

### Backward compatibility
- All 21 existing entries stay identical; new ones are appended.
- `MetricDefaults` will be extended to include the new PIDs where a sensible gauge range exists.
- `DashboardMetric.Obd2Pid` already uses the PID string as key — no structural change needed.

## 8. `BluetoothObd2Service` — Polling Delay Integration


Two internal constants `POLLING_DELAY` and `COMMAND_DELAY` are replaced with reads from `AppSettings.effectivePollingDelayMs` / `effectiveCommandDelayMs` on each polling cycle (so changes take effect without reconnecting).

---

## 9. Dashboard Integration

- `DashboardEditorFragment` subscribes to `MetricsCalculator.metrics` (single collector replaces per-PID collectors)
- **"Start Trip" button** added to `fragment_dashboard_editor.xml` toolbar area → calls `MetricsCalculator.startTrip()`
- **"Log" toggle** (small icon button) in same toolbar → flips `AppSettings.loggingEnabled`
- `DashboardMetric` sealed class gains `DerivedMetric(key, name, unit)` subclass
- `MetricDefaults` gains 12 derived metric entries (fuel efficiency, trip computer, CO₂, drive mode %)

---

## Files to Create / Modify

| File | Action |
|---|---|
| `metrics/VehicleMetrics.kt` | **New** |
| `metrics/TripState.kt` | **New** (internal) |
| `metrics/MetricsCalculator.kt` | **New** |
| `metrics/MetricsLogger.kt` | **New** |
| `settings/VehicleProfile.kt` | **New** |
| `settings/VehicleProfileRepository.kt` | **New** |
| `settings/AppSettings.kt` | **New** |
| `settings/VehicleProfileEditSheet.kt` | **New** |
| `settings/SettingsFragment.kt` | **Modify** — expand with 4 sections |
| `res/layout/fragment_settings.xml` | **Modify** — expand layout |
| `res/layout/sheet_vehicle_profile_edit.xml` | **New** |
| `res/layout/item_vehicle_profile.xml` | **New** |
| `obd/Obd2CommandRegistry.kt` | **Modify** — expand from 21 to ~80 full SAE J1979 Mode 01 PIDs |
| `obd/BluetoothObd2Service.kt` | **Modify** — read delays from AppSettings |
| `ui/dashboard/model/DashboardMetric.kt` | **Modify** — add DerivedMetric |
| `ui/dashboard/model/MetricDefaults.kt` | **Modify** — add 12 derived entries |
| `ui/dashboard/DashboardEditorFragment.kt` | **Modify** — MetricsCalculator collector, Start Trip + Log buttons |
| `AndroidManifest.xml` | **Modify** — add `READ_EXTERNAL_STORAGE` (API < 29), no extra permission needed for SAF |
