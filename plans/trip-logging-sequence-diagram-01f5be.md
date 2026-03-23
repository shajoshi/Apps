# Trip Logging Sequence Diagram

Shows how GPS and OBD samples are combined, calculated, and logged once a trip is started, with exact timing and data flow.

---

## Sequence Diagram

```mermaid
sequenceDiagram
    participant User
    participant UI as UI/TripScreen
    participant MC as MetricsCalculator
    participant DO as DataOrchestrator
    participant GPS as GpsDataSource
    participant OBD as BluetoothObd2Service
    participant ELM as ELM327 Adapter
    participant ECU as Vehicle ECU
    participant Logger as MetricsLogger
    participant File as JSON Log File

    Note over User,File: Trip starts when user taps "Start Trip"

    User->>UI: tap Start Trip
    UI->>MC: startTrip()
    MC->>MC: reset TripState
    MC->>MC: set tripPhase = RUNNING
    MC->>Logger: open(context, profile, supportedPids)
    Logger->>File: create file + write header
    File-->>Logger: file ready
    Logger-->>MC: logger.isOpen = true

    Note over GPS,ECU: Continuous polling begins (already running)

    %% GPS Fix Path (1 Hz, 500ms min)
    loop GPS fixes
        GPS->>GPS: LocationCallback.onLocationResult()
        GPS->>GPS: emit GpsDataItem
        GPS->>DO: gpsData flow emit
    end

    %% OBD Polling Path (fast/slow tiers)
    loop OBD cycles
        Note right of OBD: Fast tier every ~200ms (RPM,Speed,MAF,Throttle,Fuel)
        OBD->>ELM: "010C\r" (RPM)
        ELM->>ECU: PID request
        ECU-->>ELM: response bytes
        ELM-->>OBD: "410Cxxxx>"
        OBD->>ELM: "010D\r" (Speed)
        ELM->>ECU: PID request
        ECU-->>ELM: response bytes
        ELM-->>OBD: "410Dxx>"
        OBD->>ELM: "0110\r" (MAF)
        ELM->>ECU: PID request
        ECU-->>ELM: response bytes
        ELM-->>OBD: "4110xxxx>"
        OBD->>ELM: "0111\r" (Throttle)
        ELM->>ECU: PID request
        ECU-->>ELM: response bytes
        ELM-->>OBD: "4111xx>"
        OBD->>ELM: "015E\r" (Fuel Rate)
        ELM->>ECU: PID request
        ECU-->>ELM: response bytes
        ELM-->>OBD: "415Exxxx>"
        Note right of OBD: Slow tier every 5 cycles (temps, fuel level, etc.)
        OBD->>ELM: "0105\r" (Coolant) [if cycle % 5 == 0]
        ELM->>ECU: PID request
        ECU-->>ELM: response bytes
        ELM-->>OBD: "4105xx>"
        OBD->>OBD: emit List<Obd2DataItem>
    end

    %% DataOrchestrator combines and batches
    Note over DO,MC: combine() fires when either GPS or OBD emits
    DO->>DO: combine(obd2Data, gpsData)
    DO->>DO: cache latestObd2 map
    DO->>DO: debounce(100ms) — batch rapid emissions
    DO->>MC: combinedFlow.emit(Pair(obdList, gps))

    %% Calculation and Logging
    MC->>MC: calculate(obdList, gps)
    Note right of MC: 1) Parse values<br/>2) Update TripState (if !paused)<br/>3) Compute derived metrics<br/>4) Build VehicleMetrics
    MC->>MC: updateMetrics(snapshot)
    MC->>MC: logMetrics(snapshot)
    Logger->>File: append JSON sample (flush)
    Note over File: Each sample includes:<br/>- timestampMs, sampleNo<br/>- gps sub-object (if available)<br/>- obd sub-object (if available)<br/>- fuel, trip, accel sub-objects

    %% UI Updates (optional)
    MC->>UI: metrics StateFlow emit
    UI->>UI: UI updates (gauges, trip stats)

    %% Trip End
    User->>UI: tap Stop Trip
    UI->>MC: stopTrip()
    MC->>MC: tripPhase = IDLE
    MC->>Logger: close()
    Logger->>File: write closing "]\n}" + flush + close
    File-->>Logger: file complete
    Logger-->>MC: logger.isOpen = false
```

---

## Key Timing Details

| Event | Frequency | Notes |
|-------|-----------|-------|
| **GPS fixes** | ~1 Hz (min 500ms) | Emits `GpsDataItem` via `gpsData` flow |
| **Fast OBD tier** | ~200ms cycle | 5 PIDs (RPM, Speed, MAF, Throttle, Fuel Rate) |
| **Slow OBD tier** | ~1s (every 5 cycles) | All other PIDs (temps, fuel level, voltage, etc.) |
| **combine() trigger** | Whichever is faster (GPS or OBD) | Fires when either flow emits |
| **debounce(100ms)** | Batches rapid emissions | Prevents duplicate calculations when GPS+OBD emit close together |
| **Full calculation** | ~100–200ms effective | After debounce, one `VehicleMetrics` snapshot |
| **Logging** | Per calculation | Each snapshot becomes one JSON sample (if logging enabled) |

---

## What Gets Logged per Sample

```json
{
  "timestampMs": 1710529123456,
  "sampleNo": 42,
  "gps": { "lat": 28.6, "lon": 77.2, "speedKmh": 45, "altMsl": 215, ... },
  "obd": { "rpm": 1850, "speedKmh": 44, "mafGs": 12.3, "throttlePct": 18, "fuelRateLh": 2.1, ... },
  "fuel": { "fuelRateEffectiveLh": 2.1, "instantLper100km": 4.7, "tripFuelUsedL": 0.85, ... },
  "trip": { "distanceKm": 0.42, "timeSec": 38, "movingTimeSec": 35, ... },
  "accel": { ... } // if accelerometer enabled
}
```

- **GPS sub-object** only if any GPS data is present
- **OBD sub-object** only if any OBD data is present  
- **Derived sub-objects** (fuel, trip, accel) always present with computed values
- **Sample numbers** increment sequentially (1, 2, 3, ...)

---

## Edge Cases

- **No GPS fix**: `gps` sub-object omitted; OBD-only samples still logged
- **No OBD data (connection lost)**: `obd` sub-object omitted; GPS-only samples still logged
- **Trip paused**: `TripState.update()` skipped, but samples still logged with `tripPhase = PAUSED`
- **Logging disabled**: `logger.isOpen = false`; `logMetrics()` no-ops
