package com.sj.obd2app.ui.connect

/**
 * Sealed class representing items in the sectioned device list.
 * Used by SectionedDeviceAdapter to display headers and devices.
 */
sealed class DeviceListItem {
    /**
     * Section header item.
     * @param title Section title (e.g., "Potential OBD Devices")
     * @param count Number of devices in this section
     */
    data class Header(val title: String, val count: Int) : DeviceListItem()
    
    /**
     * Device item.
     * @param info Device metadata including BluetoothDevice, paired status, OBD flag, and RSSI
     */
    data class Device(val info: DeviceInfo) : DeviceListItem()
}
