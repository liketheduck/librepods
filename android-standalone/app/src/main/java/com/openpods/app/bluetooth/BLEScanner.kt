package com.openpods.app.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.openpods.app.data.AirPodsModel
import com.openpods.app.data.AirPodsState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BLE Scanner for detecting AirPods through BLE advertisements
 * Parses Apple's proximity pairing messages to extract battery and status info
 * Works without root - uses standard Android BLE APIs
 */
class BLEScanner(private val context: Context) {
    companion object {
        private const val TAG = "BLEScanner"
        private const val APPLE_MANUFACTURER_ID = 76
        private const val PROXIMITY_PAIRING_TYPE: Byte = 0x07
        private const val PROXIMITY_PAIRING_LENGTH: Byte = 0x19
    }

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var isScanning = false

    private val _airPodsState = MutableStateFlow(AirPodsState())
    val airPodsState: StateFlow<AirPodsState> = _airPodsState.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanningFlow: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Color mapping
    private val colorNames = mapOf(
        0x00 to "White", 0x01 to "Black", 0x02 to "Red", 0x03 to "Blue",
        0x04 to "Pink", 0x05 to "Gray", 0x06 to "Silver", 0x07 to "Gold",
        0x08 to "Rose Gold", 0x09 to "Space Gray", 0x0A to "Dark Blue",
        0x0B to "Light Blue", 0x0C to "Yellow"
    )

    @SuppressLint("MissingPermission")
    fun startScanning(): Boolean {
        if (isScanning) {
            Log.d(TAG, "Already scanning")
            return true
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter

        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled")
            return false
        }

        bluetoothLeScanner = adapter.bluetoothLeScanner ?: return false

        // Create scan filter for Apple devices with proximity pairing data
        val manufacturerData = ByteArray(27)
        val manufacturerDataMask = ByteArray(27)

        // Filter for proximity pairing message type
        manufacturerData[0] = PROXIMITY_PAIRING_TYPE
        manufacturerData[1] = PROXIMITY_PAIRING_LENGTH
        manufacturerDataMask[0] = 0xFF.toByte()
        manufacturerDataMask[1] = 0xFF.toByte()

        val scanFilter = ScanFilter.Builder()
            .setManufacturerData(APPLE_MANUFACTURER_ID, manufacturerData, manufacturerDataMask)
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(300L)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                processScanResult(result)
            }

            override fun onBatchScanResults(results: List<ScanResult>) {
                results.forEach { processScanResult(it) }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE scan failed with error code: $errorCode")
                isScanning = false
                _isScanning.value = false
            }
        }

        try {
            bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
            isScanning = true
            _isScanning.value = true
            Log.d(TAG, "BLE scanning started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BLE scan", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return

        try {
            scanCallback?.let { bluetoothLeScanner?.stopScan(it) }
            scanCallback = null
            isScanning = false
            _isScanning.value = false
            Log.d(TAG, "BLE scanning stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scan", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun processScanResult(result: ScanResult) {
        val manufacturerData = result.scanRecord?.getManufacturerSpecificData(APPLE_MANUFACTURER_ID)
            ?: return

        if (manufacturerData.size < 20) return

        // Verify it's a proximity pairing message
        if (manufacturerData[0] != PROXIMITY_PAIRING_TYPE || manufacturerData[1] != PROXIMITY_PAIRING_LENGTH) {
            return
        }

        try {
            val parsed = parseProximityMessage(result.device.address, manufacturerData)
            _airPodsState.value = parsed

            Log.d(TAG, "AirPods detected: ${parsed.model.displayName}, " +
                    "L:${parsed.leftBattery}%, R:${parsed.rightBattery}%, C:${parsed.caseBattery}%")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing proximity message", e)
        }
    }

    private fun parseProximityMessage(address: String, data: ByteArray): AirPodsState {
        // Model ID is at bytes 3-4 (big endian)
        val modelId = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
        val model = AirPodsModel.fromModelId(modelId)

        // Status byte contains ear detection and orientation info
        val status = data[5].toInt() and 0xFF

        // Battery info is in bytes 6-7 (nibbles) for unencrypted advertisements
        val podsBattery = data[6].toInt() and 0xFF
        val flagsCase = data[7].toInt() and 0xFF

        // Determine if left/right are flipped based on primary pod
        val primaryLeft = ((status shr 5) and 0x01) == 1
        val isFlipped = !primaryLeft

        // Extract battery levels from nibbles
        val leftBatteryNibble = if (isFlipped) (podsBattery shr 4) and 0x0F else podsBattery and 0x0F
        val rightBatteryNibble = if (isFlipped) podsBattery and 0x0F else (podsBattery shr 4) and 0x0F
        val caseBatteryNibble = flagsCase and 0x0F
        val flags = (flagsCase shr 4) and 0x0F

        // Decode battery levels (nibble to percentage)
        fun decodeBattery(n: Int): Int? = when (n) {
            in 0x0..0x9 -> n * 10
            in 0xA..0xE -> 100
            0xF -> null
            else -> null
        }

        // Charging status from flags
        val isLeftCharging = if (isFlipped) (flags and 0x02) != 0 else (flags and 0x01) != 0
        val isRightCharging = if (isFlipped) (flags and 0x01) != 0 else (flags and 0x02) != 0
        val isCaseCharging = (flags and 0x04) != 0

        // Ear detection from status byte
        val thisInCase = ((status shr 6) and 0x01) == 1
        val xorFactor = primaryLeft xor thisInCase
        val isLeftInEar = if (xorFactor) (status and 0x08) != 0 else (status and 0x02) != 0
        val isRightInEar = if (xorFactor) (status and 0x02) != 0 else (status and 0x08) != 0

        // Color from byte 9
        val colorValue = data[9].toInt() and 0xFF
        val color = colorNames[colorValue] ?: "Unknown"

        return AirPodsState(
            isConnected = true,
            isL2capConnected = false,
            name = model.displayName,
            model = model,
            leftBattery = decodeBattery(leftBatteryNibble),
            rightBattery = decodeBattery(rightBatteryNibble),
            caseBattery = decodeBattery(caseBatteryNibble),
            isLeftCharging = isLeftCharging,
            isRightCharging = isRightCharging,
            isCaseCharging = isCaseCharging,
            isLeftInEar = isLeftInEar,
            isRightInEar = isRightInEar,
            macAddress = address,
            color = color
        )
    }

    fun getBluetoothAdapter(): BluetoothAdapter? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter
    }
}
