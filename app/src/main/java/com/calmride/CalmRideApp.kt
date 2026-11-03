package com.calmride

import android.app.Application
import com.calmride.runtime.CalmRideController

class CalmRideApp : Application() {

    val controller: CalmRideController by lazy { CalmRideController(this) }

    override fun onCreate() {
        super.onCreate()
        controller.start()
    }
}
