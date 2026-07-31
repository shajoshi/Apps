# BkgTracker — LLM Reference Guide

> A reference for AI assistants (Claude, Cascade, etc.) working on this codebase.
> For deployment diagrams and Firebase sequence flows, see `docs/ARCHITECTURE.md`.
> For Firebase setup steps, see `SETUP.md` and `Setup_Firebase.md`.

---

## 1. What the App Does

BkgTracker is a **standalone Android GPS background tracking app** for families. Each device
continuously records its location in the background, uploads points to Firebase Firestore, and
a companion **web dashboard** (`web/index.html`, Leaflet map) shows all family members' trails.

Core capabilities:
- **Battery-aware background GPS tracking** via a foreground service + GPS state machine.
- **Activity-based wakeup** using the Activity Recognition API (walking/driving/etc. wakes GPS; STILL puts it to sleep).
- **Express Sync** — any device can trigger high-frequency (10s) tracking for 1 hour across all family devices via FCM broadcast.
- **Map view** with a scrubbable **timeline slider**, per-user trail toggles, and "open point in Maps app".
- **Local caching** of Firestore reads to minimize cloud usage, with a usage tracker dashboard.

---

## 2. Tech Stack

| Aspect | Choice |
|--------|--------|
| Language | Kotlin 2.0.21 |
| Package | `com.sj.bkgtracker` |
| Min / Target / Compile SDK | 26 / 36 / 36 |
| UI | Jetpack Compose (Material3) |
| Architecture | MVI (MainState / MainIntent / MainViewModel) + light Clean Architecture (domain/data) |
| GPS | `FusedLocationProviderClient` + `GnssStatus.Callback` |
| Activity detection | Play Services `ActivityRecognitionClient` (Activity Transitions) |
| Maps (in-app) | osmdroid 6.1.20 (MAPNIK tiles) |
| Auth | Google Sign-In (CredentialManager) → Firebase Auth |
| Backend | Firebase Firestore (Spark/free), FCM, Cloud Functions, Hosting |
| Background work | WorkManager (15-min periodic sync) + Foreground Service |
| Serialization | kotlinx-serialization-json 1.7.3 |

Versioning is auto-incremented on each `assembleDebug`/`assembleRelease` via `app/version.properties`.

**IMPORTANT (build policy):** Do NOT auto-build the source. Ask the user to build and verify changes.

---

## 3. Source Layout (`app/src/main/java/com/sj/bkgtracker/`)

```
BkgTrackerApp.kt              # Application class; subscribes to FCM topic "bkgtracker_family"

data/local/
  ActivityLogCache.kt         # In-memory rolling log of activity transitions (last 120 min)
  ActivityStateHolder.kt      # StateFlow of current activity ("Walking"/"Still"/"Idle"...) + isInActivity
  AppSettings.kt              # App settings persisted to JSON (uses "wt" file mode — see gotchas)
  ExpressSyncManager.kt       # Express mode state (SharedPreferences + StateFlow), expiry handling
  GpsStateHolder.kt           # StateFlow of GpsState (DEEP_IDLE/ACQUISITION/ACTIVE/EXPRESS) + interval
  SyncPrefs.kt                # Sync-related SharedPreferences
  TrackingStateHolder.kt      # StateFlow<Boolean> for whether tracking is active
  UnifiedLocationCache.kt     # Per-user location cache; incremental Firebase fetch optimization
  UsageTracker.kt             # Tracks Firestore reads/writes/FCM for the Usage dialog

domain/
  model/LocationRecord.kt     # lat, lon, timestampMs, accuracyM, speedKmh, altitudeM, bearingDeg
  repository/LocationRepository.kt   # Repository interface

data/repository/
  LocationRepositoryImpl.kt   # Firestore batch writes; coroutines-play-services await()

receiver/
  ActivityTransitionReceiver.kt  # Receives ENTER/EXIT transitions → calls service start/end methods
  BootReceiver.kt                # Restarts service after reboot if signed in

service/
  LocationForegroundService.kt   # CORE: GPS state machine, activity handling, express timer, notifications
  BkgTrackerMessagingService.kt  # FCM receiver → drives ExpressSyncManager

ui/
  MainActivity.kt   # Hosts Compose; top-bar menu (Usage / Activity Log / Sign Out); permissions
  MainScreen.kt     # Home tab: tracking status card (GPS + activity state), last-location card
  MainState.kt      # MVI state
  MainViewModel.kt  # MVI logic, auth, sign-in/out
  MapScreen.kt      # Map tab: osmdroid map, timeline slider, user chips, globe→Maps button
  MapViewModel.kt   # Map data load, cache integration, timeline navigation
  theme/Theme.kt

worker/
  SyncWorker.kt     # WorkManager: drains cache → Firestore batch write → requeue on failure
```

