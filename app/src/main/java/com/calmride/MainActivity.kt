package com.calmride

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calmride.ui.CalmRideScreen
import com.calmride.ui.CalmRideTheme
import com.calmride.runtime.CalmRideService
import com.calmride.vehicle.CarApiSource

class MainActivity : ComponentActivity() {

    private val controller get() = (application as CalmRideApp).controller

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.onPermissionsGranted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        // Runs for the whole shift, so boarding is noticed while the navigation app is in front.
        CalmRideService.start(this)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            CalmRideTheme {
                CalmRideScreen(state = state, controller = controller)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val wanted = CarApiSource.RUNTIME_PERMISSIONS.toMutableList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }
}
