# OBD2 Viewer — Quick Start User Guide

**Your Complete Vehicle Diagnostics & Trip Computer**

---

## 📱 What is OBD2 Viewer?

OBD2 Viewer transforms your Android phone into a powerful vehicle diagnostics tool and trip computer. Connect to your car's OBD-II port via Bluetooth, view live engine data, track trips with GPS, and analyze your driving with customizable dashboards.

---

## 🚀 Quick Start (5 Steps)

### 1. **Hardware Setup**
- Plug your **ELM327 Bluetooth adapter** into your vehicle's OBD-II port (usually under the dashboard near the steering wheel)
- Turn on your vehicle's ignition (engine can be off for initial setup)
- **Pair the adapter** with your Android device via Settings → Bluetooth (typically shows as "OBDII" or "ELM327")

### 2. **First Launch**
- Open OBD2 Viewer
- Grant **Bluetooth** and **Location** permissions when prompted
- Enable Bluetooth if prompted

### 3. **Connect to Your Vehicle**
- Tap the **Connect** tab
- Find your paired OBD-II adapter in the device list
- Tap the device name to connect
- Wait for "Connected" status (takes 5-10 seconds)

### 4. **Create Your Vehicle Profile** (Optional but Recommended)
- Go to **Settings** tab
- Tap **"+ Add Profile"**
- Enter: Vehicle name, fuel type, tank capacity, fuel price
- Advanced: Engine power (BHP), vehicle mass (kg) for power calculations
- Tap **Save**

### 5. **Start Your First Trip**
- Go to **Trip** tab
- Tap **"Start Trip"** button
- Drive normally — the app tracks everything automatically
- Tap **"Stop Trip"** when done

---

## 📊 Main Features

### **🎯 Trip Computer**
- **Real-time metrics**: Distance, duration, fuel used, average speed
- **Fuel efficiency**: Instant and trip average (L/100km or km/L)
- **Drive mode analysis**: % time in city/highway/idle
- **GPS tracking**: Accurate distance and speed with satellite positioning
- **Trip logging**: Save detailed JSON logs for analysis

### **📈 Live Dashboard**
- **Customizable layouts**: Create unlimited dashboard configurations
- **6 widget types**: Dial gauges, 7-segment displays, bar gauges, numeric displays, temperature arcs
- **50+ metrics**: RPM, speed, temperatures, fuel, power, acceleration, and more
- **Color schemes**: Dark, Neon Red, Green LCD themes
- **Warning zones**: Set thresholds for visual alerts

### **🔧 Diagnostics**
- **OBD-II parameters**: Engine load, coolant temp, throttle, MAF, fuel trim, O2 sensors, and more
- **Auto-discovery**: Only displays parameters your vehicle supports
- **Details view**: Complete table of all live sensor readings

### **📍 GPS Integration**
- **Accurate tracking**: Uses Google Play Services location
- **Altitude correction**: MSL altitude with geoid correction
- **Satellite count**: Shows GPS signal quality
- **Speed validation**: Cross-checks OBD speed vs GPS speed

### **📱 Accelerometer Analysis** (Optional)
- **Road quality tracking**: Detects bumps and potholes
- **G-force measurement**: Forward/lateral/vertical acceleration
- **Lean angle**: Vehicle tilt during cornering
- **Brake/acceleration peaks**: Maximum forces recorded

---

## 🎮 Using the App

### **Navigation**
Swipe between 5 main screens or use the bottom navigation bar:
1. **Trip** — Start/stop trips, view live stats
2. **Connect** — Bluetooth device connection
3. **Dashboards** — Customizable gauge layouts
4. **Details** — Full OBD-II parameter table
5. **Settings** — Vehicle profiles and preferences

### **Recording a Trip**

**Start:**
1. Ensure OBD-II adapter is connected (green indicator on Trip screen)
2. Tap **"Start Trip"**
3. Drive normally — all data is recorded automatically
4. App runs in background with persistent notification

**Pause/Resume:**
- Tap **"Pause"** to temporarily stop recording (e.g., at a gas station)
- Tap **"Resume"** to continue the same trip
- Paused time is excluded from trip calculations

**Stop:**
- Tap **"Stop Trip"** to end recording
- Trip log is automatically saved (if logging enabled in Settings)
- View trip summary before closing

### **Creating Custom Dashboards**

1. Go to **Dashboards** tab → Tap **menu (⋮)** → **"New Layout"**
2. Choose a name and color scheme
3. Tap **"+ Add Widget"**
4. Select:
   - **Metric** (e.g., RPM, Speed, Coolant Temp)
   - **Widget type** (Dial, 7-Segment, Bar, etc.)
   - **Range** (min/max values, auto-populated)
   - **Warning threshold** (optional)
5. **Drag** widget to position, **resize** using corner handles
6. Tap **Save** when done
7. Switch between layouts via the layout list

### **Managing Vehicle Profiles**

**Why use profiles?**
- Different vehicles have different fuel types, tank sizes, and engine specs
- Profiles enable accurate fuel cost and power calculations
- Set custom OBD polling rates per vehicle

