# ELM327 ↔ Vehicle ECU Architecture

## The Three Layers

The ELM327 is a **protocol translator** sitting between your phone (Bluetooth) and the vehicle's OBD-II bus. There are three distinct communication layers:

1. **Bluetooth Serial (Phone ↔ ELM327)** — plain ASCII text over a virtual serial port (SPP or BLE)
2. **AT Command Layer (Phone ↔ ELM327 firmware)** — configuration commands prefixed with `AT`
3. **OBD-II Protocol (ELM327 ↔ ECU)** — the ELM327 translates your ASCII hex commands into the correct electrical signaling on the vehicle bus

---

## Component Architecture

```mermaid
graph LR
    subgraph Phone["Phone (Android App)"]
        App[OBD2 App]
        BT[Bluetooth Stack<br/>SPP / BLE]
    end

    subgraph Adapter["ELM327 Adapter"]
        UART[Bluetooth-to-UART<br/>Bridge Chip]
        MCU[ELM327 MCU<br/>AT Command Interpreter<br/>+ Protocol Engine]
        XCVR[OBD-II Bus<br/>Transceiver<br/>CAN / ISO / etc.]
    end

    subgraph Vehicle["Vehicle OBD-II Bus"]
        ECU1[Engine ECU<br/>Header 7E0]
        ECU2[Transmission ECU<br/>Header 7E1]
        ECU3[ABS ECU<br/>Header 7E2]
        ECU4[Other ECUs<br/>760, 7E4, etc.]
    end

    App <-->|ASCII text<br/>over Bluetooth| BT
    BT <-->|SPP/BLE| UART
    UART <-->|Serial UART<br/>AT commands + hex| MCU
    MCU <-->|CAN frames /<br/>ISO-TP packets| XCVR
    XCVR <-->|OBD-II Bus| ECU1
    XCVR <-->|OBD-II Bus| ECU2
    XCVR <-->|OBD-II Bus| ECU3
    XCVR <-->|OBD-II Bus| ECU4
```

### Key points:
- **UART bridge chip** (e.g. HC-05, CC2541) handles Bluetooth ↔ serial conversion transparently
- **ELM327 MCU** parses AT commands, manages protocol timing, constructs/decodes bus frames
- **Transceiver** handles the physical-layer voltage levels for CAN, ISO 9141, KWP2000, etc.

---

## Protocol Stack

```mermaid
graph TB
    subgraph App Layer
        A1["ASCII command string<br/>'010C' (request RPM)"]
    end
    subgraph ELM327 Firmware
        B1["Parse hex → OBD request<br/>Service 01, PID 0C"]
        B2["Wrap in ISO-TP frame<br/>(if multi-byte)"]
        B3["Build CAN frame<br/>ID=7DF, Data=[02 01 0C 00 00 00 00 00]"]
    end
    subgraph CAN Bus
        C1["CAN 2.0B frame on pins 6+14<br/>500 kbps typical"]
    end
    subgraph ECU Response
        D1["ECU responds on CAN ID 7E8<br/>Data=[04 41 0C 0B E0 00 00 00]"]
    end
    subgraph ELM327 Decode
        E1["Strip CAN header + PCI byte<br/>→ raw: 41 0C 0B E0"]
        E2["Format as ASCII hex<br/>'410C0BE0' (with ATS0)"]
    end
    subgraph App Parse
        F1["Find '410C', extract 0BE0<br/>RPM = (0x0B×256 + 0xE0) / 4<br/>= 760 RPM"]
    end

    A1 --> B1 --> B2 --> B3 --> C1 --> D1 --> E1 --> E2 --> F1
```

---

## Connection & Initialization Sequence

This is what happens when your app connects and starts polling:

