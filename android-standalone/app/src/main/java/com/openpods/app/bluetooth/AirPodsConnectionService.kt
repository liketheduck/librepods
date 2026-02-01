package com.openpods.app.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openpods.app.MainActivity
import com.openpods.app.R
import com.openpods.app.data.AirPodsState
import com.openpods.app.data.ListeningMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that maintains connection to AirPods
 * Provides BLE scanning and L2CAP communication
 */
class AirPodsConnectionService : Service() {
    companion object {
        private const val TAG = "AirPodsService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "airpods_connection"
    }

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsConnectionService = this@AirPodsConnectionService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    lateinit var bleScanner: BLEScanner
        private set
    lateinit var l2capManager: L2CAPManager
        private set

    val airPodsState: StateFlow<AirPodsState>
        get() = if (l2capManager.isConnected()) l2capManager.airPodsState else bleScanner.airPodsState

    val connectionState: StateFlow<L2CAPManager.ConnectionState>
        get() = l2capManager.connectionState

    val errorMessage: StateFlow<String?>
        get() = l2capManager.errorMessage

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        bleScanner = BLEScanner(this)
        l2capManager = L2CAPManager()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(null))

        // Start BLE scanning
        bleScanner.startScanning()

        // Update notification when state changes
        scope.launch {
            combine(
                bleScanner.airPodsState,
                l2capManager.airPodsState,
                l2capManager.connectionState
            ) { bleState, l2capState, connState ->
                if (connState == L2CAPManager.ConnectionState.CONNECTED) l2capState else bleState
            }.collect { state ->
                updateNotification(state)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        bleScanner.stopScanning()
        l2capManager.disconnect()
        scope.cancel()
    }

    /**
     * Attempt to establish L2CAP connection to the specified device
     */
    suspend fun connectL2cap(device: BluetoothDevice): Boolean {
        return l2capManager.connect(device)
    }

    /**
     * Disconnect L2CAP (BLE scanning continues)
     */
    fun disconnectL2cap() {
        l2capManager.disconnect()
    }

    /**
     * Set listening/noise control mode
     */
    suspend fun setListeningMode(mode: ListeningMode): Boolean {
        return l2capManager.setListeningMode(mode)
    }

    /**
     * Toggle conversational awareness
     */
    suspend fun setConversationalAwareness(enabled: Boolean): Boolean {
        return l2capManager.setConversationalAwareness(enabled)
    }

    /**
     * Set adaptive audio strength (0-100)
     */
    suspend fun setAdaptiveStrength(level: Int): Boolean {
        return l2capManager.setAdaptiveStrength(level)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(state: AirPodsState?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (state?.isConnected == true) {
            state.name
        } else {
            "Scanning for AirPods..."
        }

        val text = if (state?.isConnected == true) {
            buildString {
                if (state.leftBattery != null) append("L: ${state.leftBattery}%")
                if (state.rightBattery != null) {
                    if (isNotEmpty()) append(" | ")
                    append("R: ${state.rightBattery}%")
                }
                if (state.caseBattery != null) {
                    if (isNotEmpty()) append(" | ")
                    append("Case: ${state.caseBattery}%")
                }
                if (state.isL2capConnected) {
                    if (isNotEmpty()) append(" • ")
                    append(state.listeningMode.displayName)
                }
            }.ifEmpty { "Connected" }
        } else {
            "Waiting for AirPods..."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(state: AirPodsState) {
        val notification = createNotification(state)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
