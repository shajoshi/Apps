# Stabilise OBD II Data List on Details Page

Fix the Details page OBD II Data section where the row count keeps changing during polling, by stabilising the PID list after the first complete polling cycle.

## Problem

The OBD II Data RecyclerView on the Details page shows rows appearing and disappearing because:

1. **Slow-tier PIDs populate incrementally** — fast PIDs (RPM, speed, throttle) appear immediately, but slow PIDs (temps, fuel level, distances, etc.) only get polled every 5th cycle. The list grows over the first few seconds.
2. **Some PIDs pass bitmask discovery but intermittently fail actual queries** — the ECU claims support via 0100/0120/0140/0160 bitmasks, but individual PIDs may return `NODATA` or unparseable responses, so they enter the cache sporadically.

The result: the row count fluctuates as PIDs succeed or fail for the first time, making the Details page feel "jumpy."

## Root Cause Location

`BluetoothObd2Service.kt` → `startPolling()` (lines 270–370):
- `cachedResults` is a `mutableMapOf` that grows as PIDs succeed
- `_obd2Data.value = cachedResults.values.toList()` emits the current cache every cycle
- No mechanism to stabilise the list after initial population

## Fix: Two-Phase Polling

### Phase 1: Discovery warm-up (first full cycle including slow tier)

During the first `SLOW_TIER_MODULO` cycles (i.e., until `cycleCount` reaches `SLOW_TIER_MODULO`), poll normally and let `cachedResults` accumulate. Don't emit to `_obd2Data` until the first slow-tier cycle completes (cycle index `SLOW_TIER_MODULO`). This ensures the UI sees a complete, stable list from the start.

### Phase 2: Stable polling

After the warm-up phase:
- Continue polling as before, updating values in `cachedResults`
- **Never remove entries** from `cachedResults` (already the case)
- Emit `_obd2Data.value` using a **sorted, stable list** — sort by PID hex string so insertion order doesn't matter
- PIDs that fail after warm-up retain their last known value in the cache

### Implementation Details

**File: `BluetoothObd2Service.kt`**

1. Add a `warmUpComplete` flag, initially `false`
2. After the first slow-tier poll completes (`cycleCount == SLOW_TIER_MODULO`), set `warmUpComplete = true`
3. Only start emitting `_obd2Data.value` once `warmUpComplete` is true
4. When emitting, sort `cachedResults.values` by PID to guarantee stable ordering:
   ```kotlin
   _obd2Data.value = cachedResults.values.sortedBy { it.pid }
   ```

**File: `DetailsFragment.kt`** (no changes needed — the `Obd2Adapter` already uses `DiffUtil` with `areItemsTheSame` keyed on PID, so a stable-sized sorted list will diff cleanly with only value updates)

## Files Changed

| File | Change |
|------|--------|
| `BluetoothObd2Service.kt` | Add warm-up phase gate + sorted emission |

## Testing

- Connect to Alto → Details page should show no rows for ~1-2 seconds (warm-up), then a stable complete list appears at once
- Row count should not change after initial appearance
- Values should continue updating in-place
- Disconnecting and reconnecting should repeat the same stable behavior
