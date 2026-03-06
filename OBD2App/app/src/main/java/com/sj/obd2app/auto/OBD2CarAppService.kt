package com.sj.obd2app.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Entry point for Android Auto.
 * It validates the host (e.g. Android Auto desktop head unit or real car)
 * and creates the [OBD2Session] for the UI.
 */
class OBD2CarAppService : CarAppService() {
    
    override fun createHostValidator(): HostValidator {
        // Allows connection to any car host; in production, you might constrain this.
        return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
    }

    override fun onCreateSession(): Session {
        return OBD2Session()
    }
}
