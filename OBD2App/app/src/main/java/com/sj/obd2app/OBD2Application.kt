package com.sj.obd2app

import android.app.Application
import org.osmdroid.config.Configuration

class OBD2Application : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("osmdroid", MODE_PRIVATE)
        Configuration.getInstance().load(this, prefs)
        Configuration.getInstance().userAgentValue = packageName
    }
}
