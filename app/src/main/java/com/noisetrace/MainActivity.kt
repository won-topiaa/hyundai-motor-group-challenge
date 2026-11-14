package com.noisetrace

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noisetrace.runtime.NoiseTraceService
import com.noisetrace.ui.NoiseTraceScreen
import com.noisetrace.ui.NoiseTraceTheme
import com.noisetrace.vehicle.CarApiSource

class MainActivity : ComponentActivity() {

    private val controller get() = (application as NoiseTraceApp).controller

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.onPermissionsGranted()
        // Restart the foreground service so it may now use the microphone in the background.
        NoiseTraceService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        NoiseTraceService.start(this)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            LaunchedEffect(state.needMicPermission) {
                if (state.needMicPermission) permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            NoiseTraceTheme {
                NoiseTraceScreen(state = state, controller = controller)
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
