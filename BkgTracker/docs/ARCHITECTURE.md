# BkgTracker — Architecture & Design Document

BkgTracker is a family GPS background tracking system consisting of an Android app and a web dashboard, connected via Firebase cloud services.

---

## Deployment Diagram

```mermaid
graph TB
    subgraph "Android Devices"
        A1[Device A<br/>BkgTracker App]
        A2[Device B<br/>BkgTracker App]
    end

    subgraph "Firebase Cloud"
        FA[Firebase Auth<br/>Google Sign-In]
        FS[Cloud Firestore<br/>Location Storage]
        FCM[Firebase Cloud Messaging<br/>Push Notifications]
        CF[Cloud Functions<br/>Express Sync Trigger]
        FH[Firebase Hosting<br/>Web Dashboard]
    end

    subgraph "Web Browser"
        WD[Family Dashboard<br/>Leaflet Map]
    end

    A1 -->|Google Sign-In| FA
    A2 -->|Google Sign-In| FA
    A1 -->|Batch write locations| FS
    A2 -->|Batch write locations| FS
    A1 -->|Write express_sync doc| FS
    FS -->|Firestore trigger| CF
    CF -->|Topic push| FCM
    FCM -->|Data message| A1
    FCM -->|Data message| A2
    WD -->|Read locations| FS
    WD -->|Google Sign-In| FA
    FH -->|Serves| WD
```

---

## Firebase Components Used

| Component | Purpose | Firestore Path / Topic |
|-----------|---------|----------------------|
| **Firebase Auth** | Google Sign-In for app + web | — |
| **Cloud Firestore** | Store GPS location records | `/locations/{uid}/records/{docId}` |
| **Cloud Firestore** | Express sync request trigger | `/express_sync/{uid}` |
| **Cloud Functions** | Firestore trigger → FCM broadcast | `onExpressSyncRequested` |
| **Cloud Messaging (FCM)** | Push express sync to all devices | Topic: `bkgtracker_family` |
| **Firebase Hosting** | Serve web dashboard (Leaflet map) | `web/` directory |

---

## Sequence Diagram — Normal Location Tracking & Sync

```mermaid
sequenceDiagram
    participant GPS as FusedLocationProvider
    participant SVC as LocationForegroundService
    participant Cache as UnifiedLocationCache
    participant WM as WorkManager (15-min)
    participant Repo as LocationRepositoryImpl
    participant FS as Cloud Firestore

    Note over SVC: Service starts on sign-in or boot
    Note over SVC: GPS state machine governs interval<br/>(DEEP_IDLE→ACQUISITION→ACTIVE→EXPRESS)
    loop Every 15s in ACTIVE (5s ACQUISITION, 10s EXPRESS)
        GPS->>SVC: onLocationResult(location)
        SVC->>SVC: Filter (accuracy > 100m? skip)
        SVC->>SVC: Filter (distance < 20m? skip → consecutiveSkips++)
        SVC->>Cache: addPoint(LocationRecord)
    end

    Note over WM: Periodic trigger every 15 min
    WM->>Cache: drain pending points
    Cache-->>WM: List<LocationRecord>
    WM->>Repo: uploadBatch(records)
    Repo->>FS: batch.set(/locations/{uid}/records/*)
    FS-->>Repo: success
    Repo-->>WM: Result.success
```

---

## GPS State Machine (Battery-Aware Tracking)

`LocationForegroundService` governs the GPS request interval through a four-state machine to balance
tracking fidelity against battery drain.

```mermaid
stateDiagram-v2
    [*] --> DEEP_IDLE
    DEEP_IDLE --> ACQUISITION : Activity ENTER (walk/drive/etc.)<br/>or GNSS satellites detected
    ACQUISITION --> ACTIVE : First fix + movement<br/>(or timeout while isInActivity)
    ACQUISITION --> DEEP_IDLE : 60s timeout, no movement,<br/>not in activity
    ACTIVE --> DEEP_IDLE : 6 consecutive distance-skips<br/>(only if NOT in activity)
    ACTIVE --> DEEP_IDLE : Activity EXIT / STILL detected
    DEEP_IDLE --> EXPRESS : Express Sync activated (FCM)
    ACTIVE --> EXPRESS : Express Sync activated (FCM)
    EXPRESS --> DEEP_IDLE : Express expires (1h) / stopped
```

