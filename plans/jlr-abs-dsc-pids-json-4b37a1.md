# JLR ABS/DSC Custom PID JSON Objects

## Individual PID Objects

### 1. Yaw Rate (220456)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440001",
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

### 2. Lateral G (220455)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440002",
  "name": "Lateral G",
  "header": "760",
  "mode": "22",
  "pid": "0455",
  "bytesReturned": 2,
  "unit": "g",
  "formula": "(((A*256)+B)-32768)/100",
  "signed": true,
  "enabled": true
}
```

### 3. Longitudinal G (220454)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440003",
  "name": "Longitudinal G",
  "header": "760",
  "mode": "22",
  "pid": "0454",
  "bytesReturned": 2,
  "unit": "g",
  "formula": "(((A*256)+B)-32768)/100",
  "signed": true,
  "enabled": true
}
```

### 4. Steering Angle (220510)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440004",
  "name": "Steering Angle",
  "header": "760",
  "mode": "22",
  "pid": "0510",
  "bytesReturned": 2,
  "unit": "°",
  "formula": "(((A*256)+B)-32768)/10",
  "signed": true,
  "enabled": true
}
```

## Complete customPids Array

Copy this entire array to add all 4 PIDs to your vehicle profile at once:

```json
"customPids": [
  {
    "id": "550e8400-e29b-41d4-a716-446655440001",
    "name": "Yaw Rate",
    "header": "760",
    "mode": "22",
    "pid": "0456",
    "bytesReturned": 2,
    "unit": "°/s",
    "formula": "((A*256)+B)/100",
    "signed": false,
    "enabled": true
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440002",
    "name": "Lateral G",
    "header": "760",
    "mode": "22",
    "pid": "0455",
    "bytesReturned": 2,
    "unit": "g",
    "formula": "(((A*256)+B)-32768)/100",
    "signed": true,
    "enabled": true
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440003",
    "name": "Longitudinal G",
    "header": "760",
    "mode": "22",
    "pid": "0454",
    "bytesReturned": 2,
    "unit": "g",
    "formula": "(((A*256)+B)-32768)/100",
    "signed": true,
    "enabled": true
  },
  {
    "id": "550e8400-e29b-41d4-a716-446655440004",
    "name": "Steering Angle",
    "header": "760",
    "mode": "22",
    "pid": "0510",
    "bytesReturned": 2,
    "unit": "°",
    "formula": "(((A*256)+B)-32768)/10",
    "signed": true,
    "enabled": true
  }
]
```

## How to Add to Your Profile

### Option 1: Use Custom PID UI (Recommended)
1. Open your vehicle profile
2. Go to Custom PIDs
3. Click "Add Custom PID"
4. Enter the values from the JSON objects above
5. Save each PID individually

### Option 2: Manual JSON Editing
1. Export your vehicle profile (or find the JSON file)
2. Locate the `customPids` array
3. Replace with the complete array above, or add individual objects
4. Save the JSON file
5. Import or reload the profile

## Important Notes

### Formulas Explained
- **Unsigned (Yaw Rate)**: `((A*256)+B)/100` - Simple 16-bit unsigned integer
- **Signed (G-forces, Steering)**: `(((A*256)+B)-32768)/scale` - Two's complement signed 16-bit

### Scaling Factors
- **Yaw Rate**: /100 (0.01°/s resolution)
- **G-forces**: /100 (0.01g resolution) 
- **Steering**: /10 (0.1° resolution)

### Verification Required
⚠️ **Test with your vehicle to verify**:
1. PIDs return data (no NODATA/ERROR responses)
2. Scaling factors are correct
3. Signed/unsigned interpretation is correct
4. Units are appropriate

### Expected Ranges
- **Yaw Rate**: 0-655 °/s (typical: 0-100 °/s during aggressive driving)
- **Lateral G**: -2.0 to +2.0 g (negative = left, positive = right)
- **Longitudinal G**: -1.5 to +1.0 g (negative = braking, positive = acceleration)
- **Steering Angle**: -720° to +720° (2 rotations each direction)

### Troubleshooting
- If values are wrong, try removing the `-32768` offset for unsigned interpretation
- If scaling is off, adjust the divisor (/100, /10, etc.)
- Use PID Discovery feature to verify PIDs are accessible with header 760
