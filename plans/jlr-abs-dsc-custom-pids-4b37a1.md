# Create JLR ABS/DSC Custom PID JSON Objects

Generate JSON objects for Jaguar Land Rover ABS/DSC module extended PIDs that can be added to a vehicle profile.

## Overview

Create Custom PID JSON configurations for the following JLR-specific UDS PIDs from the ABS/DSC Module (ECU Header 760):

1. **Yaw Rate** - PID 220456
2. **Lateral G** - PID 220455  
3. **Longitudinal G** - PID 220454
4. **Steering Angle** - PID 220510

## JSON Structure

Based on the VehicleProfileRepository serialization code, each Custom PID JSON object requires:

```json
{
  "id": "unique-uuid",
  "name": "Human readable name",
  "header": "760",
  "mode": "22",
  "pid": "0456",
  "bytesReturned": 2,
  "unit": "°/s",
  "formula": "((A*256)+B)/100",
  "signed": false,
  "enabled": true
}
```

## PID Specifications

### 1. Yaw Rate (220456)
- **Description**: Vehicle rotation rate around vertical axis
- **Header**: 760 (ABS/DSC Module)
- **Mode**: 22 (UDS Enhanced Diagnostics)
- **PID**: 0456
- **Bytes**: 2
- **Formula**: `((A*256)+B)/100` (16-bit value scaled by 100)
- **Unit**: °/s (degrees per second)
- **Signed**: false (typically unsigned, but may need verification)
- **Range**: ~0-655 °/s

### 2. Lateral G (220455)
- **Description**: Lateral acceleration (cornering force)
- **Header**: 760
- **Mode**: 22
- **PID**: 0455
- **Bytes**: 2
- **Formula**: `((A*256)+B)/100` or `(((A*256)+B)-32768)/100` if signed
- **Unit**: g (gravitational force)
- **Signed**: Likely true (can be negative for left/right)
- **Range**: ~-2g to +2g typical

### 3. Longitudinal G (220454)
- **Description**: Longitudinal acceleration (braking/acceleration force)
- **Header**: 760
- **Mode**: 22
- **PID**: 0454
- **Bytes**: 2
- **Formula**: `((A*256)+B)/100` or `(((A*256)+B)-32768)/100` if signed
- **Unit**: g
- **Signed**: Likely true (negative for braking, positive for acceleration)
- **Range**: ~-1.5g to +1g typical

### 4. Steering Angle (220510)
- **Description**: Steering wheel angle from center
- **Header**: 760
- **Mode**: 22
- **PID**: 0510
- **Bytes**: 2
- **Formula**: `(((A*256)+B)-32768)/10` (signed 16-bit, scaled by 10)
- **Unit**: ° (degrees)
- **Signed**: true (negative = left, positive = right)
- **Range**: ~-720° to +720° (2 full rotations each direction)

## Questions to Resolve

### 1. Signed Values
**Question**: Are Lateral G, Longitudinal G, and Steering Angle signed values?

**Current Assumption**: 
- Yaw Rate: unsigned (always positive rotation rate)
- Lateral G: signed (left/right cornering)
- Longitudinal G: signed (braking/acceleration)
- Steering Angle: signed (left/right turn)

**Note**: The current formula parser doesn't have a "signed" mode that automatically handles two's complement. Signed values must use explicit formula: `(((A*256)+B)-32768)/scale`

### 2. Scaling Factors
**Question**: Are the scaling factors (/100, /10) correct?

**Current Assumption**:
- G-forces: /100 (0.01g resolution)
- Steering: /10 (0.1° resolution)
- Yaw: /100 (0.01°/s resolution)

**Verification Needed**: Test with real vehicle to confirm

### 3. UUID Generation
**Question**: Should UUIDs be pre-generated or left for the app to create?

**Options**:
- Pre-generate UUIDs for consistency
- Use placeholder UUIDs that get replaced on import
- Let app generate new UUIDs when PIDs are added

**Recommendation**: Pre-generate UUIDs for the JSON template

## Implementation Options

### Option 1: Individual JSON Objects
Create 4 separate JSON objects that can be manually added to a profile's `customPids` array.

**Pros**: Easy to copy/paste individual PIDs
**Cons**: User must manually edit profile JSON file

### Option 2: Complete Profile JSON
Create a full vehicle profile JSON with all 4 PIDs included.

**Pros**: One-step import, ready to use
**Cons**: User must merge with existing profile or create new one

### Option 3: JSON Array Only
Create just the `customPids` array that can be inserted into existing profile.

**Pros**: Easy to merge into existing profile
**Cons**: Still requires manual JSON editing

## Recommended Approach

**Create both Option 1 and Option 3**:
1. Individual JSON objects for reference/documentation
2. Complete `customPids` array ready to paste into profile

This gives maximum flexibility for users to either:
- Add individual PIDs one at a time
- Add all 4 PIDs at once to existing profile
- Reference the structure for creating their own

## Output Format

### Individual PID Objects
```json
// Yaw Rate
{
  "id": "jlr-yaw-rate-001",
  "name": "Yaw Rate",
  "header": "760",
  "mode": "22",
  "pid": "0456",
  "bytesReturned": 2,
  "unit": "°/s",
  "formula": "((A*256)+B)/100",
  "signed": false,
  "enabled": true
}
```

### Complete Array
```json
"customPids": [
  { /* Yaw Rate */ },
  { /* Lateral G */ },
  { /* Longitudinal G */ },
  { /* Steering Angle */ }
]
```

## Next Steps

1. Generate UUIDs for each PID
2. Create individual JSON objects with best-guess formulas
3. Create combined array for easy import
4. Add notes about verification needed with real vehicle
5. Document how to add these to an existing profile

## Notes

- These PIDs are **Jaguar-specific** and may not work on other vehicles
- Formulas are **estimated** based on typical automotive scaling
- **Real-world testing required** to verify:
  - Correct byte count
  - Correct scaling factors
  - Signed vs unsigned interpretation
  - Response format matches expectations
- User should test with PID Discovery feature first to confirm PIDs are accessible
