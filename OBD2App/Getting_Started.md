# Getting Started — OBD2 App

## Overview

The app is organised into four swipeable main screens — **Bluetooth Devices**, **Trip**, **Dashboard**, and **Details** — plus secondary screens (**Trip Summary**, **Map View**, **Settings**, **CAN Bus Reader**) accessible from the **⋮ (three-dot) overflow menu** in the top-right corner of any screen.

---

## 1. First-Time Setup

### Grant Permissions
On first launch the app requests Bluetooth and Location permissions. Both are required — tap **Allow** on each prompt.

### Set a Log Folder *(Settings → Log Folder)*
1. Open the overflow menu **⋮** → **Settings**.
2. Tap **Set Log Folder** and choose (or create) a folder where trip JSON files will be saved. This is where Trip Summary reads its files from.

### Configure Adapter Mode *(Settings → Adapter Mode)*
- **OBD Polling** — standard OBD-II PID queries (default).


> Ignore the CAN modes for basic ELM327 adapters, as they do not support CAN sniffing

---

## 2. Vehicle Profile

A Vehicle Profile stores your car's fuel type, engine displacement, and tank capacity for accurate fuel and power calculations.

1. Open the overflow menu **⋮** → **Settings** → **Vehicle Profiles**.
2. Tap **+ Add Profile**, fill in the details, then tap **Save**.
3. Tap the profile row to mark it as **active** (shown with a highlight). The active profile is used for all trip calculations.

---

## 3. Connecting to the OBD2 Adapter

1. Pair your ELM327 / STN adapter in Android **System Bluetooth Settings** first.
2. Open the **Bluetooth Devices** screen (the app opens here by default).
3. The screen shows a **mode badge** (e.g. *OBD POLLING*, *HS-CAN 500 kbps*, *MS-CAN 125 kbps*) reflecting the current adapter mode.
4. Tap your paired adapter in the device list — the status bar changes:
   - 🟡 **Connecting…**
   - 🟢 **Connected**
5. On successful connection the adapter runs its initialisation sequence automatically. Connection log lines are shown in the lower panel for diagnostics.

> **Auto-Connect:** Enable *Auto-Connect* in Settings to reconnect automatically whenever the app starts.

---

## 4. Starting and Stopping a Trip

Swipe right from **Bluetooth Devices** to reach the **Trip** screen, or use the overflow menu **⋮** → **Trip**.

### Sensor Readiness panel
Before starting, check the indicator dots:
- **OBD2** — green when the adapter is connected and responding.
- **GPS** — green when a location fix is obtained; accuracy and satellite count shown below.
- **Accel** — green when the accelerometer is active (used for G-force and power estimation).
- **Logging** — green when a log folder is set and file logging is enabled.

### Starting a Trip
Tap **START TRIP**. The phase indicator changes from *IDLE* → *RUNNING* and a live sample counter increments. The screen stays on during an active trip.

### Stopping a Trip
Tap **STOP TRIP**. The phase returns to *IDLE*, the log file is written to the log folder, and the screen lock is released.

> **Trip Summary, Settings, and Map View are locked** (greyed out in the overflow menu) while a trip is running.

---

## 5. Dashboard Widget

Swipe right from the **Trip** screen to reach the **Dashboard** screen.

- The app opens the **default dashboard** automatically if one is starred.
- To browse or create layouts, tap the **Layout List** button (top-left) or use **⋮** → **Dashboards**.
- **Edit Mode:** Long-press any widget tile to enter edit mode. Drag tiles to rearrange; tap a tile to change the metric it displays. Tap **Done** to exit edit mode.
- **Portrait / Landscape** — separate layouts can be saved for each orientation.

### Available widget types
RPM · Speed · Engine Load · Throttle · Coolant Temp · Fuel Level · Fuel Economy · Trip Distance · Trip Duration · GPS Altitude · G-Force · Estimated Power · CAN signals (in CAN mode)

---

## 6. Map Widget (Live GPS Track)

The **Details** screen (swipe right from Dashboard, or **⋮** → **Details**) shows a live GPS track map that updates in real time during a trip.

- The blue trail is drawn from GPS points collected since the app started.
- Zoom and pan with standard pinch/drag gestures.
- The map is read-only during recording; no interaction needed.

---

## 7. Viewing a Recorded Trip

### Trip Summary screen
Open via **⋮** → **Trip Summary** (only accessible when no trip is running).

1. Tap **Reload** to refresh the list of saved trip files from the log folder.
2. Tap a file row to select it — the summary panel expands below the list showing:
   - **Vehicle Profile** used (name, fuel type, tank capacity)
   - **Trip stats**: distance, duration, average and max speed, estimated fuel used, average fuel economy
   - **OBD stats**: average and peak RPM, average engine load, average throttle, coolant temperature range
3. Tap **Analyze** (activates once a file is selected) to load the full detailed breakdown.

### Trip Map screen
1. With a trip file selected in Trip Summary, open **⋮** → **Map View**, or tap the map thumbnail in the summary panel.
2. The recorded GPS track is drawn as a coloured polyline from start to finish, with start/end markers.
3. Tap any point on the track to see a tooltip with speed, altitude, and timestamp at that sample.
4. Use the toolbar buttons to **Export as KML** (share to Google Maps / Earth) or **Export CSV** (raw data for analysis).

> Map View is **only accessible when a trip file is selected** in Trip Summary.

---

## Quick Reference

| Screen | How to reach | Swipe access |
|---|---|---|
| Bluetooth Devices | App launch / **⋮ → Connect** | ✓ |
| Trip | **⋮ → Trip** | ✓ |
| Dashboard | **⋮ → Dashboards** | ✓ |
| Details (live map) | **⋮ → Details** | ✓ |
| Trip Summary | **⋮ → Trip Summary** | ✗ (menu only) |
| Map View | **⋮ → Map View** (from Trip Summary) | ✗ (menu only) |
| Settings | **⋮ → Settings** | ✗ (menu only) |
| CAN Bus Reader | **⋮ → CAN Bus Reader** *(visible in CAN mode only)* | ✗ (menu only) |
