# ELM327 Fast Polling — Research & Implementation Plan

How apps like Torque achieve faster OBD polling rates and what can be implemented in this app's `BluetoothObd2Service`.

---

## Why Standard Polling Is Slow

The current implementation sends one PID at a time in a sequential loop:

```
→ "010C\r"   wait for ">"   parse   delay(cmdDelay)
→ "010D\r"   wait for ">"   parse   delay(cmdDelay)
→ "015E\r"   wait for ">"   parse   delay(cmdDelay)
... (N PIDs)
→ delay(pollDelay)
→ repeat
```

With a typical ELM327 BT adapter, each round-trip is **50–150ms** (BT latency ~20–40ms + ECU response time ~20–80ms + parsing). With 15 active PIDs this gives a full-cycle time of ~1–2 seconds. The current code also adds explicit `cmdDelay` between each PID (configurable, default likely 50–100ms), making it even slower.

---

## How Torque and Similar Apps Go Faster

### Technique 1: **Adaptive Header / Protocol Lock** (most impactful)
- After auto-detecting protocol with `ATSP0`, Torque **locks the protocol** with e.g. `ATSP6` (ISO 15765-4 CAN 11-bit 500kbps) and sets `ATSH 7DF` (the OBD broadcast address).
- This eliminates the protocol negotiation overhead on every request.
- Commands: `ATSP6` (or whichever protocol was detected), `ATSH 7DF`, `ATFCSH 7E0`, `ATFCSD 300000`, `ATFCSM 1`

### Technique 2: **`ATH1` + `ATCAF0` Response Filtering Off**
- Torque uses `ATH1` (headers ON) + `ATCAF0` (CAN auto-formatting OFF) with raw hex parsing.
- This skips the ELM327's internal byte reformatting, reducing per-response processing time by ~10–30ms.

### Technique 3: **`ATAT1` / `ATAT2` Adaptive Timing** ← **biggest single gain**
- ELM327 has a built-in adaptive timing mode:
  - `ATAT0` — fixed timeout (default)
  - `ATAT1` — adaptive (ELM adjusts timeout down to minimum needed)
  - `ATAT2` — aggressive adaptive (even shorter timeouts, may miss slow ECUs)
- With `ATAT1`, the ELM327 learns the ECU's response latency and tightens the wait window. On fast CAN ECUs this reduces per-PID overhead from 50–100ms down to **10–20ms**.
- This is the **single most impactful change** for faster polling.

### Technique 4: **Priority PID Tiering**
- Torque splits PIDs into "fast" (RPM, speed, throttle — polled every cycle) and "slow" (temps, fuel level — polled every N cycles).
- This reduces PIDs per cycle from e.g. 20 to 5, multiplying effective frequency of critical metrics by 4×.

### Technique 5: **`ATSTFF` / Response Length Hint**
- ELM327 v1.4b+ supports `ATBN` (bypass init) and `ATAL` (allow long messages).
- Some firmware versions support sending the expected byte count in the command, telling the ELM to stop reading early: `010C1` (append `1` = expect 1 message). Cuts wait time by not waiting for timeout.

### Technique 6: **BT Throughput — Use BLE (Bluetooth Low Energy) OBD Adapters**
- ELM327-based BLE (BT 4.0) adapters have **lower latency (~5–10ms)** vs Classic BT RFCOMM (~30–50ms).
- App needs to use `BluetoothGatt` instead of `BluetoothSocket` for BLE.
- Not all ELM327 adapters are BLE; this is a hardware constraint.

### Technique 7: **`ATMA` — Monitor All (Passive CAN Sniffing)**
- On CAN-bus vehicles, `ATMA` puts the ELM327 in passive listen mode — it streams all CAN frames without the app sending any requests.
- Throughput: hundreds of frames/second instead of request-response.
- **Downside:** requires parsing raw CAN PIDs (not OBD-II formatted), and not all PIDs are broadcast by the ECU unprompted. Fuel rate (015E) typically isn't broadcast passively.

