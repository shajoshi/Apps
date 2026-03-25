# JLR Extended PIDs & TPMS Integration Analysis

Analyze whether the current Custom PID structure can read JLR-specific UDS extended PIDs and TPMS data from the CAN bus via ELM327 BLE adapter.

## Current Custom PID Capabilities

### ✅ Supported Features
1. **UDS Mode 22 Support**: Custom PIDs already support Mode 22 (Enhanced Diagnostics)
2. **Custom ECU Headers**: Can target specific modules like ABS/DSC (Header 760)
3. **Multi-byte Data**: Supports up to 8 bytes (A-H variables in formulas)
4. **Formula Evaluation**: Full arithmetic parser with +, -, *, /, parentheses
5. **Header Switching**: Automatically switches headers via AT SH commands
6. **Response Parsing**: Correctly parses UDS responses (mode+0x40) + PID + data

### 📋 JLR PIDs to Integrate

#### ABS/DSC Module (Header 760)
- **Yaw Rate**: Mode 22, PID 0456 → Command: `220456`
- **Lateral G**: Mode 22, PID 0455 → Command: `220455`
- **Longitudinal G**: Mode 22, PID 0454 → Command: `220454`
- **Steering Angle**: Mode 22, PID 0510 → Command: `220510`

#### TPMS Data (likely BCM or TPMS module)
- **Tire Pressures**: PIDs 2076, 2077, 2078, 2079 → Commands: `222076`, `222077`, `222078`, `222079`
- **Tire Temperatures**: PIDs 2A0A, 2A0B, 2A0C, 2A0D → Commands: `222A0A`, `222A0B`, `222A0C`, `222A0D`

## Compatibility Analysis

### ✅ Fully Compatible
All JLR PIDs can be configured using the existing Custom PID structure:

1. **Header**: `760` for ABS/DSC, likely empty or specific header for TPMS
2. **Mode**: `22` (UDS Enhanced Diagnostics)
3. **PID**: Hex values like `0456`, `2076`, `2A0A`
4. **Bytes Returned**: Typically 2 bytes for these metrics
5. **Formula**: Standard conversions (e.g., `((A*256)+B)/100` for scaled values)
6. **Unit**: °/s, g, °, PSI, °C/°F

### Example Configurations

#### Yaw Rate (Already in code comments!)
```kotlin
CustomPid(
    name = "Yaw Rate",
    header = "760",
    mode = "22",
    pid = "0456",
    bytesReturned = 2,
    formula = "((A*256)+B)/100",
    unit = "°/s"
)
```

#### Tire Pressure (Front Left)
```kotlin
CustomPid(
    name = "Tire Pressure FL",
    header = "",  // TBD - need to discover correct header
    mode = "22",
    pid = "2076",
    bytesReturned = 2,
    formula = "((A*256)+B)/100",  // May need adjustment based on actual response
    unit = "PSI"
)
```

## Questions to Resolve

### 1. TPMS Header Discovery
**Question**: What ECU header should be used for TPMS PIDs (2076-2079, 2A0A-2A0D)?

**Options**:
- Empty string (default 7DF broadcast)
- Specific BCM header (e.g., 726, 765)
- TPMS module header (if separate)

**Action**: Use PID Discovery feature to scan for these PIDs across common headers

### 2. Data Format Verification
**Question**: What is the actual data format and scaling for TPMS values?

**Considerations**:
- Pressure: PSI, kPa, or bar? Scaling factor?
- Temperature: Celsius or Fahrenheit? Offset?
- Signed vs unsigned values?

**Action**: Test with real vehicle to capture raw responses and determine correct formulas

### 3. Response Byte Count
**Question**: How many bytes do TPMS PIDs return?

**Typical Options**:
- 1 byte: Simple 0-255 range
- 2 bytes: 16-bit values (most common)
- 3+ bytes: Extended data or multiple values

**Action**: Verify via PID discovery or manual testing

## Implementation Plan

### Phase 1: ABS/DSC PIDs (Low Risk)
These are well-documented and already referenced in code comments.

1. **Create Custom PID Profiles** for Jaguar XF with:
   - Yaw Rate (220456)
   - Lateral G (220455)
   - Longitudinal G (220454)
   - Steering Angle (220510)

2. **Test with Vehicle**: Verify responses and formulas

3. **Document Formulas**: Record correct scaling factors

### Phase 2: TPMS Discovery (Medium Risk)
TPMS PIDs are less documented and may require discovery.

1. **Use PID Discovery Feature**:
   - Scan Mode 22 PIDs in range 2000-2FFF
   - Try common BCM/TPMS headers (726, 760, 765, 7E0)
   - Look for PIDs 2076-2079, 2A0A-2A0D

2. **Analyze Responses**:
   - Determine byte count
   - Identify scaling factors
   - Test with known tire pressures/temps

3. **Create Custom PIDs** with verified formulas

### Phase 3: Profile Integration
1. **Create "JLR Extended Metrics" Profile** with all working PIDs
2. **Add to Dashboard** for real-time monitoring
3. **Enable Logging** for track recording

## Technical Verification Checklist

- [ ] Confirm Mode 22 works with Header 760 on Jaguar XF
- [ ] Verify response format matches expected UDS structure
- [ ] Test formula evaluation with real data
- [ ] Confirm header switching doesn't interfere with standard PIDs
- [ ] Verify polling frequency is acceptable (currently slow-tier = every 5 cycles)
- [ ] Test with multiple custom PIDs active simultaneously
- [ ] Verify TPMS PIDs are accessible via OBD (not all vehicles expose TPMS on CAN)

## Potential Issues & Mitigations

### Issue 1: TPMS Not on OBD Bus
**Risk**: Some vehicles don't expose TPMS data via OBD-II connector

**Mitigation**: 
- Test with PID discovery first
- May need direct CAN bus access (not via ELM327)
- Consider alternative: OBD-II TPMS sensors if factory data unavailable

### Issue 2: Header Conflicts
**Risk**: Switching headers may cause standard PID polling to fail

**Mitigation**:
- Current code already handles header restoration (AT SH7DF)
- Monitor for increased error rates
- May need to adjust polling frequency

### Issue 3: Formula Complexity
**Risk**: TPMS formulas may be more complex than current parser supports

**Mitigation**:
- Current parser supports A-H (8 bytes), +, -, *, /, parentheses
- Should be sufficient for most automotive formulas
- Can extend parser if needed (e.g., add bitwise operations)

## Recommendation

**✅ YES - Current Custom PID structure CAN read these PIDs**

The existing implementation already supports:
- UDS Mode 22 ✅
- Custom headers (760) ✅
- Multi-byte responses ✅
- Formula evaluation ✅
- Header switching ✅

**Next Steps**:
1. Create Custom PID configurations for ABS/DSC PIDs (known working)
2. Use PID Discovery to locate TPMS PIDs and determine correct header
3. Test with vehicle to verify formulas and data formats
4. Document findings and create reusable profile

**No code changes required** - this is purely configuration/discovery work.