---

## 4. GPS State Machine (the heart of the app)

Defined in `LocationForegroundService.kt`. Four states (`GpsState`):

| State | Meaning | GPS interval |
|-------|---------|--------------|
| `DEEP_IDLE` | GPS off, minimal battery; waiting for activity/satellites | 0 (off) |
| `ACQUISITION` | Fast startup after wake, seeking first fix | 5s |
| `ACTIVE` | Normal movement tracking | 10s |
| `EXPRESS` | High-frequency, distance filter bypassed; accuracy filter at 25m | 10s |

Key constants:
- `MIN_DISTANCE_METERS = 5.0` — only save a point if moved ≥5m from last saved (bypassed in EXPRESS).
- `INDOOR_ACCURACY_THRESHOLD = 15f` — skip fixes worse than 15m in normal modes.
- `EXPRESS_INDOOR_ACCURACY_THRESHOLD = 25f` — skip fixes worse than 25m in EXPRESS.
- `ACQUISITION_TIMEOUT_MS = 60_000L` — if no movement within 60s of acquiring.
- `STATIONARY_SKIP_THRESHOLD = 6` — consecutive distance-skips before going idle.
- `NORMAL_INTERVAL_MS = 10_000L`, `EXPRESS_INTERVAL_MS = 10_000L`, `ACQUISITION_INTERVAL_MS = 5_000L`.

Transitions are handled by `enterDeepIdleMode()`, `enterAcquisitionMode()`, `enterActiveMode()`,
`enterExpressMode()`. **The notification state is driven ONLY by these `enter*` methods** — do not
update the notification from `onLocationAvailability()` (it caused stale "GPS active" displays).

---

## 5. Activity Detection ↔ GPS Mapping

Activity Transitions are registered in `registerActivityTransitions()` for: `IN_VEHICLE`,
`ON_BICYCLE`, `ON_FOOT`, `WALKING`, `RUNNING`, `STILL` (both ENTER and EXIT).

Flow: `ActivityTransitionReceiver` → `startForActivityWake(type)` / `notifyActivityEnded(type)` →
`onStartCommand` handles `ACTION_ACTIVITY_WAKE` / `ACTION_ACTIVITY_END`.

| Event | Behavior |
|-------|----------|
| Movement activity ENTER (walk/run/vehicle/etc.) | `isInActivity = true`; wake from DEEP_IDLE → ACQUISITION; stay active even if stationary |
| `STILL` ENTER | `isInActivity = false`; `ActivityStateHolder.setStillActivity()`; → DEEP_IDLE (unless EXPRESS) |
| Any EXIT | `isInActivity = false`; → DEEP_IDLE (unless EXPRESS) |

**`isInActivity` is a GPS stay-awake override**: while true, the stationary-skip logic and the
acquisition timeout will NOT drop to DEEP_IDLE (acquisition timeout goes to ACTIVE instead).

`ActivityStateHolder` exposes `activityState: StateFlow<String>` and `isInActivity: StateFlow<Boolean>`
for the UI. `ActivityLogCache` keeps a rolling 120-minute log shown in the "Activity Log" menu dialog.

---

## 6. Caching & Cloud-Usage Optimization

`UnifiedLocationCache` (per-user) minimizes Firestore reads:
- Tracks `fetchedWindows` per user = the time range actually fetched from Firebase.
- `cacheCoversTimeWindow(uid, since)` → if the cache covers the start of the requested window, use
  cache + an **incremental** query for only new points since the last fetch (`fetchedWindow.second`).