| State | Meaning | Interval |
|-------|---------|---------:|
| `DEEP_IDLE` | GPS off, minimal battery; awaiting activity/satellites | off |
| `ACQUISITION` | Fast startup, seeking first fix | 5s |
| `ACTIVE` | Normal movement tracking | 15s |
| `EXPRESS` | High-frequency, filters bypassed (overrides all) | 10s |

Key constants (in `LocationForegroundService`): `MIN_DISTANCE_METERS = 20`,
`INDOOR_ACCURACY_THRESHOLD = 100m`, `ACQUISITION_TIMEOUT_MS = 60s`, `STATIONARY_SKIP_THRESHOLD = 6`.

> **Notification rule**: the foreground notification state is updated ONLY by the `enter*Mode()`
> methods, never from `onLocationAvailability()` (which previously caused stale "GPS active" text).

---

## Sequence Diagram — Activity-Based Wakeup

The Activity Recognition API wakes GPS when movement starts and sleeps it when the user is STILL or
an activity ends. While `isInActivity` is true, the service will NOT drop to DEEP_IDLE even if
stationary — this prevents missed points when movement is minimal/delayed after wake.

```mermaid
sequenceDiagram
    participant AR as ActivityRecognition API
    participant RX as ActivityTransitionReceiver
    participant SVC as LocationForegroundService
    participant ASH as ActivityStateHolder
    participant ALC as ActivityLogCache
    participant UI as MainScreen

    AR->>RX: Transition (type, ENTER/EXIT)
    alt Movement ENTER (walk/run/vehicle/bicycle/on_foot)
        RX->>SVC: startForActivityWake(type)
        SVC->>SVC: isInActivity = true, if DEEP_IDLE then enterAcquisitionMode
        SVC->>ASH: setActivityStarted(type)
        SVC->>ALC: logActivity(name, started)
    else STILL ENTER
        RX->>SVC: startForActivityWake(STILL)
        SVC->>SVC: isInActivity = false, enterDeepIdleMode unless EXPRESS
        SVC->>ASH: setStillActivity()
        SVC->>ALC: logActivity("Still", started)
    else Any EXIT
        RX->>SVC: notifyActivityEnded(type)
        SVC->>SVC: isInActivity = false, enterDeepIdleMode unless EXPRESS
        SVC->>ASH: setActivityEnded(type)
        SVC->>ALC: logActivity(name, ended)
    end
    ASH-->>UI: activityState / isInActivity (StateFlow)
    Note over UI: Tracking Status card shows activity<br/>(blue when active, grey when idle)
```

Registered transition types (ENTER + EXIT): `IN_VEHICLE`, `ON_BICYCLE`, `ON_FOOT`, `WALKING`,
`RUNNING`, `STILL`. `ActivityLogCache` retains entries for the last **120 minutes**, viewable via
the top-bar **Activity Log** menu.

---

## Sequence Diagram — Map View with Cache Optimization

`UnifiedLocationCache` minimizes Firestore reads by tracking the time window already fetched per user
and only querying for new points incrementally.

```mermaid
sequenceDiagram
    participant UI as MapScreen
    participant VM as MapViewModel
    participant Cache as UnifiedLocationCache
    participant FS as Cloud Firestore
    participant UT as UsageTracker

    UI->>VM: refresh() / setTimeWindowHours(h)
    VM->>Cache: cacheCoversTimeWindow(uid, since)?
    alt Cache covers window
        Cache-->>VM: cached points + fetchedWindow
        VM->>FS: query timestamp > fetchedWindow.latest (incremental)
        FS-->>VM: only new points
        VM->>Cache: addPoints(new, since)
    else Cache does not cover window
        VM->>FS: query timestamp >= since (full window)
        FS-->>VM: points
        VM->>Cache: addPoints(points, since)
    end
    VM->>UT: recordReads(count)
    VM-->>UI: users + timeline points
    Note over UI: Timeline slider scrubs points,<br/>globe icon opens point in Maps app,<br/>map does NOT auto-zoom while timeline is on
```

