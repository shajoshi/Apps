# Vehicle Profile System for Calibration Settings

Implement a vehicle profile system that allows users to save, load, and manage named sets of calibration settings as JSON files in the track output folder, with Save/Save As/Load buttons directly in the Calibration dialog.

## Overview

The vehicle profile feature will enable users to:
- Save current calibration as a profile (Save/Save As)
- Load existing profiles from the track folder
- View active profile name in Settings and Calibration dialog
- Use "Default" profile when no profile is selected (uses hardcoded default values)
- Store profiles as JSON files alongside track files

## Architecture Changes

### 1. New Data Classes and Repository

**Create `VehicleProfile.kt`**
- `VehicleProfile` data class containing:
  - `name: String` - profile name (e.g., "Motorcycle", "Car", "Bicycle")
  - `calibration: CalibrationSettings` - the calibration parameters
  - `createdAt: Long` - timestamp
  - `lastModified: Long` - timestamp
- JSON serialization/deserialization using Gson library
- Helper methods: `toJson()`, `fromJson()`

**Create `VehicleProfileRepository.kt`**
- `listProfiles(folderUri: String?): List<VehicleProfile>` - scan folder for `.profile.json` files
- `saveProfile(profile: VehicleProfile, folderUri: String?)` - write profile to JSON file
- `loadProfile(filename: String, folderUri: String?): VehicleProfile?` - read profile from JSON
- File naming convention: `{profileName}.profile.json` (e.g., `Motorcycle.profile.json`)
- Handle both custom folder URI and Downloads folder

### 2. Settings Repository Updates

**Modify `SettingsRepository.kt`**
- Add `currentProfileName: String?` field to `TrackingSettings` (null = "Default")
- Add preference key `currentProfileNameKey` for persistence
- Add `updateCurrentProfileName(name: String?)` method
- Keep existing `calibration` field for active calibration settings

### 3. UI Changes in Calibration Dialog

**Modify `CalibrationDialog` in `SettingsScreen.kt`**
- Add profile name display at top: "Active Profile: {name}" or "Active Profile: Default"
- Replace single "Save" button with three buttons:
  - **Save**: Save to current profile (disabled if profile is "Default")
  - **Save As**: Show dialog to enter new profile name, then save
  - **Load**: Show dialog with list of available profiles to select
- Add "Reset to Defaults" button (existing functionality)
- Add "Cancel" button to close without saving

**Save As Dialog**
- Text field for profile name input
- Validate name (non-empty, no special characters)
- Save button creates new profile file
- Show toast: "Profile '{name}' saved"

**Load Dialog**
- List all `.profile.json` files from track folder
- Show profile names in a list
- Select profile → load calibration values → update UI
- Show toast: "Profile '{name}' loaded"
- Close dialog after loading

### 4. Settings Screen Updates

**Modify main Settings UI**
- Display active profile name next to "Calibration" button
- Format: "Calibration (Profile: {name})" or "Calibration (Profile: Default)"

### 5. File Storage Integration

**Use existing `TrackingFileStore.kt` pattern**
- Profile files saved in same folder as tracks
- If custom folder selected: save there
- If no folder selected: save to Downloads
- File naming: `{ProfileName}.profile.json`

### 6. JSON Format

**Profile JSON Structure**
```json
{
  "name": "Motorcycle",
  "calibration": {
    "rmsSmoothMax": 1.0,
    "rmsAverageMax": 2.0,
    "peakThresholdZ": 1.5,
    "symmetricBumpThreshold": 2.0,
    "potholeDipThreshold": -2.5,
    "bumpSpikeThreshold": 2.5,
    "peakCountSmoothMax": 5,
    "peakCountAverageMax": 15,
    "movingAverageWindow": 5
  },
  "createdAt": 1738410000000,
  "lastModified": 1738410000000
}
```

### 7. Default Sample Profiles

**Create 2-3 preset profiles on first app launch:**
1. **Motorcycle.profile.json** (current default values)
   - rmsSmoothMax: 1.0, rmsAverageMax: 2.0, peakThresholdZ: 1.5
2. **Car.profile.json** (less sensitive)
   - rmsSmoothMax: 1.5, rmsAverageMax: 3.0, peakThresholdZ: 2.0
3. **Bicycle.profile.json** (more sensitive)
   - rmsSmoothMax: 0.7, rmsAverageMax: 1.5, peakThresholdZ: 1.0

## Implementation Steps

1. **Add Gson dependency** to `build.gradle.kts`
   - `implementation("com.google.code.gson:gson:2.10.1")`

2. **Create `VehicleProfile.kt`** with data class and JSON serialization

3. **Create `VehicleProfileRepository.kt`** with file I/O operations
   - List, save, load profile methods
   - Handle DocumentFile API for custom folders
   - Handle MediaStore API for Downloads

4. **Update `SettingsRepository.kt`**
   - Add `currentProfileName` field and persistence
   - Add update method for profile name

5. **Modify `CalibrationDialog` in `SettingsScreen.kt`**
   - Add profile name display
   - Replace Save button with Save/Save As/Load buttons
   - Create Save As dialog composable
   - Create Load dialog composable
   - Wire up profile repository calls
   - Add toast notifications

6. **Update Settings screen main UI**
   - Display active profile name next to Calibration button

7. **Create default profiles**
   - Check if profiles exist on app launch
   - Create Motorcycle, Car, Bicycle profiles if not present

8. **Handle edge cases**
   - Profile file not found → show error toast
   - Invalid JSON → show error toast
   - Duplicate profile name → overwrite with confirmation
   - No folder selected → use Downloads
   - "Default" profile → cannot be saved over (Save button disabled)

## User Workflow

1. User opens Settings → sees "Calibration (Profile: Default)" button
2. User clicks Calibration → dialog shows "Active Profile: Default"
3. User adjusts values and clicks "Save As" → enters "Motorcycle" → saves
4. Dialog now shows "Active Profile: Motorcycle"
5. Settings button shows "Calibration (Profile: Motorcycle)"
6. User can click "Load" to switch to different profile
7. Toast shows "Profile 'Motorcycle' loaded" when loading
8. "Save" button updates existing profile (disabled for "Default")

## Benefits

- Simple, intuitive UI with Save/Save As/Load workflow
- Profile name always visible in UI
- Profiles stored alongside tracks for easy organization
- Default profile ensures app works without profile management
- Toast notifications provide clear feedback
- Portable JSON files can be shared between devices
