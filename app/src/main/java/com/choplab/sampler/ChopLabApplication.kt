package com.choplab.sampler

import android.app.Application
import android.content.Context
import android.system.Os

class ChopLabApplication : Application() {
    override fun attachBaseContext(base: Context) {
        // Before providers, activities or model workers can load ORT's native 1DS uploader.
        Os.setenv("ORT_DISABLE_TELEMETRY", "1", true)
        super.attachBaseContext(base)
    }
}
