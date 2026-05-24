Here are the exact steps, in order:

---

## Step 1 — Create the Firebase Project

1. Go to **[console.firebase.google.com](https://console.firebase.google.com)**
2. Click **"Add project"**
3. Name it [BkgTracker](cci:9://file:///c:/Code/Apps/BkgTracker:0:0-0:0) → click **Continue**
4. **Disable** Google Analytics (not needed) → click **Create project**
5. Wait ~30 seconds → click **Continue**

---

## Step 2 — Register the Android App

1. On the project overview page, click the **Android icon** (🤖)
2. **Android package name:** `com.sj.bkgtracker`
3. **App nickname:** [BkgTracker](cci:9://file:///c:/Code/Apps/BkgTracker:0:0-0:0) (optional)
4. Leave SHA-1 blank for now → click **Register app**
5. Click **Download google-services.json**
6. Place the file at: `c:\Code\Apps\BkgTracker\app\google-services.json`
7. Click **Next → Next → Continue to console** (skip the SDK steps, they're already in the build files)

---

## Step 3 — Get the SHA-1 Fingerprint

Open a terminal in [c:\Code\Apps\BkgTracker\](cci:9://file:///c:/Code/Apps/BkgTracker:0:0-0:0) and run:
```powershell
.\gradlew signingReport
```
Look for the **debug** variant output — copy the **SHA-1** line, e.g.:
```
SHA-1: AA:BB:CC:DD:EE:FF:...
```

Then in Firebase Console:
1. Click **⚙️ Project Settings** (gear icon, top-left)
2. Under **"Your apps"** → find the Android app → click **"Add fingerprint"**
3. Paste the SHA-1 → click **Save**

---

## Step 4 — Enable Google Sign-In

1. In the left sidebar → **Build → Authentication**
2. Click **"Get started"** (first time only)
3. Click **"Google"** under Sign-in providers
4. Toggle **Enable** → ON
5. Set a **Project support email** (your Gmail address)
6. Click **Save**

---

## Step 5 — Get the Web Client ID

This is what goes into [app/build.gradle.kts](cci:7://file:///c:/Code/Apps/BkgTracker/app/build.gradle.kts:0:0-0:0):

1. Still in **Authentication → Sign-in method → Google**
2. Expand the Google provider row → look for **"Web SDK configuration"**
3. Copy the **Web client ID** — it looks like: `123456789-abcdefg.apps.googleusercontent.com`
4. Open [c:\Code\Apps\BkgTracker\app\build.gradle.kts](cci:7://file:///c:/Code/Apps/BkgTracker/app/build.gradle.kts:0:0-0:0) and replace line 21:
```kotlin
buildConfigField("String", "FIREBASE_WEB_CLIENT_ID", "\"PASTE_YOUR_WEB_CLIENT_ID_HERE\"")
```

---

## Step 6 — Create the Firestore Database

1. Left sidebar → **Build → Firestore Database**
2. Click **"Create database"**
3. Select **"Start in production mode"** → click **Next**
4. Choose a **location** (e.g., `asia-south1` for India) → click **Enable**

---

## Step 7 — Set Firestore Security Rules

1. In Firestore → click **"Rules"** tab
2. Replace everything with:

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /locations/{userId}/records/{recordId} {
      allow write: if request.auth != null && request.auth.uid == userId;
      allow read:  if request.auth != null && request.auth.token.email in [
        "your@gmail.com",
        "familymember@gmail.com"
      ];
    }
  }
}
```
3. Replace the emails with your actual family Google accounts
4. Click **Publish**

---

## Step 8 — Create the Firestore Index (for Dashboard)

The web dashboard queries by `timestamp` across all users. Firebase will auto-prompt you to create the index the **first time** the dashboard loads — click the link in the browser console error. Alternatively create it manually:

1. Firestore → **Indexes** tab → **Add index**
2. **Collection group:** `records`
3. Fields: `timestamp` Ascending
4. **Query scope:** Collection group
5. Click **Create**

---

## Step 9 — Register a Web App (for Dashboard)

1. **⚙️ Project Settings** → click **"Add app"** → choose **Web (◇)**
2. App nickname: `BkgTracker Dashboard`
3. Check **"Also set up Firebase Hosting"** → click **Register app**
4. Copy the `firebaseConfig` object that appears
5. Open [c:\Code\Apps\BkgTracker\web\index.html](cci:7://file:///c:/Code/Apps/BkgTracker/web/index.html:0:0-0:0) and replace the config block near the top:

```js
const firebaseConfig = {
  apiKey:            "AIza...",
  authDomain:        "bkgtracker-xxxxx.firebaseapp.com",
  projectId:         "bkgtracker-xxxxx",
  storageBucket:     "bkgtracker-xxxxx.appspot.com",
  messagingSenderId: "123456789",
  appId:             "1:123456789:web:abcdef"
};
```
6. Click **Next → Continue to console**

---

## Step 10 — Deploy the Web Dashboard (optional)

```powershell
npm install -g firebase-tools
firebase login
firebase init hosting
# When prompted:
#   Public directory: web
#   Single-page app: No
#   Overwrite index.html: No
firebase deploy --only hosting
```
The dashboard URL will be: `https://bkgtracker-xxxxx.web.app`

---

## Build Order Summary

| Order | Action |
|---|---|
| 1 | Complete steps 1–7 above |
| 2 | Place `google-services.json` in [app/](cci:9://file:///c:/Code/Apps/BkgTracker/app:0:0-0:0) |
| 3 | Replace `FIREBASE_WEB_CLIENT_ID` in [build.gradle.kts](cci:7://file:///c:/Code/Apps/OBD2App/build.gradle.kts:0:0-0:0) |
| 4 | Build & run on device — grant all location permissions |
| 5 | Sign in with Google → tracking starts automatically |
| 6 | Fill web dashboard config → deploy or open locally |