---

## Sequence Diagram — Express Sync Activation (FCM Broadcast)

```mermaid
sequenceDiagram
    participant User as User (Device A)
    participant App as BkgTracker App A
    participant FS as Cloud Firestore
    participant CF as Cloud Function
    participant FCM as Firebase Cloud Messaging
    participant AppB as BkgTracker App B
    participant SvcB as LocationForegroundService B

    User->>App: Tap "Express Sync" button
    App->>FS: Write /express_sync/{uid}<br/>{requestedBy, expiresAt: now+1h}
    App->>App: ExpressSyncManager.activate() locally
    App->>App: UI shows "Express Sync mode till HH:mm activated by user"
    FS->>CF: Firestore onWrite trigger
    CF->>FCM: send(topic: bkgtracker_family,<br/>data: {expiresAt, requestedBy})
    FCM->>App: Data message (self)
    FCM->>AppB: Data message
    AppB->>AppB: BkgTrackerMessagingService.onMessageReceived()
    AppB->>AppB: ExpressSyncManager.activate(expiresAt)
    AppB->>AppB: UI shows "Express Sync mode till HH:mm activated by user"
    AppB->>AppB: Button changes to "Stop Express Sync"
    
    Note over SvcB: Express mode: sync every 60s
    loop Every 60 seconds (for 1 hour)
        SvcB->>SvcB: performSync()
        SvcB->>FS: uploadBatch(records)
    end

    Note over SvcB: After 1 hour
    SvcB->>SvcB: ExpressSyncManager.checkExpiry() → deactivate
    Note over SvcB: Reverts to normal 15-min WorkManager sync
```

---

## Sequence Diagram — Stop Express Sync (FCM Broadcast)

```mermaid
sequenceDiagram
    participant User as User (Device B)
    participant AppB as BkgTracker App B
    participant FS as Cloud Firestore
    participant CF as Cloud Function
    participant FCM as Firebase Cloud Messaging
    participant AppA as BkgTracker App A

    User->>AppB: Tap "Stop Express Sync" button
    AppB->>FS: Write /express_sync/{uid}<br/>{action: "stop", stoppedBy: email}
    AppB->>AppB: ExpressSyncManager.stopByUser() locally
    AppB->>AppB: UI shows "Express Sync stopped by user"
    FS->>CF: Firestore onWrite trigger
    CF->>FCM: send(topic: bkgtracker_family,<br/>data: {type: "express_sync_stop", stoppedBy})
    FCM->>AppB: Data message (self)
    FCM->>AppA: Data message
    AppA->>AppA: ExpressSyncManager.stopByUser()
    AppA->>AppA: UI shows "Express Sync stopped by user"
    AppA->>AppA: Button reverts to "Express Sync"
    Note over AppA,AppB: 60s sync timer stops, reverts to 15-min WorkManager
```

---

## Sequence Diagram — App Startup & Authentication

```mermaid
sequenceDiagram
    participant User as User
    participant MA as MainActivity
    participant VM as MainViewModel
    participant Auth as Firebase Auth
    participant FCM as FCM Topic
    participant SVC as LocationForegroundService

    User->>MA: Launch app
    MA->>MA: requestPermissions (fine loc, bg loc, notifications)
    MA->>VM: refreshAuthState()
    VM->>Auth: currentUser?
    
    alt Not signed in
        User->>MA: Tap "Sign In"
        MA->>Auth: Google Sign-In (CredentialManager)
        Auth-->>MA: GoogleIdToken
        MA->>Auth: signInWithCredential(token)
        Auth-->>MA: FirebaseUser
    end

    Note over MA: User is signed in
    MA->>SVC: LocationForegroundService.start()
    Note over SVC: GPS tracking begins

    Note over MA: BkgTrackerApp.onCreate
    MA->>FCM: subscribeToTopic("bkgtracker_family")
```

---

## Sequence Diagram — Web Dashboard