---

## What's Actually Achievable with ELM327 + Classic BT

| Technique | Gain | Risk | Effort |
|-----------|------|------|--------|
| `ATAT1` adaptive timing | **3–5× per-PID speedup** | Low — widely supported | Low |
| Protocol lock after detection | ~10–20% | Low | Low |
| Priority PID tiering (fast/slow split) | **2–4× effective rate for critical PIDs** | None | Medium |
| Remove explicit `cmdDelay` between PIDs | ~20–50% | Low (some slow ECUs may miss) | Low |
| `ATH1`+`ATCAF0` raw mode | ~10–20% | Medium (breaks current string parser) | High |
| BLE adapter support | ~2–3× BT latency reduction | None (hardware dependent) | High |
| `ATMA` passive sniffing | Extreme but limited PID coverage | High (complex parsing) | Very High |

**Realistic target with ELM327 Classic BT:** From ~1–2s full cycle → **200–500ms** full cycle, or **~2–5× faster**.

---

## Current Code Bottlenecks in `BluetoothObd2Service`

1. **`ATAT0` (implicit default)** — no adaptive timing configured in `INIT_COMMANDS`
2. **No protocol lock** — `ATSP0` auto-detects every session but never locks
3. **Explicit `cmdDelay`** between every PID (adds `N × cmdDelay` ms per cycle)
4. **All PIDs treated equally** — no fast/slow tiering; slow PIDs (temp, fuel level) polled same as RPM/speed
5. **`ATS0` (spaces off) ✅** — already doing this (correct)
6. **`ATL0`, `ATE0`, `ATH0` ✅** — already doing this (correct)
7. **Response reader polls `iStream.available()` with `Thread.sleep(10)`** — busy-wait; a blocking read with `BufferedReader.readLine()` is lower latency

---

## Proposed Implementation

### Phase 1 — Low-risk, High-gain (recommended to implement now)

**1a. Add `ATAT1` to init sequence**
```kotlin
private val INIT_COMMANDS = listOf(
    "ATZ", "ATE0", "ATL0", "ATS0", "ATH0",
    "ATAT1",   // ← adaptive timing
    "ATSP0"
)
```

**1b. Lock protocol after auto-detect**
After `discoverSupportedPids()` succeeds, send `ATDP` to query detected protocol, then lock it:
```kotlin
// After discovery:
val detectedProtocol = sendCommand("ATDPN")  // returns e.g. "6" for ISO 15765 CAN 500k
if (detectedProtocol.isNotBlank()) sendCommand("ATSP$detectedProtocol")
```

**1c. Remove or reduce `cmdDelay` to 0 (let `ATAT1` handle timing)**
```kotlin
val cmdDelay = 0L  // ATAT1 handles per-command timing
```

**1d. Split PIDs into fast/slow tiers**
```kotlin
// Fast: polled every cycle (~200ms)
val fastPids = setOf("010C", "010D", "0110", "0111", "015E")
// Slow: polled every 5 cycles (~1s)
val slowPids = ...everything else...
```

### Phase 2 — Medium effort (optional, larger gain)

**2a. Blocking response reader** — replace `available()` busy-wait with `BufferedReader` for lower latency response reading.

**2b. BLE adapter support** — separate `BleObd2Service` implementation using `BluetoothGatt`.

---

## Files to Change (Phase 1)

| File | Change |
|------|--------|
| `BluetoothObd2Service.kt` | Add `ATAT1`, protocol lock, zero cmdDelay, fast/slow PID tiers |
| `AppSettings.kt` | May need to expose fast/slow tier configuration |

---

## Note on Bike (Brezza) ECU
The Maruti Brezza uses a **CAN-bus ECU (ISO 15765-4)**. This is the fastest OBD protocol supported by ELM327. With `ATAT1` + protocol lock, per-PID time on CAN can drop to **15–30ms**, giving a 5-PID fast cycle of ~100–150ms (6–10 Hz for RPM/speed/fuel).
