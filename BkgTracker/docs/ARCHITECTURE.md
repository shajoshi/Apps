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
    participant Cache as LocationCache
    participant WM as WorkManager (15-min)
    participant Repo as LocationRepositoryImpl
    participant FS as Cloud Firestore

    Note over SVC: Service starts on sign-in or boot
    loop Every 15 seconds
        GPS->>SVC: onLocationResult(location)
        SVC->>SVC: Filter (accuracy > 100m? skip)
        SVC->>SVC: Filter (distance < 20m? skip)
        SVC->>Cache: add(LocationRecord)
    end

    Note over WM: Periodic trigger every 15 min
    WM->>Cache: drainAll()
    Cache-->>WM: List<LocationRecord>
    WM->>Repo: uploadBatch(records)
    Repo->>FS: batch.set(/locations/{uid}/records/*)
    FS-->>Repo: success
    Repo-->>WM: Result.success
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

    subgraph "Data Layer"
        LC[LocationCache<br/>ConcurrentLinkedQueue + StateFlow]
        SP[SyncPrefs<br/>SharedPreferences]
        ESM[ExpressSyncManager<br/>SharedPreferences + StateFlow]
        Repo[LocationRepositoryImpl]
    end

    subgraph "Services"
        LFS[LocationForegroundService<br/>GPS + Express Sync Timer]
        FCM_SVC[BkgTrackerMessagingService<br/>FCM Receiver]
        SW[SyncWorker<br/>WorkManager 15-min]
        BR[BootReceiver]
    end

    subgraph "External"
        FLP[FusedLocationProviderClient]
        FAuth[Firebase Auth]
        FStore[Cloud Firestore]
        GFCM[Firebase Cloud Messaging]
    end

    MA --> VM
    MA --> MVM
    MS --> VM
    MapS --> MVM
    VM --> LC
    VM --> SP
    VM --> ESM
    VM --> Repo
    LFS --> FLP
    LFS --> LC
    LFS --> ESM
    LFS --> Repo
    FCM_SVC --> ESM
    SW --> LC
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

- **GPS Fix Interval (Normal)**: 60 seconds (PRIORITY_HIGH_ACCURACY via FusedLocationProviderClient)
- **GPS Fix Interval (Express)**: 10 seconds — filters bypassed for maximum data capture
- **Distance Filter**: Only saves if moved ≥ 20m from last saved point (normal mode only, bypassed in express)
- **Accuracy Filter**: Skips if GPS accuracy > 100m (normal mode only, bypassed in express)
- **Normal Sync**: WorkManager every 15 minutes (requires network)
- **Express Sync**: 60-second timer in ForegroundService, lasts 1 hour, FCM broadcast to all family devices
- **Express Sync Stop**: Any device can stop express sync for all devices via FCM broadcast
- **Express Status**: All devices show "Express Sync mode till {time} activated by {user}" or "Express Sync stopped by {user}"
- **Points (24h)**: Rolling 24-hour counter of saved GPS points, not reset by sync drain
- **Cache**: MutableList in memory + JSON file persistence
- **Auth**: Google Sign-In → Firebase Auth token → Firestore security rules
- **Boot Resilience**: BootReceiver restarts foreground service after reboot if signed in
