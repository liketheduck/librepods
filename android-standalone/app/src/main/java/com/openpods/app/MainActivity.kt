package com.openpods.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.openpods.app.bluetooth.AirPodsConnectionService
import com.openpods.app.ui.screens.HomeScreen
import com.openpods.app.ui.screens.PermissionsScreen
import com.openpods.app.ui.theme.OpenPodsTheme

class MainActivity : ComponentActivity() {
    private var service: AirPodsConnectionService? = null
    private var serviceBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AirPodsConnectionService.LocalBinder
            service = localBinder?.getService()
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OpenPodsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpenPodsApp(
                        service = if (serviceBound) service else null,
                        onServiceReady = { startAndBindService() }
                    )
                }
            }
        }
    }

    private fun startAndBindService() {
        val intent = Intent(this, AirPodsConnectionService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OpenPodsApp(
    service: AirPodsConnectionService?,
    onServiceReady: () -> Unit
) {
    val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionsState = rememberMultiplePermissionsState(permissions)

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted) {
            onServiceReady()
        }
    }

    if (permissionsState.allPermissionsGranted) {
        HomeScreen(
            service = service,
            onRequestPermissions = { /* Open settings */ }
        )
    } else {
        PermissionsScreen(
            onRequestPermissions = {
                permissionsState.launchMultiplePermissionRequest()
            }
        )
    }
}
