# Fix Suspend Function Call Error

Fix the compilation error where `suspend fun sendCommand(command: String): String` is being called from a non-suspend function. The issue is in the `pollCustomPids` function which calls `transport?.sendCommand()` but is not marked as suspend.

## Problem Analysis
- Error occurs at line 488 in `BluetoothObd2Service.kt`
- `pollCustomPids` function calls `transport?.sendCommand()` (suspend function) on lines 488, 499, and 516
- `pollCustomPids` is called from within a coroutine scope (line 303: `CoroutineScope(Dispatchers.IO).launch`)
- The function needs to be marked as `suspend` to call suspend functions

## Solution
Add `suspend` modifier to the `pollCustomPids` function signature on line 472.

## Files to Modify
- `c:\Users\ShaileshJoshi\AndroidStudioProjects\Apps\OBD2App\app\src\main\java\com\sj\obd2app\obd\BluetoothObd2Service.kt`

## Changes Required
- Line 472: Change `private fun pollCustomPids(` to `private suspend fun pollCustomPids(`

This is a minimal, targeted fix that addresses the root cause of the compilation error.