- Otherwise fetch the full window from Firebase.
- The **write pipeline** (`addPoint` from the service) is independent of read-cache tracking.

`UsageTracker` records reads/writes/FCM and powers the "Usage" dialog (with daily free-tier limits).

---

## 7. Express Sync (cross-device high-frequency)

- A device writes `/express_sync/{uid}` in Firestore → Cloud Function `onExpressSyncRequested`
  broadcasts an FCM data message to topic `bkgtracker_family`.
- `BkgTrackerMessagingService` receives it → `ExpressSyncManager.activate(expiresAt)`.
- Service enters `EXPRESS` (10s interval, distance filter bypassed, accuracy threshold 25m) and syncs every 60s for 1 hour.
- Any device can **stop** express for everyone (writes a stop doc → FCM broadcast).
- Normal sync otherwise is WorkManager every 15 min.

---

## 8. Firestore Data Model

```
locations/{uid}                      # doc: { email }
locations/{uid}/records/{docId}      # { lat, lon, timestamp, accuracy, speed, altitude, bearing, email }
express_sync/{uid}                   # { action: start|stop, requestedBy, stoppedBy, expiresAt, requestedAt, stoppedAt }
```

---

## 9. UI Notes

- **Home tab (`MainScreen`)**: a Tracking Status card shows GPS state (color-coded) on the left and
  the **current device activity** (e.g., "Walking"/"Idle") right-aligned (blue when in activity, grey when idle).
  The Express Sync / Stop / Extend buttons also live here. A separate Last-GPS-Fix card shows coordinates.
- **Map tab (`MapScreen`)**: time-window chips (1h/6h/24h/3d), per-user trail toggle chips, refresh,
  and a **timeline** slider. Each timeline point shows time + speed and a **globe icon** that opens the
  point in the phone's Maps app via `geo:lat,lon?q=lat,lon(timestampLabel)`.
  - **While the timeline is enabled, the map does NOT auto zoom/center** — the user keeps manual control.
    (Auto zoom-to-bounds only runs when `!state.timelineEnabled`.)
- **Top-bar overflow menu**: `Usage`, `Activity Log`, `Sign Out`.

---

## 10. Gotchas & Conventions

- **Build policy**: never auto-build; tell the user to build and verify.
- **File writes**: when persisting JSON via `ContentResolver.openOutputStream`, use mode **`"wt"`**
  (write+truncate). Mode `"w"` does NOT truncate on Android 10+ and leaves stale trailing bytes,
  corrupting JSON. Applies to settings/profile/layout saves.
- **Notification state**: only update from the `enter*Mode()` methods, never from `onLocationAvailability()`.
- **EXPRESS overrides everything**: state transitions guard with `currentGpsState != GpsState.EXPRESS`
  before dropping to DEEP_IDLE.
- **Comments/docs**: do not add or remove comments unless asked.

---

## 11. Where to Start for Common Tasks

| Task | Primary file(s) |
|------|-----------------|
| Change GPS intervals / state logic | `service/LocationForegroundService.kt` |
| Adjust activity→GPS behavior | `service/LocationForegroundService.kt`, `receiver/ActivityTransitionReceiver.kt` |
| Activity UI / log | `data/local/ActivityStateHolder.kt`, `data/local/ActivityLogCache.kt`, `ui/MainScreen.kt`, `ui/MainActivity.kt` |
| Map / timeline / trails | `ui/MapScreen.kt`, `ui/MapViewModel.kt` |
| Caching & Firestore reads | `data/local/UnifiedLocationCache.kt`, `ui/MapViewModel.kt` |
| Sync / upload | `worker/SyncWorker.kt`, `data/repository/LocationRepositoryImpl.kt` |
| Express sync | `data/local/ExpressSyncManager.kt`, `service/BkgTrackerMessagingService.kt` |
| Auth / permissions | `ui/MainActivity.kt`, `ui/MainViewModel.kt` |