```mermaid
sequenceDiagram
    participant App as Android App
    participant BT as Bluetooth (SPP/BLE)
    participant ELM as ELM327 Firmware
    participant Bus as OBD-II Bus
    participant ECU as Engine ECU (7E0)

    Note over App,ECU: Phase 1 — Bluetooth Connection
    App->>BT: Connect to MAC address
    BT-->>App: Socket connected

    Note over App,ECU: Phase 2 — ELM327 Initialization (AT Commands)
    App->>ELM: ATZ (reset)
    ELM-->>App: ELM327 v1.5
    App->>ELM: ATE0 (echo off)
    ELM-->>App: OK
    App->>ELM: ATL0 (linefeeds off)
    ELM-->>App: OK
    App->>ELM: ATS0 (spaces off)
    ELM-->>App: OK
    App->>ELM: ATH0 (headers off)
    ELM-->>App: OK
    App->>ELM: ATAT1 (adaptive timing)
    ELM-->>App: OK
    App->>ELM: ATSP0 (auto-detect protocol)
    ELM-->>App: OK

    Note over App,ECU: Phase 3 — Protocol Detection (first real command)
    App->>ELM: 0100 (supported PIDs query)
    ELM->>Bus: CAN frame ID=7DF [02 01 00 ...]
    Note over ELM,Bus: ELM327 tries protocols until<br/>one gets a valid response
    Bus->>ECU: CAN frame received
    ECU->>Bus: CAN frame ID=7E8 [06 41 00 BE 3E B8 13 ...]
    Bus->>ELM: Response received
    ELM-->>App: 4100BE3EB813

    Note over App,ECU: Phase 4 — Continuous Polling Loop
    loop Every ~200ms
        App->>ELM: 010C (RPM)
        ELM->>Bus: CAN frame ID=7DF [02 01 0C ...]
        ECU->>Bus: CAN ID=7E8 [04 41 0C 0B E0 ...]
        ELM-->>App: 410C0BE0
        App->>App: Parse: RPM = 760

        App->>ELM: 010D (Speed)
        ELM->>Bus: CAN frame ID=7DF [02 01 0D ...]
        ECU->>Bus: CAN ID=7E8 [03 41 0D 3C ...]
        ELM-->>App: 410D3C
        App->>App: Parse: Speed = 60 km/h
    end
```

---

## PID Discovery Sequence (Modes 21/22/23)

This is the flow the PID discovery feature uses with custom ECU headers:

```mermaid
sequenceDiagram
    participant App as PidDiscoveryService
    participant ELM as ELM327
    participant Bus as OBD-II Bus
    participant ECU as Target ECU

    Note over App,ECU: Pre-scan Initialization
    App->>ELM: ATE0 (echo off — prevents command echo in response)
    ELM-->>App: OK
    App->>ELM: ATL0 (linefeeds off)
    ELM-->>App: OK
    App->>ELM: ATS0 (spaces off — clean hex)
    ELM-->>App: OK

    Note over App,ECU: Switch to specific ECU header
    App->>ELM: AT SH 7E4 (target Hybrid ECU)
    ELM-->>App: OK
    Note over ELM: Now all requests go to<br/>CAN ID 0x7E4 instead of<br/>broadcast 0x7DF

    Note over App,ECU: Brute-force scan Mode 22
    App->>ELM: 2200
    ELM->>Bus: CAN ID=7E4 [03 22 00 ...]
    ECU->>Bus: CAN ID=7EC [04 62 00 xx ...]
    ELM-->>App: 6200xx → VALID PID

    App->>ELM: 2201
    ELM->>Bus: CAN ID=7E4 [03 22 01 ...]
    Note over ECU: ECU does not support this PID
    ECU--xBus: (no response / negative response)
    ELM-->>App: NODATA

    App->>ELM: 2202
    ELM->>Bus: CAN ID=7E4 [03 22 02 ...]
    ECU->>Bus: CAN ID=7EC [05 62 02 yy zz ...]
    ELM-->>App: 6202yyzz → VALID PID (2 bytes)

    Note over App: Continue 2203..22FF, then modes 21, 23
```

---

## Reading Diagnostic Trouble Codes (DTCs)

OBD-II Service **03** retrieves stored DTCs, and Service **07** retrieves pending (not yet confirmed) DTCs. The ELM327 handles multi-frame responses automatically via ISO-TP.

### DTC Encoding

Each DTC is encoded as **2 bytes** (16 bits):

| Bits | Meaning | Values |
|------|---------|--------|
| 15–14 | System | `00`=Powertrain (P), `01`=Chassis (C), `10`=Body (B), `11`=Network (U) |
| 13–12 | Sub-type | `0`=SAE standard, `1`=Manufacturer specific |
| 11–8 | Category digit | `0`–`F` |
| 7–4 | Fault digit 2 | `0`–`F` |
| 3–0 | Fault digit 3 | `0`–`F` |

