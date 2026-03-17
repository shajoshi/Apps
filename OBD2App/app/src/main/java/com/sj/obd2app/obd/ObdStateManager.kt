package com.sj.obd2app.obd

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Centralized OBD state manager singleton.
 * Provides a single source of truth for OBD connection mode and settings.
 * All UI components should observe this for consistent state.
 */
object ObdStateManager {
    
    // OBD connection modes
    enum class Mode {
        MOCK,      // Simulation mode
        REAL       // Real OBD via Bluetooth
    }
    
    // Connection states
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    // Private state
    private val _mode = MutableStateFlow(Mode.MOCK)
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _autoConnect = MutableStateFlow(false)
    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    
    // Public read-only StateFlows
    val mode: StateFlow<Mode> = _mode.asStateFlow()
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()
    
    // Convenience properties for immediate access
    val isMockMode: Boolean get() = _mode.value == Mode.MOCK
    val isRealMode: Boolean get() = _mode.value == Mode.REAL
    val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED
    val isConnecting: Boolean get() = _connectionState.value == ConnectionState.CONNECTING
    
    /**
     * Initialize the state manager with current settings.
     * Call this during app startup.
     */
    fun initialize(autoConnectEnabled: Boolean, obdConnectionEnabled: Boolean) {
        _mode.value = if (obdConnectionEnabled) Mode.REAL else Mode.MOCK
        _autoConnect.value = autoConnectEnabled
        
        // Update Obd2ServiceProvider to maintain compatibility
        Obd2ServiceProvider.useMock = isMockMode
    }
    
    /**
     * Switch OBD mode (called from Settings).
     */
    fun switchMode(newMode: Mode) {
        if (_mode.value != newMode) {
            _mode.value = newMode
            
            // Update Obd2ServiceProvider for compatibility
            Obd2ServiceProvider.useMock = isMockMode
            
            // Reset connection state when switching modes
            _connectionState.value = ConnectionState.DISCONNECTED
            _connectedDeviceName.value = null
        }
    }
    
    /**
     * Update auto-connect setting.
     */
    fun setAutoConnect(enabled: Boolean) {
        _autoConnect.value = enabled
    }
    
    /**
     * Update connection state (called by OBD service or fragments).
     */
    fun updateConnectionState(state: ConnectionState, deviceName: String? = null) {
        _connectionState.value = state
        _connectedDeviceName.value = deviceName
    }
    
    /**
     * Get user-friendly connection status text for UI.
     */
    fun getConnectionStatusText(): String {
        return when {
            isMockMode && isConnected -> "Simulation Mode"
            isMockMode -> "Disconnected"
            isConnected -> _connectedDeviceName.value?.let { "Connected · $it" } ?: "Connected"
            isConnecting -> "Connecting…"
            _connectionState.value == ConnectionState.ERROR -> "Error"
            else -> "Disconnected"
        }
    }
    
    /**
     * Check if auto-connection should happen based on current mode and settings.
     */
    fun shouldAutoConnect(): Boolean {
        return _autoConnect.value && _connectionState.value == ConnectionState.DISCONNECTED
    }
}
