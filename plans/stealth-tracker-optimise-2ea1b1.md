# StealthTracker — Cost Optimisation + GCP Serverless Migration

Optimise the Android app for minimal GPS data storage and network usage, and replace the Flask/SQLite server with a GCP Cloud Functions + Firestore serverless stack.

---

## Part A — Android optimisations (TrackingService + Uploader)

### Changes to `ApiConfig.kt` (new constants)
```kotlin
const val MIN_ACCURACY_M          = 10f    // discard fix if accuracy worse than this
const val MIN_MOVE_DISTANCE_M     = 10f    // discard fix if moved less than this since last saved fix
const val GPS_INTERVAL_MS         = 5_000L // already exists — confirm no change
const val AUTO_UPLOAD_INTERVAL_MS = 5 * 60 * 1000L  // already exists
```

### Fix filtering in `TrackingService.onLocationFix()`
Current: saves every fix unconditionally.  
New logic (all checks before `insertLocation`):
1. **Accuracy gate** — `location.hasAccuracy() && location.accuracy > MIN_ACCURACY_M` → discard
2. **Movement gate** — compute distance from `lastSavedLocation` → if `< MIN_MOVE_DISTANCE_M` → discard
3. Save fix and update `lastSavedLocation`

### Upload gate in `TrackingService.uploadRunnable` / `triggerUploadNow()`
Before calling `uploader.uploadPending()`:
- Check **network connectivity** via `ConnectivityManager.activeNetworkInfo?.isConnected`
- Check **device is moving** — `lastSavedLocation?.speed > 0` or tracked `isMoving` flag (speed > 0.5 m/s from last fix)
- If either check fails → skip upload, log reason, reschedule as normal

### New import needed in `TrackingService.kt`
`android.net.ConnectivityManager`, `android.location.Location.distanceTo`

---

## Part B — Cloud provider recommendation

### Free tier comparison (per-invocation billing, no idle VM cost)

| Provider | Serverless compute | Free invocations/month | Free DB | Best fit |
|----------|--------------------|------------------------|---------|----------|
| **GCP** | Cloud Functions 2nd gen | **2 million** | Firestore: 50K reads/day, **20K writes/day**, 1 GB storage free | ✅ Best overall |
| AWS | Lambda | 1 million | DynamoDB: 25 GB, 25 WCU free | Good but Firestore writes/day is more generous |
| Azure | Functions Consumption | 1 million | Cosmos DB: 1000 RU/s free (limited) | Weakest free DB tier |

**Recommendation: GCP Cloud Functions + Firestore**
- 2M free invocations/month (double AWS/Azure)
- Firestore free tier generous for this workload: even at 1 fix/5 s while moving = 720 writes/hour ≈ 17K writes/day → stays within 20K free daily writes
- KML export becomes a second Cloud Function (HTTP trigger, no extra infrastructure)
- No container to build or manage — deploy with `gcloud functions deploy`

### Why not REST over HTTP trigger?
The user said "it need not be REST". GCP HTTP-triggered Cloud Functions accept any HTTP POST — we keep the same `POST /locations` contract but the function is invoked per-call (no always-on server). This is functionally identical to REST from the Android side. No protocol change needed.

---

## Part C — New server: GCP Cloud Functions + Firestore

### Replace `server/` with `server_gcp/`

| File | Role |
|------|------|
| `main.py` | Two Cloud Functions: `receive_locations` (HTTP POST) + `export_kml` (HTTP GET) |
| `auth.py` | API key validation against Firestore `registered_keys` collection |
| `requirements.txt` | `functions-framework`, `google-cloud-firestore` |
| `deploy.sh` | `gcloud functions deploy` commands for both functions |
| `init_keys.py` | Local script: creates Firestore `registered_keys` doc for a new device |
| `export_kml_local.py` | Local CLI script querying Firestore directly for offline KML export |

### Firestore schema
```
Collection: registered_keys
  doc id = <api_key>
    user_id: string
    created_at: timestamp

Collection: locations
  doc id = auto
    user_id: string
    ts: number (epoch ms)
    lat: number
    lon: number
    alt: number | null
    bearing: number | null
    speed: number | null
    received_at: timestamp
```
Index: composite on `(user_id ASC, ts ASC)` — needed for KML export query.

### `receive_locations` function
- `POST /receive_locations`
- Header: `X-Api-Key`
- Body: `{ "locations": [...] }` (same schema as before — no Android change needed)
- Validates key → batch-writes to Firestore → returns `{ "saved": N }`

### `export_kml` function  
- `GET /export_kml?user=<id>&from=<iso>&to=<iso>`
- Secured by same API key header (or a separate admin key)
- Queries Firestore by `user_id + ts range` → streams KML response

### Android change for new endpoint
Only `ApiConfig.BASE_URL` changes — point to the Cloud Function URL.  
Format: `https://<region>-<project>.cloudfunctions.net`

---

## Implementation order

1. **Android** — `ApiConfig.kt` (new constants) → `TrackingService.kt` (accuracy + movement gate + network + moving check on upload)
2. **Server** — create `server_gcp/` with `main.py`, `auth.py`, `requirements.txt`, `deploy.sh`, `init_keys.py`, `export_kml_local.py`
3. Update `README.md` — GCP setup steps, deployment commands, updated `ApiConfig` instructions
