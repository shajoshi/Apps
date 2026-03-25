# Support Unpaired Bluetooth Devices in Device List

Modify the Connect screen to show all available Bluetooth devices (both paired and unpaired) organized in sections, with manual scan control via the magnifying glass icon, allowing users to connect to OBD2 adapters without prior pairing.

## User Requirements

1. **Manual scan only**: Scan triggered only when user taps magnifying glass icon (no auto-scan)
2. **Three-section layout**: 
   - Potential OBD devices (paired + unpaired, filtered by keywords)
   - Other paired devices
   - Other unpaired devices
   - All sections sorted by signal strength (RSSI)
3. **Scan control**: Scan only when icon is tapped (not on screen load)

## Current Implementation Analysis

The app currently has:
- **Paired devices**: Split into `obdDevices` and `otherDevices`, shown automatically
- **Discovered devices**: Only unpaired devices, shown after manual scan, explicitly excludes paired devices (line 103)
- **No RSSI tracking**: Discovery receiver doesn't capture signal strength

## Proposed Changes

### 1. Data Model (`ConnectViewModel.kt`)

**New data structure to track device metadata:**
```kotlin
data class DeviceInfo(
    val device: BluetoothDevice,
    val isPaired: Boolean,
    val isObd: Boolean,
    val rssi: Int = Int.MIN_VALUE  // Signal strength
)
```

**ViewModel changes:**
- Keep existing `_pairedDevices` for internal tracking
- Replace `_obdDevices`, `_otherDevices`, `_discoveredDevices` with three new LiveData:
  - `_obdDevices: MutableLiveData<List<DeviceInfo>>` (OBD-like, paired + unpaired)
  - `_pairedOtherDevices: MutableLiveData<List<DeviceInfo>>` (Non-OBD paired)
  - `_unpairedOtherDevices: MutableLiveData<List<DeviceInfo>>` (Non-OBD unpaired)
- Modify discovery receiver to:
  - Capture RSSI from `BluetoothDevice.EXTRA_RSSI` intent extra
  - Include ALL discovered devices (remove line 103 exclusion)
  - Merge with paired devices to create unified sections

### 2. Discovery Receiver Enhancement

**Capture signal strength:**
```kotlin
BluetoothDevice.ACTION_FOUND -> {
    val device = intent.getParcelableExtra(...)
    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
    // Store device with RSSI
}
```

**Rebuild sections after scan:**
- Combine paired devices (with RSSI = 0 or cached) + discovered devices (with actual RSSI)
- Filter into three categories: OBD, paired-other, unpaired-other
- Sort each section by RSSI (descending)

### 3. Fragment Changes (`ConnectFragment.kt`)

**Single RecyclerView with section headers (Option C):**
- Use only `recyclerviewDevices` for all content
- Hide/remove `recyclerviewDiscovered` and section labels
- Create sealed class for list items (Header vs Device)
- Single scrollable list with visual section separators
- Remove auto-scan logic (keep manual scan only)

### 4. New Adapter - `SectionedDeviceAdapter.kt`

**Create new adapter with multiple view types:**

```kotlin
sealed class DeviceListItem {
    data class Header(val title: String, val count: Int) : DeviceListItem()
    data class Device(val info: DeviceInfo) : DeviceListItem()
}
```

**Adapter features:**
- Two view types: `VIEW_TYPE_HEADER` and `VIEW_TYPE_DEVICE`
- Header view: Section title + device count
- Device view: Reuse existing `ItemDeviceBinding` layout
- Add "PAIRED" badge for paired devices in any section
- Handle click events only on device items (not headers)

**Section building logic:**
```kotlin
fun buildSectionedList(
    obdDevices: List<DeviceInfo>,
    pairedOther: List<DeviceInfo>,
    unpairedOther: List<DeviceInfo>
): List<DeviceListItem> {
    val items = mutableListOf<DeviceListItem>()
    
    if (obdDevices.isNotEmpty()) {
        items.add(Header("Potential OBD Devices", obdDevices.size))
        items.addAll(obdDevices.map { Device(it) })
    }
    
    if (pairedOther.isNotEmpty()) {
        items.add(Header("Other Paired Devices", pairedOther.size))
        items.addAll(pairedOther.map { Device(it) })
    }
    
    if (unpairedOther.isNotEmpty()) {
        items.add(Header("Other Unpaired Devices", unpairedOther.size))
        items.addAll(unpairedOther.map { Device(it) })
    }
    
    return items
}
```

### 5. Layout Updates

**Simplify fragment_connect.xml:**
- Remove `label_paired` TextView (headers now in adapter)
- Keep only `recyclerviewDevices` 
- Remove `label_discovered` TextView
- Remove `recyclerviewDiscovered` RecyclerView
- Keep scan button, status text, connection log panel, disconnect button

**Create new header layout - `item_device_header.xml`:**
```xml
<TextView
    android:id="@+id/text_section_header"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:paddingTop="12dp"
    android:paddingBottom="4dp"
    android:textColor="#AAAACC"
    android:textSize="13sp"
    android:textStyle="bold" />
```

### 6. Scan Behavior

**Manual scan only:**
- Remove any auto-scan logic from `onResume()` or `loadPairedDevices()`
- Scan button triggers `startScan()` which:
  1. Clears previous discovered devices
  2. Starts Bluetooth discovery
  3. Rebuilds all three sections when `ACTION_DISCOVERY_FINISHED`
- Show paired devices immediately (no scan needed)
- Unpaired sections remain empty until first scan

## Implementation Steps

1. **Create new files:**
   - `DeviceInfo.kt` - Data class for device metadata
   - `DeviceListItem.kt` - Sealed class for sectioned list items
   - `SectionedDeviceAdapter.kt` - New adapter with header support
   - `item_device_header.xml` - Layout for section headers

2. **Update ConnectViewModel.kt:**
   - Add `DeviceInfo` data class
   - Replace three LiveData with single `_allDevices: MutableLiveData<List<DeviceListItem>>`
   - Update discovery receiver to capture RSSI
   - Add `rebuildSections()` method called on scan finish
   - Remove exclusion of paired devices from discovered list (line 103)

3. **Update ConnectFragment.kt:**
   - Replace `DeviceAdapter` with `SectionedDeviceAdapter`
   - Remove observers for `obdDevices`, `otherDevices`, `discoveredDevices`
   - Add single observer for `allDevices`
   - Remove second RecyclerView setup
   - Keep manual scan button logic

4. **Update fragment_connect.xml:**
   - Remove `label_paired` TextView
   - Remove `label_discovered` TextView  
   - Remove `recyclerviewDiscovered` RecyclerView
   - Keep `recyclerviewDevices` with full height

5. **Update DeviceAdapter.kt (or deprecate):**
   - Keep for reference or remove if fully replaced
   - "PAIRED" badge logic moves to SectionedDeviceAdapter

6. **Testing:**
   - Verify paired devices show immediately
   - Tap scan → verify RSSI captured
   - Verify three sections appear with correct devices
   - Verify RSSI sorting within each section
   - Test connection to paired and unpaired devices

## Technical Details

### RSSI Sorting
- Higher RSSI = stronger signal (less negative)
- Sort descending: `sortedByDescending { it.rssi }`
- Paired devices without RSSI: use placeholder value (0 or Int.MAX_VALUE)

### Section Visibility
- OBD section: Always visible if any OBD devices exist
- Paired other: Visible only if non-OBD paired devices exist
- Unpaired other: Visible only after scan if non-OBD unpaired devices found

### Connection Flow
- No changes to connection logic
- Both paired and unpaired devices use same `BluetoothDevice` object
- Android handles pairing prompt automatically if needed
