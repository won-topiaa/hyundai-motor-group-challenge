package com.noisetrace

import android.app.Application
import com.noisetrace.runtime.NoiseTraceController

class NoiseTraceApp : Application() {

    val controller: NoiseTraceController by lazy { NoiseTraceController(this) }

    override fun onCreate() {
        super.onCreate()
        controller.start()
    }
}