```mermaid
sequenceDiagram
    participant User as User (Browser)
    participant WD as Web Dashboard
    participant Auth as Firebase Auth
    participant FS as Cloud Firestore

    User->>WD: Open dashboard URL
    WD->>Auth: signInWithPopup(GoogleAuthProvider)
    Auth-->>WD: Firebase user
    WD->>FS: getDocs(/locations)
    FS-->>WD: List of family members
    loop For each member
        WD->>FS: getDocs(/locations/{uid}/records, limit)
        FS-->>WD: Location points
        WD->>WD: Plot trail on Leaflet map
    end
```

---

## Component Diagram — Android App

```mermaid
graph TB
    subgraph "UI Layer (Compose)"
        MA[MainActivity]
        MS[MainScreen]
        MapS[MapScreen]
    end

    subgraph "ViewModel"
        VM[MainViewModel]
        MVM[MapViewModel]
    end

    subgraph "Data Layer (data/local)"
        ULC[UnifiedLocationCache<br/>per-user cache + incremental fetch]
        SP[SyncPrefs / AppSettings]
        ESM[ExpressSyncManager<br/>StateFlow]
        GSH[GpsStateHolder<br/>StateFlow GpsState]
        ASH[ActivityStateHolder<br/>StateFlow activity]
        ALC[ActivityLogCache<br/>rolling 120-min log]
        UT[UsageTracker]
        Repo[LocationRepositoryImpl]
    end

    subgraph "Services & Receivers"
        LFS[LocationForegroundService<br/>GPS state machine + Express timer]
        FCM_SVC[BkgTrackerMessagingService<br/>FCM Receiver]
        SW[SyncWorker<br/>WorkManager 15-min]
        BR[BootReceiver]
        ATR[ActivityTransitionReceiver]
    end

    subgraph "External"
        FLP[FusedLocationProviderClient]
        ARC[ActivityRecognitionClient]
        FAuth[Firebase Auth]
        FStore[Cloud Firestore]
        GFCM[Firebase Cloud Messaging]
    end

    MA --> VM
    MA --> MVM
    MA --> ALC
    MS --> VM
    MS --> GSH
    MS --> ASH
    MapS --> MVM
    VM --> SP
    VM --> ESM
    VM --> Repo
    MVM --> ULC
    MVM --> UT
    LFS --> FLP
    LFS --> ARC
    LFS --> ULC
    LFS --> ESM
    LFS --> GSH
    LFS --> ASH
    LFS --> ALC
    LFS --> Repo
    ARC --> ATR
    ATR --> LFS
    FCM_SVC --> ESM
    SW --> ULC
    SW --> Repo
    BR --> LFS
    Repo --> FAuth
    Repo --> FStore
    GFCM --> FCM_SVC
```

---

## Firestore Data Model

```mermaid
erDiagram
    LOCATIONS {
        string uid PK
        string email
    }
    RECORDS {
        string docId PK
        float lat
        float lon
        long timestamp
        float accuracy
        float speed
        float altitude
        float bearing
        string email
    }
    EXPRESS_SYNC {
        string uid PK
        string action "start or stop"
        string requestedBy "email (when starting)"
        string stoppedBy "email (when stopping)"
        long expiresAt "epoch ms (when starting)"
        long requestedAt "epoch ms"
        long stoppedAt "epoch ms (when stopping)"
    }

    LOCATIONS ||--o{ RECORDS : "has many"
```

---

## Key Design Decisions

