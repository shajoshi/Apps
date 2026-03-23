# BLE Support for ELM327 Adapters

## Overview

The OBD2 app now supports both **Classic Bluetooth (SPP)** and **Bluetooth Low Energy (BLE)** connections to ELM327 adapters. The connection type is automatically detected based on the device type.

## Architecture

### Transport Abstraction Layer

The implementation uses a transport abstraction pattern with three main components:

1. **`Elm327Transport`** (interface) - Common interface for all transport types
2. **`ClassicBluetoothTransport`** - RFCOMM/SPP implementation for Classic Bluetooth
3. **`BleTransport`** - GATT implementation for BLE devices

### Auto-Detection

The `BluetoothObd2Service` automatically detects the device type using `BluetoothDevice.type`:

- **`DEVICE_TYPE_LE`** → Uses BLE transport
- **`DEVICE_TYPE_DUAL`** → Uses Classic Bluetooth (more reliable for serial data)
- **`DEVICE_TYPE_CLASSIC`** or unknown → Uses Classic Bluetooth

## BLE Implementation Details

### Supported Service UUIDs

The BLE transport supports two common ELM327 BLE service types:

#### 1. Generic ELM327 BLE Service
- **Service UUID**: `0000fff0-0000-1000-8000-00805f9b34fb`
- **TX Characteristic** (write): `0000fff1-0000-1000-8000-00805f9b34fb` or `0000fff2-0000-1000-8000-00805f9b34fb`
- **RX Characteristic** (notify): `0000fff2-0000-1000-8000-00805f9b34fb` or `0000fff1-0000-1000-8000-00805f9b34fb`

#### 2. Nordic UART Service (NUS)
- **Service UUID**: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- **TX Characteristic**: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- **RX Characteristic**: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

The transport automatically discovers which service is available during connection.

### Communication Flow

1. **Connection**: GATT connection established via `connectGatt()`
2. **Service Discovery**: Automatically discovers ELM327 or NUS service
3. **Notification Setup**: Enables notifications on RX characteristic
4. **Command/Response**: 
   - Commands sent via TX characteristic write
   - Responses received via RX characteristic notifications
   - Response buffering until ELM327 prompt character `>` is received

## Permissions

The app already has the necessary permissions in `AndroidManifest.xml`:

```xml
<!-- Classic Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />

<!-- BLE and Android 12+ -->
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />

<!-- Location (required for BLE scanning on Android < 12) -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

No additional permissions are required for BLE support.

## Usage

### For Users

No code changes needed - the app automatically detects and uses the appropriate connection type:

```kotlin
val service = BluetoothObd2Service.getInstance(context)
service.connect(bluetoothDevice)  // Auto-detects Classic BT or BLE
```

### Connection Log

The connection log will show the detected transport type:

```
Connecting to ELM327 BLE…
Device type: BLE only
Detected device type: BLE (GATT)
Establishing connection…
✓ Connection established
Initialising ELM327 adapter…
```

## Testing Recommendations

### Classic Bluetooth Devices
- Standard ELM327 v1.5 adapters with SPP
- Should continue working as before

### BLE Devices
- Viecar/Veepeak BLE adapters
- OBDLink MX+ (dual-mode, will use Classic BT)
- Generic Chinese BLE ELM327 clones

### Test Scenarios

1. **Connection**: Verify both Classic and BLE devices connect successfully
2. **PID Discovery**: Ensure supported PIDs are discovered correctly
3. **Data Polling**: Confirm continuous data streaming works
4. **Reconnection**: Test disconnect/reconnect cycles
5. **Error Handling**: Verify graceful handling of connection failures

## Known Limitations

1. **BLE MTU**: Some BLE adapters have limited MTU (20-23 bytes), which may require response fragmentation for long responses
2. **Latency**: BLE may have slightly higher latency than Classic Bluetooth due to GATT overhead
3. **Connection Interval**: BLE connection intervals are device-dependent and may affect polling frequency
4. **Service Discovery**: BLE service discovery takes ~2 seconds during connection

## Troubleshooting

### BLE Connection Fails

1. **Check device type**: Ensure the device advertises as BLE
2. **Service UUIDs**: Some adapters use custom UUIDs - may need to add support
3. **Pairing**: Some BLE devices require pairing before connection
4. **Android version**: BLE works best on Android 8.0+ (API 26+)

### Slow Response Times

- BLE adapters may have longer response times than Classic Bluetooth
- Adjust `ATAT1` adaptive timing to compensate
- Consider increasing timeout values if needed

### Characteristic Not Found

If the adapter uses non-standard UUIDs, you'll need to:
1. Scan for available services using a BLE scanner app
2. Add the custom UUIDs to `BleTransport.kt`
3. Update the characteristic discovery logic

## Future Enhancements

Potential improvements for BLE support:

1. **MTU Negotiation**: Request larger MTU for better throughput
2. **Connection Parameters**: Optimize connection interval and latency
3. **Custom UUID Support**: Allow users to configure custom service/characteristic UUIDs
4. **Dual Transport Fallback**: For dual-mode devices, try BLE if Classic fails
5. **Response Fragmentation**: Handle fragmented responses for long data
