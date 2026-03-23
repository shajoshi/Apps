# Custom PID Guide - OBD2App

## Overview

Custom PIDs allow you to read **extended OBD-II parameters** that aren't included in the standard Mode 01 PIDs. This includes manufacturer-specific diagnostics, enhanced parameters, and data from non-engine ECUs.

## Table of Contents

1. [What Are Custom PIDs?](#what-are-custom-pids)
2. [Getting Started](#getting-started)
3. [Configuring Custom PIDs](#configuring-custom-pids)
4. [Understanding Formulas](#understanding-formulas)
5. [Real-World Examples](#real-world-examples)
6. [Discovering Custom PIDs with Terminal Apps](#discovering-custom-pids-with-terminal-apps)
7. [Troubleshooting](#troubleshooting)
8. [Advanced Tips](#advanced-tips)

---

## What Are Custom PIDs?

### Standard vs Custom PIDs

| Feature | Standard PIDs | Custom PIDs |
|---------|---------------|-------------|
| **Discovery** | Automatic (bitmask queries) | Manual configuration |
| **Mode** | Mode 01 only | Any mode (21, 22, 23, etc.) |
| **Headers** | Default (7DF) | Any ECU header (7E0, 7E1, 760, etc.) |
| **Availability** | Universal (all vehicles) | Manufacturer/vehicle specific |
| **Examples** | RPM, speed, coolant temp | Yaw rate, hybrid battery SOC, transmission temp |

### Use Cases

- **Manufacturer-specific diagnostics** (Jaguar, BMW, Toyota)
- **Enhanced parameters** (Mode 22 extended data)
- **Non-engine ECUs** (transmission, ABS, instrument cluster)
- **Hybrid/EV data** (battery state, inverter temps)
- **Performance monitoring** (boost pressure, G-forces)

---

## Getting Started

### Step 1: Create a Vehicle Profile

Custom PIDs are stored **per vehicle profile**:

1. Open OBD2App → **Settings** tab
2. Tap **+ Add** to create a new profile or edit existing one
3. Fill in vehicle details:
   - Profile name (e.g., "Jaguar XF 2020")
   - Fuel type, engine size, tank capacity
   - Engine displacement and volumetric efficiency (for fuel calculations)
4. **Save** the profile

### Step 2: Access Custom PID Management

1. In **Settings**, tap to **edit** your vehicle profile
2. Tap **"Manage Custom PIDs"** button (appears for existing profiles only)
3. You'll see the Custom PID list (empty initially)

---

## Configuring Custom PIDs

### Adding a New Custom PID

Tap **+ Add** to create a new custom PID:

| Field | Description | Example | Notes |
|-------|-------------|---------|-------|
| **Name** | Human-readable display name | "Yaw Rate" | Include units if helpful |
| **Header** | ECU header in hex | "760" | Leave blank for default (7DF) |
| **Mode** | OBD mode in hex | "22" | Default is "22" for extended |
| **PID** | Parameter ID in hex | "0456" | Must include leading zeros |
| **Bytes** | Number of data bytes | 2 | Usually 1-8 bytes |
| **Unit** | Display unit (optional) | "°/s" | Appears in UI |
| **Formula** | Arithmetic expression | `((A*256)+B)/100` | See formula section |
| **Signed** | Interpret bytes as signed | unchecked | Important for negative values |

### Managing Existing PIDs

- **Tap any PID** in the list to edit it
- **Use delete button** in edit mode to remove
- PIDs are automatically saved when you tap **Save**

---

## Understanding Formulas

The formula converts raw OBD data bytes to meaningful values.

### Variables

- **A, B, C, D, E, F, G, H** = Data bytes 1 through 8
- Each variable contains the decimal value (0-255) of the corresponding byte

### Operations

- **Basic**: `+`, `-`, `*`, `/`
- **Parentheses**: `()` for grouping
- **Order of operations**: Standard mathematical precedence

### Common Formula Patterns

| Pattern | Description | Example |
|---------|-------------|---------|
| `A-40` | Standard temperature conversion | Coolant temp: `A-40` |
| `((A*256)+B)/100` | 16-bit value divided by 100 | Yaw rate: `((A*256)+B)/100` |
| `A*0.078125-40` | Toyota-specific temp conversion | Sensor temp: `A*0.078125-40` |
| `A*0.5` | Simple scaling | Battery SOC: `A*0.5` |
| `((A*256)+B)-32768` | 16-bit signed with offset | Steering angle: `((A*256)+B)-32768` |

### Formula Examples

```kotlin
// Engine coolant temperature (standard)
"A-40"

// 16-bit RPM calculation  
"((A*256)+B)/4"

// Percentage with decimal precision
"A*0.5"

// Signed angle with center offset
"((A*256)+B)-32768"

// Complex scaling
"((A*256)+B)*0.015625"
```

---

## Real-World Examples

### Example 1: Jaguar XF Yaw Rate

| Field | Value |
|-------|-------|
| **Name** | "Yaw Rate" |
| **Header** | "760" |
| **Mode** | "22" |
| **PID** | "0456" |
| **Bytes** | 2 |
| **Unit** | "°/s" |
| **Formula** | `((A*256)+B)/100` |
| **Signed** | unchecked |

**Terminal Response**: `62 04 56 01 F4`  
**Calculation**: ((1×256)+244)/100 = 5.0 °/s

### Example 2: Toyota Hybrid Battery SOC

| Field | Value |
|-------|-------|
| **Name** | "Hybrid Battery SOC" |
| **Header** | "7E4" |
| **Mode** | "22" |
| **PID** | "01DB" |
| **Bytes** | 1 |
| **Unit** | "%" |
| **Formula** | `A*0.5` |
| **Signed** | unchecked |

**Terminal Response**: `62 01 DB B4`  
**Calculation**: 180 × 0.5 = 90.0%

### Example 3: BMW Transmission Temperature

| Field | Value |
|-------|-------|
| **Name** | "Transmission Temp" |
| **Header** | "7E1" |
| **Mode** | "22" |
| **PID** | "10B8" |
| **Bytes** | 1 |
| **Unit** | "°C" |
| **Formula** | `A-40` |
| **Signed** | unchecked |

**Terminal Response**: `62 10 B8 58`  
**Calculation**: 88 - 40 = 48°C

---

## Discovering Custom PIDs with Terminal Apps

### Recommended Android Terminal Apps

1. **Serial Bluetooth Terminal** (by Kai Morich) - Free, popular
2. **Bluetooth Terminal HC-05** - Simple interface
3. **Serial Monitor** - Good for logging
4. **Termius** - Advanced features

### Step-by-Step Discovery Process

#### 1. Connect to Your OBD-II Adapter

```
1. Pair adapter in Android Bluetooth settings
2. Open terminal app
3. Connect to your OBD-II adapter
```

#### 2. Initialize the Adapter

```bash
ATZ         # Reset adapter (responds with version)
AT E0       # Echo off
AT L0       # Linefeeds off  
AT S0       # Spaces off
AT SP 0     # Set protocol to auto
AT DP       # Show current protocol
```

#### 3. Verify Standard Communication

```bash
010C        # Engine RPM (should respond: 41 0C XX XX)
010D        # Vehicle speed (should respond: 41 0D XX)
```

#### 4. Explore Different ECU Headers

```bash
AT SH 7E0  # Engine ECU (default)
AT SH 7E1  # Transmission ECU
AT SH 7E2  # ABS ECU  
AT SH 760  # Instrument Cluster (Jaguar/LR)
AT SH 7E4  # Hybrid ECU (Toyota/Lexus)
AT SH 7DF  # Default broadcast header
```

#### 5. Try Extended Mode 22 PIDs

```bash
# Systematic approach
2200        # Extended PID 00
2201        # Extended PID 01
2202        # Extended PID 02
# Continue through 22FF
```

#### 6. Try Other Modes

```bash
# Mode 21 - Transmission data
2100        # Transmission PID 00
2101        # Transmission PID 01

# Mode 23 - Manufacturer specific
2300        # Manufacturer PID 00
```

### Understanding Terminal Responses

#### Standard PID Response
```
Command: 010C (Engine RPM)
Response: 41 0C 0A F0
         ^^ ^^ ^^^^^
         || || |||||
         || || ||+++-- Data bytes (0A F0 = 2800 RPM)
         || || |+----- Byte count
         || |+------- PID (0C)
         |+--------- Mode (41 = 01 + 0x40)
         +----------- Response prefix
```

#### Extended PID Response  
```
Command: 220456 (Yaw rate)
Response: 62 04 56 01 F4
         ^^ ^^ ^^ ^^^^^
         || || || |||||
         || || || ||+++-- Data bytes (01 F4)
         || || || |+----- Byte count
         || || |+------- PID low byte (56)
         || |+--------- PID high byte (04)  
         |+----------- Mode (62 = 22 + 0x40)
         +------------- Response prefix
```

### Common Response Meanings

| Response | Meaning |
|----------|---------|
| `41 XX ...` | Valid Mode 01 response |
| `62 XX ...` | Valid Mode 22 response |
| `NO DATA` | PID not supported by this ECU |
| `UNABLE TO CONNECT` | Wrong ECU header |
| `ERROR` or `?` | Communication error |

### Systematic Discovery Strategy

1. **Research First**
   - Vehicle-specific forums
   - Service manuals
   - Torque app PID database

2. **Start with Known Headers**
   ```bash
   # Common headers to try
   AT SH 7E0  # Most vehicles
   AT SH 7E1  # Transmission
   AT SH 760  # Jaguar/Land Rover
   AT SH 7E4  # Toyota/Lexus hybrids
   ```

3. **Document Everything**
   ```
   | Header | Mode | PID | Response | Description | Formula |
   |--------|------|-----|----------|-------------|---------|
   | 760    | 22   | 0456| 62 04 56 01 F4 | Yaw rate | ((A*256)+B)/100 |
   ```

4. **Test While Driving**
   - Some PIDs only work when vehicle is moving
   - Transmission data needs gear engagement
   - ABS data needs wheel movement

---

## Troubleshooting

### Custom PID Shows "NODATA"

| Cause | Solution |
|-------|----------|
| Wrong ECU header | Try different headers (7E0, 7E1, 760, etc.) |
| Incorrect mode/PID | Verify hex values with terminal app |
| Vehicle not ready | Start engine, wait for initialization |
| ECU not present | Vehicle may not have this ECU |

### Values Look Wrong

| Issue | Fix |
|-------|-----|
| Incorrect formula | Test calculation manually |
| Wrong byte count | Adjust bytes returned |
| Signed/unsigned mismatch | Toggle signed checkbox |
| Endianness wrong | Try `(B*256)+A` instead of `(A*256)+B` |

### Terminal App Issues

| Problem | Solution |
|---------|----------|
| Can't connect | Check Bluetooth pairing, try different terminal app |
| No responses | Verify adapter initialization (ATZ, AT E0) |
| Garbled text | Check baud rate, try AT SP 0 for auto protocol |

---

## Advanced Tips

### Performance Optimization

1. **Group PIDs by Header** - Minimizes AT SH switches
2. **Use Slow Tier** - Custom PIDs polled every 5th cycle
3. **Limit Byte Count** - Only request needed bytes
4. **Test Formulas** - Use unit test framework

### Documentation

1. **Save PID Sources** - Note where you found each PID
2. **Vehicle Notes** - Document year/model/ECU variations  
3. **Formula Derivation** - Explain complex calculations
4. **Test Conditions** - Note when PIDs are valid

### Sharing Configurations

1. **Export Profiles** - Share vehicle profiles with custom PIDs
2. **Community Contributions** - Share findings on forums
3. **Version Control** - Track changes to PID configurations
4. **Backup Regularly** - Prevent loss of custom configurations

### Experimental Approaches

```bash
# Brute force script (if terminal supports scripting)
for pid in 2200..22FF; do
    echo "Testing PID $pid"
    send "$pid"
    sleep 0.1
done

# Header switching test
headers=("7E0" "7E1" "7E2" "760" "7E4")
for header in "${headers[@]}"; do
    echo "Testing header $header"
    send "AT SH $header"
    send "2200"
done
```

---

## Resources

### PID Databases
- [Torque App PID Database](https://torque-bhp.com/wiki/Custom_PIDs)
- [OBD-II PIDs Wikipedia](https://en.wikipedia.org/wiki/OBD-II_PIDs)
- Vehicle-specific forums (Jaguar, BMW, Toyota, etc.)

### Documentation
- Vehicle service manuals
- Manufacturer technical bulletins
- SAE J1979 standard (OBD-II specification)

### Tools
- Android terminal apps (Serial Bluetooth Terminal)
- PC tools (ScanTool, OBDwiz)
- Professional diagnostic software (manufacturer specific)

---

## Conclusion

Custom PIDs unlock a wealth of vehicle data beyond standard OBD-II parameters. While they require research and manual configuration, they provide access to manufacturer-specific diagnostics, enhanced parameters, and data from non-engine ECUs.

The combination of OBD2App's custom PID system and terminal-based discovery gives you powerful tools to explore your vehicle's data capabilities. Start with well-documented PIDs for your vehicle, then expand your configuration as you discover new parameters.

Happy hacking! 🚗💨