- **GPS State Machine**: DEEP_IDLE (off) → ACQUISITION (5s) → ACTIVE (15s); EXPRESS (10s) overrides all. (PRIORITY_HIGH_ACCURACY via FusedLocationProviderClient)
- **GPS Fix Interval (Active/Normal)**: 15 seconds (`NORMAL_INTERVAL_MS`)
- **GPS Fix Interval (Acquisition)**: 5 seconds for fast first fix after wake; 60s timeout back to idle if no movement
- **GPS Fix Interval (Express)**: 10 seconds — filters bypassed for maximum data capture
- **Activity-Based Wakeup**: Activity Recognition transitions wake GPS on movement (walk/run/vehicle/bicycle/on_foot ENTER) and sleep it on STILL/EXIT. `isInActivity` flag keeps GPS awake during ongoing activity even when stationary.
- **Distance Filter**: Only saves if moved ≥ 20m from last saved point (bypassed in express)
- **Accuracy Filter**: Skips if GPS accuracy > 100m (bypassed in express)
- **Stationary Detection**: 6 consecutive distance-skips → DEEP_IDLE, but only when NOT in an activity
- **Normal Sync**: WorkManager every 15 minutes (requires network)
- **Express Sync**: 60-second timer in ForegroundService, lasts 1 hour, FCM broadcast to all family devices
- **Express Sync Stop**: Any device can stop express sync for all devices via FCM broadcast
- **Express Status**: All devices show "Express Sync mode till {time} activated by {user}" or "Express Sync stopped by {user}"
- **Read Cache**: `UnifiedLocationCache` caches per-user points and tracks fetched windows so map reads only query Firebase incrementally (new points since last fetch). Write pipeline is independent of read-cache tracking.
- **Usage Tracking**: `UsageTracker` records Firestore reads/writes/FCM, surfaced in the "Usage" dialog.
- **Activity Log**: `ActivityLogCache` keeps activity transitions for the last 120 minutes, shown via the "Activity Log" menu.
- **Map Timeline**: Scrubbable slider over loaded points; selecting a point shows a globe icon that opens it in the phone's Maps app (`geo:` URI). Auto zoom/center is suppressed while the timeline is active so the user keeps manual control.
- **Auth**: Google Sign-In → Firebase Auth token → Firestore security rules
- **Boot Resilience**: BootReceiver restarts foreground service after reboot if signed in
- **File Persistence**: JSON saves use `ContentResolver` mode `"wt"` (write+truncate) to avoid stale trailing bytes on Android 10+

---

## Firebase Free Tier (Spark Plan) Usage Estimate

Assumptions: **4 devices**, **~4 hours express mode/day**, **~10 start/stop button clicks/day**, **~20 hours normal mode/day**.

### Firestore Writes (Free limit: 20K/day)

| Operation | Calculation | Daily Writes |
|---|---|---:|
| Normal mode GPS saves | 4 devices × 20h × 60 fixes/hr × ~30% pass filter | ~1,440 |
| Express mode GPS saves | 4 devices × 4h × 360 fixes/hr (10s, no filter) | ~5,760 |
| Express sync uploads | 4 devices × 4h × 60 syncs/hr (batch writes) | ~5,760 |
| Normal sync uploads | 4 devices × 20h ÷ 0.25h × ~4.5 records | ~1,440 |
| Express start/stop docs | 10 clicks × 1 write | 10 |
| **Total** | | **~14,410** |

**~72% of free limit — safe with headroom** |

### Firestore Reads (Free limit: 50K/day)

| Operation | Calculation | Daily Reads |
|---|---|---:|
| Web dashboard | ~4 users × few refreshes × ~100 docs | ~400 |
| Cloud Function triggers | ~10 reads | 10 |
| **Total** | | **~410** |

**< 1% of free limit** |

### Cloud Functions (Free limit: 125K invocations/month, 40K GB-seconds/month)

| Metric | Calculation | Monthly |
|---|---|---:|
| Invocations | 10/day × 30 days | 300 |
| Compute | ~100ms × 256MB per invocation | ~0.75 GB-s |

**< 1% of free limits** |

### Firebase Cloud Messaging

**Completely free** — no limits on data messages. ~10 topic messages/day = no cost.

### Summary

| Service | Daily Usage | Free Limit | Utilisation |
|---|---|---|---:|
| Firestore writes | ~14.4K | 20K | ~72% |
| Firestore reads | ~410 | 50K | < 1% |
| Cloud Functions | ~10/day | ~4.1K/day | < 1% |
| FCM | 10 msgs | Unlimited | 0% |

> **Verdict**: Comfortably within free tier. The tightest constraint is Firestore writes at ~72%.
> Adding more devices or increasing express usage could approach the limit — at that point
> consider upgrading to Blaze pay-as-you-go ($0.18 per 100K writes).