**Create a profile:**
1. Settings → **"+ Add Profile"**
2. Fill in basic info (name, fuel type, tank capacity, fuel price)
3. Optional: Engine power (BHP) and vehicle mass (kg) for power metrics
4. Tap **Save**

**Switch profiles:**
- Tap any profile in the list to make it active
- Active profile shows a blue badge

---

## ⚙️ Settings Explained

| Setting | What it does |
|---------|-------------|
| **OBD Connection** | Toggle between real OBD adapter and mock mode (for testing) |
| **Auto-connect** | Automatically connect to last used device when opening Connect screen |
| **Enable Logging** | Save trip data to JSON files in selected folder |
| **Auto-share Log** | Automatically open share dialog after trip ends |
| **Log Folder** | Choose where trip logs are saved (default: Downloads) |
| **Accelerometer** | Enable road quality and G-force tracking (uses more battery) |
| **Bluetooth Logging** | Save raw Bluetooth communication for debugging |

---

## 💡 Tips & Best Practices

### **For Best Results:**
✅ **Keep GPS enabled** — Required for accurate distance and speed  
✅ **Create a vehicle profile** — Enables fuel cost and power calculations  
✅ **Start trip after engine starts** — Ensures all sensors are active  
✅ **Keep phone charged** — GPS + Bluetooth drain battery faster  
✅ **Use a phone mount** — Safely view dashboard while driving  

### **Troubleshooting:**

**Can't connect to adapter?**
- Verify adapter is paired in Android Bluetooth settings first
- Ensure vehicle ignition is ON
- Try unplugging and re-plugging the adapter
- Check adapter LED is blinking (indicates power)

**No GPS signal?**
- Ensure Location permission is granted
- Move away from buildings/tunnels for initial GPS lock
- Wait 30-60 seconds for satellite acquisition

**Missing OBD parameters?**
- Your vehicle may not support all parameters
- Only ECU-supported parameters are displayed
- Older vehicles support fewer parameters

**Dashboard widgets not updating?**
- Ensure OBD adapter is connected (check Connect screen)
- Verify the metric is supported by your vehicle
- Check Details screen to see raw values

**Battery draining fast?**
- Disable accelerometer if not needed (Settings)
- Reduce screen brightness
- Close other GPS apps running in background

---

## 📁 Trip Logs

**Format:** JSON files with complete trip data  
**Filename:** `<ProfileName>_obdlog_<YYYY-MM-DD_HHmmss>.json`  
**Location:** Chosen log folder (or Downloads by default)  
**Contains:** All OBD-II readings, GPS coordinates, accelerometer data, trip statistics

**Viewing logs:**
- Use any JSON viewer or text editor
- Import into spreadsheet software for analysis
- Share via email, cloud storage, or USB

---

## 🔒 Permissions Required

| Permission | Why it's needed |
|------------|-----------------|
| **Bluetooth** | Connect to OBD-II adapter |
| **Location** | GPS tracking for distance/speed + required for Bluetooth scanning on Android 12+ |
| **Foreground Service** | Keep trip recording active in background |

**Privacy:** All data stays on your device. No data is sent to external servers.

---

## 📱 System Requirements

- **Android 8.0** or higher
- **Bluetooth Classic** support (all modern phones)
- **GPS** for trip tracking
- **Accelerometer** (optional, for road quality analysis)

**Compatible Adapters:**
- Any **ELM327 Bluetooth** adapter (Classic BT, not BLE)
- Recommended: BAFX, OBDLink, Veepeak brands

---

## 🆘 Support & Resources

**App Version:** Check Settings screen for current build number  
**Documentation:** See `README.md` and `ARCHITECTURE.md` in app repository  
**Versioning:** Build numbers auto-increment — use for bug reports

**Common Questions:**

*Q: Can I use this with multiple vehicles?*  
A: Yes! Create a separate profile for each vehicle and switch between them.

*Q: Does this work with diesel engines?*  
A: Yes, select "Diesel" as fuel type in your vehicle profile.

*Q: Will this drain my car battery?*  
A: No. The OBD-II port is powered only when ignition is on. Always unplug the adapter when not in use for extended periods.

*Q: Can I export trip data?*  
A: Yes, enable logging in Settings. Trip logs are saved as JSON files you can share or analyze.

*Q: Why are some metrics showing "—" or blank?*  
A: Your vehicle's ECU doesn't support those parameters. Only supported sensors are polled.

---

## 🎯 Quick Reference Card

| Action | How to do it |
|--------|-------------|
| **Connect to car** | Connect tab → Tap device name |
| **Start trip** | Trip tab → Start Trip button |
| **Pause trip** | Trip tab → Pause button |
| **Stop trip** | Trip tab → Stop Trip button |
| **View live data** | Details tab (full table) or Dashboards tab (gauges) |
| **Create dashboard** | Dashboards → ⋮ menu → New Layout |
| **Add vehicle** | Settings → + Add Profile |
| **Switch vehicle** | Settings → Tap profile name |
| **Enable logging** | Settings → Enable Logging toggle |
| **Choose log folder** | Settings → Change Log Folder |

---

**Drive safe and enjoy your OBD2 Viewer experience!** 🚗💨