**Example:** bytes `01 33` → bits `0000 0001 0011 0011` → **P0133** (O2 Sensor Circuit Slow Response Bank 1 Sensor 1)

### DTC Read Sequence

```mermaid
sequenceDiagram
    participant App as Android App
    participant ELM as ELM327
    participant Bus as OBD-II Bus
    participant ECU as Engine ECU

    Note over App,ECU: Service 03 — Read Stored DTCs
    App->>ELM: 03
    ELM->>Bus: CAN ID=7DF [01 03 00 00 00 00 00 00]
    ECU->>Bus: CAN ID=7E8 [06 43 02 01 33 01 01 00]
    ELM-->>App: 4302013301010

    Note over App: Parse response
    App->>App: 43 = positive response (03 + 0x40)
    App->>App: 02 = number of DTCs stored
    App->>App: Bytes 01 33 → P0133
    App->>App: Bytes 01 01 → P0101

    Note over App,ECU: Service 07 — Read Pending DTCs
    App->>ELM: 07
    ELM->>Bus: CAN ID=7DF [01 07 00 00 00 00 00 00]
    ECU->>Bus: CAN ID=7E8 [04 47 01 04 20 00 00 00]
    ELM-->>App: 47010420

    App->>App: 47 = positive response (07 + 0x40)
    App->>App: 01 = one pending DTC
    App->>App: Bytes 04 20 → P0420

    Note over App,ECU: Service 0A — Read Permanent DTCs (cannot be cleared)
    App->>ELM: 0A
    ELM->>Bus: CAN ID=7DF [01 0A 00 00 00 00 00 00]
    ECU->>Bus: Response with permanent DTCs (if any)
    ELM-->>App: 4A...
```

### Multi-Frame DTC Response

When the ECU has many DTCs, the response exceeds 7 bytes and uses ISO-TP multi-frame:

```mermaid
sequenceDiagram
    participant App as Android App
    participant ELM as ELM327
    participant ECU as Engine ECU

    App->>ELM: 03
    ELM->>ECU: CAN request

    Note over ECU: 8 DTCs = 16 bytes + header,<br/>too large for single frame

    ECU->>ELM: First Frame [10 12 43 08 01 33 01 01]
    Note over ELM: ELM auto-sends Flow Control
    ELM->>ECU: Flow Control [30 00 00 00 00 00 00 00]
    ECU->>ELM: Consecutive Frame 1 [21 04 20 C0 03 42 10 ...]
    ECU->>ELM: Consecutive Frame 2 [22 ...]

    Note over ELM: ELM reassembles all frames<br/>and returns complete response
    ELM-->>App: 430801330101042000034210...
    App->>App: Parse all 8 DTCs from hex
```

---

## Clearing DTCs and Resetting MIL (Service 04)

Service **04** clears all stored DTCs and turns off the Malfunction Indicator Light (Check Engine Light). This is the primary **write operation** in standard OBD-II.

> **Warning:** Clearing DTCs also resets readiness monitors, emissions test data, and freeze frame data. The vehicle may fail an emissions test until all monitors complete their drive cycles again.

```mermaid
sequenceDiagram
    participant App as Android App
    participant ELM as ELM327
    participant Bus as OBD-II Bus
    participant ECU as Engine ECU

    Note over App,ECU: Service 04 — Clear DTCs + Reset MIL
    App->>ELM: 04
    ELM->>Bus: CAN ID=7DF [01 04 00 00 00 00 00 00]

    alt Success
        ECU->>Bus: CAN ID=7E8 [01 44 00 00 00 00 00 00]
        ELM-->>App: 44
        App->>App: 44 = positive response (04 + 0x40)
        App->>App: DTCs cleared, MIL off
    else ECU Refuses (conditions not met)
        ECU->>Bus: CAN ID=7E8 [03 7F 04 22 00 00 00 00]
        ELM-->>App: 7F0422
        App->>App: 7F = negative response
        App->>App: 04 = service that failed
        App->>App: 22 = conditionsNotCorrect
    end
```

### What Service 04 Resets

