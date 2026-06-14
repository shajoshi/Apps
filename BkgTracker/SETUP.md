# BkgTracker — Setup Guide

## 1. Copy the Gradle Wrapper

The `gradle-wrapper.jar` binary is not included in this repo. Copy it from the sibling OBD2App project:

```powershell
Copy-Item ..\OBD2App\gradle\wrapper\gradle-wrapper.jar .\gradle\wrapper\
Copy-Item ..\OBD2App\gradlew     .\
Copy-Item ..\OBD2App\gradlew.bat .\
```

After copying, Android Studio will detect the project and sync Gradle automatically.

---

## 2. Create a Firebase Project

1. Go to [console.firebase.google.com](https://console.firebase.google.com) → **Add project**
2. Name it `BkgTracker` → disable Google Analytics (optional) → **Create project**

---

## 3. Enable Firebase Authentication (Google)

1. In Firebase Console → **Authentication** → **Sign-in method**
2. Enable **Google** as a provider
3. Save

---

## 4. Enable Firestore

1. Firebase Console → **Firestore Database** → **Create database**
2. Choose **Production mode**
3. Pick a region close to you

### Firestore Security Rules

The canonical, hardened rules live in **`firestore.rules`** at the repo root — that
file is the single source of truth. Do NOT paste ad-hoc rules here; edit `firestore.rules`
and deploy it instead:

```bash
firebase deploy --only firestore:rules
```

The rules enforce:
- Read/write restricted to the family email allowlist (`isFamily()`).
- Writers may only write their **own** `locations/{uid}` (owner check).
- The stored `email` field must match the caller's authenticated email (anti-spoofing).
- Exact field allowlists + type/range checks on location records; records are append-only.
- `express_sync` writes limited to the caller's own doc with a validated start/stop payload.

Update the email list inside `firestore.rules` (`isFamily()`) to match your family's Google accounts.

---

## 5. Register the Android App

1. Firebase Console → ⚙️ **Project Settings** → **Add app** → Android (🤖)
2. Package name: `com.sj.bkgtracker`
3. Download **`google-services.json`**
4. Place it at: `BkgTracker/app/google-services.json`

> `google-services.json` contains no secrets — it is safe to commit.

---

## 6. Add Your SHA-1 Fingerprint (required for Google Sign-In)

Run in a terminal from the project root:

```powershell
.\gradlew signingReport
```

Copy the **SHA-1** from the `debug` variant. Then:

1. Firebase Console → ⚙️ Project Settings → Android app → **Add fingerprint**
2. Paste the SHA-1 → **Save**

---

## 7. Set the Web Client ID in build.gradle.kts

1. Firebase Console → ⚙️ Project Settings → **General** → scroll to **Your apps** → **Web app** (or create one)
2. Or: Firebase Console → **Authentication** → **Sign-in method** → Google → expand → copy **Web client ID**
3. In `app/build.gradle.kts`, replace the placeholder:

```kotlin
buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"YOUR_WEB_CLIENT_ID_HERE\"")
```

---

## 8. Configure the Web Dashboard

Edit `web/index.html` and replace the `firebaseConfig` block with the config from:

Firebase Console → ⚙️ Project Settings → **Web app** → **Firebase SDK snippet** → **Config**

```js
const firebaseConfig = {
  apiKey:            "...",
  authDomain:        "your-project.firebaseapp.com",
  projectId:         "your-project",
  storageBucket:     "your-project.appspot.com",
  messagingSenderId: "...",
  appId:             "..."
};
```

### Deploy to Firebase Hosting (free)

```bash
npm install -g firebase-tools
firebase login
firebase init hosting   # public dir = web, single-page app = no
firebase deploy --only hosting
```

---

## 9. Firestore Composite Index (required for dashboard query)

The dashboard queries `records` (collectionGroup) filtered by `timestamp` and ordered. Firebase will log a link to create the required index the first time you run the query — click it to auto-create.

---

## Build & Run

Open `BkgTracker/` in Android Studio → sync Gradle → run on device.

The app will:
1. Prompt Google Sign-In on first launch
2. Request location permissions (grant "Allow all the time" for background tracking)
3. Start the `LocationForegroundService` automatically
4. Show a persistent notification while GPS is active
5. Upload batches to Firestore every 15 minutes when connected
