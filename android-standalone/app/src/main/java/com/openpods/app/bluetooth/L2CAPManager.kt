package com.openpods.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.util.Log
import com.openpods.app.data.AirPodsState
import com.openpods.app.data.EarStatus
import com.openpods.app.data.ListeningMode
import com.openpods.app.protocol.AAPProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * L2CAP Connection Manager for AirPods
 * Uses Android's public L2CAP APIs (available since Android 10)
 *
 * Note: On some devices/ROMs, this may not work due to Android's Bluetooth stack
 * having issues with FCR (Flow Control and Retransmission) mode negotiation.
 * Works best on OxygenOS/ColorOS 16+ or devices with fixed Bluetooth stacks.
 */
class L2CAPManager {
    companion object {
        private const val TAG = "L2CAPManager"
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var scope: CoroutineScope? = null
    private var readJob: Job? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _airPodsState = MutableStateFlow(AirPodsState())
    val airPodsState: StateFlow<AirPodsState> = _airPodsState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        FAILED
    }

    /**
     * Attempt to connect to AirPods using public L2CAP APIs
     * Returns true if connection was initiated, false if it failed immediately
     */
    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.CONNECTED) {
            Log.d(TAG, "Already connected or connecting")
            return@withContext false
        }

        _connectionState.value = ConnectionState.CONNECTING
        _errorMessage.value = null

        try {
            // Try to create L2CAP socket using public API
            // createInsecureL2capChannel is available since API 29 (Android 10)
            socket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    // First try insecure channel (doesn't require pairing encryption)
                    device.createInsecureL2capChannel(AAPProtocol.AIRPODS_PSM)
                } catch (e: Exception) {
                    Log.w(TAG, "Insecure L2CAP failed, trying secure channel", e)
                    try {
                        // Fall back to secure channel
                        device.createL2capChannel(AAPProtocol.AIRPODS_PSM)
                    } catch (e2: Exception) {
                        Log.e(TAG, "Both L2CAP methods failed", e2)
                        throw e2
                    }
                }
            } else {
                throw UnsupportedOperationException("L2CAP requires Android 10 (API 29) or higher")
            }

            // Connect with timeout
            Log.d(TAG, "Attempting L2CAP connection to ${device.address}")
            socket?.connect()

            if (socket?.isConnected != true) {
                throw Exception("Socket not connected after connect() call")
            }

            inputStream = socket?.inputStream
            outputStream = socket?.outputStream

            // Start read loop
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            startReadLoop()

            // Send handshake
            delay(100)
            sendHandshake()

            // Request notifications
            delay(100)
            requestNotifications()

            // Enable advanced features
            delay(100)
            enableFeatures()

            _connectionState.value = ConnectionState.CONNECTED
            _airPodsState.update { it.copy(isL2capConnected = true) }

            Log.d(TAG, "L2CAP connection established successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish L2CAP connection", e)
            _connectionState.value = ConnectionState.FAILED
            _errorMessage.value = getReadableError(e)
            disconnect()
            false
        }
    }

    private fun getReadableError(e: Exception): String {
        return when {
            e.message?.contains("EHOSTDOWN") == true ->
                "Device not reachable. Make sure AirPods are connected via Bluetooth."
            e.message?.contains("ECONNREFUSED") == true ->
                "Connection refused. AirPods may not accept L2CAP on this Android version."
            e.message?.contains("permission") == true ->
                "Bluetooth permission denied."
            e.message?.contains("EACCES") == true ->
                "L2CAP blocked. This device may require root for full AirPods control."
            e is UnsupportedOperationException ->
                e.message ?: "L2CAP not supported"
            else -> e.message ?: "Unknown connection error"
        }
    }

    private fun startReadLoop() {
        readJob = scope?.launch {
            val buffer = ByteArray(1024)

            while (isActive && socket?.isConnected == true) {
                try {
                    val bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead > 0) {
                        val packet = buffer.copyOfRange(0, bytesRead)
                        handlePacket(packet)
                    } else if (bytesRead == -1) {
                        Log.d(TAG, "End of stream reached")
                        break
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e(TAG, "Error reading from socket", e)
                    }
                    break
                }
            }

            // Connection lost
            withContext(Dispatchers.Main) {
                if (_connectionState.value == ConnectionState.CONNECTED) {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _airPodsState.update { it.copy(isL2capConnected = false) }
                }
            }
        }
    }

    private fun handlePacket(data: ByteArray) {
        Log.d(TAG, "Received packet: ${data.toHexString()}")

        when (val parsed = AAPProtocol.parsePacket(data)) {
            is AAPProtocol.ParsedPacket.Battery -> {
                _airPodsState.update { state ->
                    state.copy(
                        leftBattery = parsed.leftLevel ?: state.leftBattery,
                        rightBattery = parsed.rightLevel ?: state.rightBattery,
                        caseBattery = parsed.caseLevel ?: state.caseBattery,
                        isLeftCharging = parsed.leftCharging,
                        isRightCharging = parsed.rightCharging,
                        isCaseCharging = parsed.caseCharging
                    )
                }
                Log.d(TAG, "Battery update: L:${parsed.leftLevel}, R:${parsed.rightLevel}, C:${parsed.caseLevel}")
            }

            is AAPProtocol.ParsedPacket.EarDetection -> {
                val leftInEar = parsed.primaryStatus == EarStatus.IN_EAR ||
                               parsed.secondaryStatus == EarStatus.IN_EAR
                val rightInEar = parsed.primaryStatus == EarStatus.IN_EAR ||
                                parsed.secondaryStatus == EarStatus.IN_EAR

                _airPodsState.update { state ->
                    state.copy(
                        isLeftInEar = leftInEar,
                        isRightInEar = rightInEar
                    )
                }
                Log.d(TAG, "Ear detection: Primary=${parsed.primaryStatus}, Secondary=${parsed.secondaryStatus}")
            }

            is AAPProtocol.ParsedPacket.ControlCommand -> {
                handleControlCommand(parsed.identifier, parsed.value)
            }

            is AAPProtocol.ParsedPacket.ConversationalAwareness -> {
                Log.d(TAG, "Conversational awareness level: ${parsed.level}")
            }

            is AAPProtocol.ParsedPacket.DeviceInfo -> {
                _airPodsState.update { state ->
                    state.copy(name = parsed.name.ifEmpty { state.name })
                }
                Log.d(TAG, "Device info: ${parsed.name}, ${parsed.modelNumber}")
            }

            is AAPProtocol.ParsedPacket.Unknown -> {
                Log.d(TAG, "Unknown packet opcode: ${parsed.opcode}")
            }

            null -> {
                Log.w(TAG, "Failed to parse packet")
            }
        }
    }

    private fun handleControlCommand(identifier: Byte, value: ByteArray) {
        when (identifier) {
            AAPProtocol.ControlIds.LISTENING_MODE -> {
                val mode = ListeningMode.fromByte(value.getOrNull(0) ?: 0)
                _airPodsState.update { it.copy(listeningMode = mode) }
                Log.d(TAG, "Listening mode changed to: ${mode.displayName}")
            }

            AAPProtocol.ControlIds.CONVERSATION_DETECT_CONFIG -> {
                val enabled = value.getOrNull(0) == 0x01.toByte()
                _airPodsState.update { it.copy(conversationalAwareness = enabled) }
                Log.d(TAG, "Conversational awareness: $enabled")
            }

            AAPProtocol.ControlIds.ADAPTIVE_STRENGTH -> {
                val level = (value.getOrNull(0)?.toInt() ?: 50) and 0xFF
                _airPodsState.update { it.copy(adaptiveStrength = level) }
                Log.d(TAG, "Adaptive strength: $level")
            }

            else -> {
                Log.d(TAG, "Unhandled control command: ${identifier.toHexString()}")
            }
        }
    }

    private suspend fun sendPacket(packet: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            outputStream?.write(packet)
            outputStream?.flush()
            Log.d(TAG, "Sent packet: ${packet.toHexString()}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet", e)
            false
        }
    }

    private suspend fun sendHandshake() {
        sendPacket(AAPProtocol.createHandshakePacket())
    }

    private suspend fun requestNotifications() {
        sendPacket(AAPProtocol.createNotificationRequestPacket())
    }

    private suspend fun enableFeatures() {
        sendPacket(AAPProtocol.createFeatureFlagsPacket())
    }

    /**
     * Set the listening/noise control mode
     */
    suspend fun setListeningMode(mode: ListeningMode): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot set listening mode: not connected")
            return false
        }
        return sendPacket(AAPProtocol.createListeningModePacket(mode))
    }

    /**
     * Toggle conversational awareness
     */
    suspend fun setConversationalAwareness(enabled: Boolean): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot set conversational awareness: not connected")
            return false
        }
        return sendPacket(AAPProtocol.createConversationalAwarenessPacket(enabled))
    }

    /**
     * Set adaptive audio noise strength (0-100)
     */
    suspend fun setAdaptiveStrength(level: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot set adaptive strength: not connected")
            return false
        }
        return sendPacket(AAPProtocol.createAdaptiveStrengthPacket(level))
    }

    /**
     * Disconnect from AirPods
     */
    fun disconnect() {
        try {
            readJob?.cancel()
            scope?.cancel()

            inputStream?.close()
            outputStream?.close()
            socket?.close()

            socket = null
            inputStream = null
            outputStream = null
            scope = null
            readJob = null

            _connectionState.value = ConnectionState.DISCONNECTED
            _airPodsState.update { it.copy(isL2capConnected = false) }

            Log.d(TAG, "Disconnected from AirPods")
        } catch (e: Exception) {
            Log.e(TAG, "Error during disconnect", e)
        }
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED

    private fun ByteArray.toHexString(): String = joinToString(" ") { "%02X".format(it) }
    private fun Byte.toHexString(): String = "%02X".format(this)
}