| Item | Effect |
|------|--------|
| **Stored DTCs** | All cleared |
| **Pending DTCs** | All cleared |
| **Freeze Frame data** | Deleted |
| **MIL (Check Engine Light)** | Turned off |
| **Readiness monitors** | Reset to "not complete" |
| **O2 sensor test data** | Cleared |
| **On-board test results** | Cleared |
| **Distance since DTCs cleared** | Reset to 0 |

---

## All OBD-II Services (Modes) Reference

The ELM327 can send any of the 10 standard OBD-II service modes. Here's the complete list, categorized by read vs. write:

### Read-Only Services

| Service | Name | Description |
|---------|------|-------------|
| **01** | Current Data | Live sensor values (RPM, speed, temps, etc.) |
| **02** | Freeze Frame | Snapshot of sensor data when a DTC was set |
| **03** | Stored DTCs | Confirmed diagnostic trouble codes |
| **05** | O2 Sensor Monitoring | Oxygen sensor test results (non-CAN only) |
| **06** | On-Board Monitoring | Test results for continuously/non-continuously monitored systems |
| **07** | Pending DTCs | DTCs detected during current/last drive cycle (not yet confirmed) |
| **09** | Vehicle Info | VIN, calibration IDs, ECU name, etc. |
| **0A** | Permanent DTCs | DTCs that cannot be cleared by Service 04 |

### Write / Control Services

| Service | Name | Description | Risk Level |
|---------|------|-------------|------------|
| **04** | Clear DTCs | Clears all stored/pending DTCs, resets MIL and monitors | **Medium** — resets emissions readiness |
| **08** | Control On-Board Systems | Bi-directional control of vehicle components (e.g., EVAP leak test) | **High** — actuates physical components |

### Enhanced/Manufacturer Services (Used in PID Discovery)

| Service | Name | Description | Risk Level |
|---------|------|-------------|------------|
| **21** | Manufacturer-specific | Read extended data (common on Asian/European vehicles) | **Low** — read only |
| **22** | Extended Data by PID | Read manufacturer-specific PIDs (most common extended mode) | **Low** — read only |
| **23** | Read Memory by Address | Read ECU memory at specific addresses | **Low** — read only |
| **2E** | Write Data by ID | Write configuration values to ECU | **Critical** — modifies ECU config |
| **2F** | IO Control by ID | Actuator control (turn on fan, move throttle, etc.) | **Critical** — controls hardware |
| **31** | Routine Control | Start/stop/request results of ECU routines | **Critical** — executes routines |

---

## Service 08 — On-Board System Control

Service 08 is the only **standard** OBD-II write service beyond clearing DTCs. It allows bidirectional control for specific tests:

```mermaid
sequenceDiagram
    participant App as Android App
    participant ELM as ELM327
    participant ECU as Engine ECU

    Note over App,ECU: Example: Request EVAP system leak test
    App->>ELM: 08 01 00
    Note over ELM: Service 08, Test ID 01,<br/>disable=00 / enable=FF
    ELM->>ECU: CAN request
    ECU->>ELM: 48 01 00 (positive response)
    ELM-->>App: 480100
    App->>App: EVAP test initiated

    Note over App,ECU: Most ECUs only support<br/>a limited set of Test IDs.<br/>Many vehicles return NO DATA<br/>for unsupported tests.
```

> **Note:** Service 08 support is rare and limited. Most scan tools don't expose it. The tests are defined per-manufacturer and can actuate physical components (solenoids, pumps), so this should be used with caution.

---

## UDS Write Operations (Enhanced — Not Standard OBD-II)

Beyond standard OBD-II, some ECUs support **Unified Diagnostic Services (UDS)** via the same CAN bus. The ELM327 can send these if you set the correct header, but they often require a **security access handshake** first:

```mermaid
sequenceDiagram
    participant App as Android App
    participant ELM as ELM327
    participant ECU as Target ECU

    Note over App,ECU: UDS Security Access (Service 27)
    App->>ELM: AT SH 7E0 (target engine ECU)
    ELM-->>App: OK
    App->>ELM: 2701 (request seed)
    ELM->>ECU: Request security seed
    ECU->>ELM: 6701 [seed bytes]
    ELM-->>App: 6701AABBCCDD

    App->>App: Compute key from seed<br/>(algorithm is manufacturer-specific)

    App->>ELM: 2702 [computed key]
    ELM->>ECU: Send security key
    ECU->>ELM: 6702 (access granted)
    ELM-->>App: 6702

    Note over App,ECU: Now write operations are unlocked
    App->>ELM: 2E F190 [new VIN bytes] (Write VIN — Service 2E)
    ECU->>ELM: 6E F190 (write confirmed)
    ELM-->>App: 6EF190

    Note over App,ECU: Or actuator control
    App->>ELM: 2F 0301 03 [param] (IO Control — activate component)
    ECU->>ELM: 6F 0301 03 (confirmed)
    ELM-->>App: 6F030103
```

### Why the App Only Uses Read Services

The OBD2 app's PID discovery intentionally scans only services **21, 22, 23** (read-only) and skips dangerous PID ranges because:

1. **Safety** — Write services (2E, 2F, 31) can permanently alter ECU configuration, damage components, or void warranties
2. **Security** — Most write operations require Service 27 security access, which needs manufacturer-specific seed/key algorithms
3. **Liability** — Actuator commands (2F) physically move components; incorrect use could cause engine damage, brake issues, etc.

---

## Negative Response Codes

When the ECU refuses a command, it returns a **negative response** (`7F`) with a reason code:

| Code | Name | Common Cause |
|------|------|-------------|
| `12` | Sub-function not supported | Mode not implemented on this ECU |
| `13` | Incorrect message length | Wrong number of data bytes sent |
| `14` | Response too long | Response exceeds transport capacity |
| `22` | Conditions not correct | Engine must be running, or vehicle must be stopped |
| `31` | Request out of range | PID not supported |
| `33` | Security access denied | Need Service 27 unlock first |
| `35` | Invalid key | Wrong security key supplied |
| `36` | Exceeded attempts | Too many wrong keys — ECU locked for a time period |
| `72` | General programming failure | Write/flash operation failed |
| `78` | Response pending | ECU is busy, will send real response later |

---

## How the CAN Frame Actually Looks

On the wire, a single OBD-II request/response looks like this:

| Layer | Request (App → ECU) | Response (ECU → App) |
|-------|-------------------|---------------------|
| **App sends** | `010C\r` | — |
| **ELM327 builds CAN frame** | ID=`7DF`, DLC=8, Data=`02 01 0C 00 00 00 00 00` | — |
| **ECU responds on CAN** | — | ID=`7E8`, DLC=8, Data=`04 41 0C 0B E0 00 00 00` |
| **ELM327 strips & formats** | — | `410C0BE0\r>` |
| **App parses** | — | Header=`410C`, bytes=`[0x0B, 0xE0]`, RPM=760 |

- **`02`** = PCI byte (2 data bytes follow in the ISO-TP frame)
- **`01 0C`** = OBD Service 01, PID 0C
- **`04`** = PCI byte (4 data bytes in response)
- **`41`** = Service 01 + 0x40 (positive response)
- **`0C`** = echo of the PID
- **`0B E0`** = actual data bytes

---

## Why ATE0/ATL0/ATS0 Matter for Discovery

The garbage responses like `DDTNODTA`, `ODTNOATA` are caused by echo + linefeeds being ON:

```
With echo ON (ATE1):     "2379\r" + "NO DATA\r>"  →  buffer reads "2379NODATA" → garbage hex
With echo OFF (ATE0):    "NO DATA\r>"              →  buffer reads "NODATA"     → clean parse
```

Similarly, `ATS0` ensures there are no spaces inside hex data, so `62 04 56` becomes `620456` — making substring parsing reliable without needing to strip whitespace mid-stream.

---

## Summary

- **ELM327 = ASCII-to-CAN translator.** Your app never touches the bus directly.
- **AT commands** configure the translator (echo, timing, protocol, target header).
- **Hex PID commands** (like `010C`, `2200`) are translated into CAN frames, sent on the bus, and the response is returned as ASCII hex over Bluetooth.
- **Headers** (`AT SH xxx`) control which ECU you're talking to — `7DF` is broadcast, `7E0` targets engine specifically.
- **Response byte** = request service byte + `0x40` (e.g., service `22` → response starts with `62`).
