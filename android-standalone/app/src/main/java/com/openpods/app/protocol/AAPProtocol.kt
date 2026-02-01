package com.openpods.app.protocol

import android.util.Log
import com.openpods.app.data.BatteryComponent
import com.openpods.app.data.EarStatus
import com.openpods.app.data.ListeningMode

/**
 * Apple AirPods Protocol (AAP) implementation
 * Handles packet construction and parsing for L2CAP communication
 */
object AAPProtocol {
    private const val TAG = "AAPProtocol"

    // L2CAP PSM for AirPods
    const val AIRPODS_PSM = 0x1001

    // Packet header
    private val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)

    // Opcodes
    object Opcodes {
        const val BATTERY_INFO: Byte = 0x04
        const val EAR_DETECTION: Byte = 0x06
        const val CONTROL_COMMAND: Byte = 0x09
        const val REQUEST_NOTIFICATIONS: Byte = 0x0F
        const val INFORMATION: Byte = 0x1D
        const val RENAME: Byte = 0x1E
        const val SET_FEATURE_FLAGS: Byte = 0x4D
        const val CONVERSATION_AWARENESS: Byte = 0x4B
    }

    // Control command identifiers
    object ControlIds {
        const val LISTENING_MODE: Byte = 0x0D
        const val LISTENING_MODE_CONFIGS: Byte = 0x1A
        const val CONVERSATION_DETECT_CONFIG: Byte = 0x28
        const val ADAPTIVE_STRENGTH: Byte = 0x2E
        const val EAR_DETECTION_CONFIG: Byte = 0x0A
    }

    /**
     * Create handshake packet - required to establish connection
     */
    fun createHandshakePacket(): ByteArray {
        return byteArrayOf(
            0x00, 0x00, 0x04, 0x00,
            0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        )
    }

    /**
     * Create packet to request notifications (battery, ear detection, etc.)
     */
    fun createNotificationRequestPacket(): ByteArray {
        return HEADER + byteArrayOf(
            Opcodes.REQUEST_NOTIFICATIONS, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )
    }

    /**
     * Create packet to enable advanced features (conversational awareness, adaptive)
     */
    fun createFeatureFlagsPacket(): ByteArray {
        return HEADER + byteArrayOf(
            Opcodes.SET_FEATURE_FLAGS, 0x00,
            0xD7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
    }

    /**
     * Create packet to change listening/noise control mode
     */
    fun createListeningModePacket(mode: ListeningMode): ByteArray {
        return HEADER + byteArrayOf(
            Opcodes.CONTROL_COMMAND, 0x00,
            ControlIds.LISTENING_MODE,
            mode.value, 0x00, 0x00, 0x00
        )
    }

    /**
     * Create packet to toggle conversational awareness
     */
    fun createConversationalAwarenessPacket(enabled: Boolean): ByteArray {
        val value: Byte = if (enabled) 0x01 else 0x02
        return HEADER + byteArrayOf(
            Opcodes.CONTROL_COMMAND, 0x00,
            ControlIds.CONVERSATION_DETECT_CONFIG,
            value, 0x00, 0x00, 0x00
        )
    }

    /**
     * Create packet to set adaptive audio noise strength (0-100)
     */
    fun createAdaptiveStrengthPacket(level: Int): ByteArray {
        val clampedLevel = level.coerceIn(0, 100)
        return HEADER + byteArrayOf(
            Opcodes.CONTROL_COMMAND, 0x00,
            ControlIds.ADAPTIVE_STRENGTH,
            clampedLevel.toByte(), 0x00, 0x00, 0x00
        )
    }

    /**
     * Create packet to toggle ear detection
     */
    fun createEarDetectionPacket(enabled: Boolean): ByteArray {
        val value: Byte = if (enabled) 0x01 else 0x02
        return HEADER + byteArrayOf(
            Opcodes.CONTROL_COMMAND, 0x00,
            ControlIds.EAR_DETECTION_CONFIG,
            value, 0x00, 0x00, 0x00
        )
    }

    /**
     * Sealed class for parsed packet results
     */
    sealed class ParsedPacket {
        data class Battery(
            val leftLevel: Int?,
            val rightLevel: Int?,
            val caseLevel: Int?,
            val leftCharging: Boolean,
            val rightCharging: Boolean,
            val caseCharging: Boolean
        ) : ParsedPacket()

        data class EarDetection(
            val primaryStatus: EarStatus,
            val secondaryStatus: EarStatus
        ) : ParsedPacket()

        data class ControlCommand(
            val identifier: Byte,
            val value: ByteArray
        ) : ParsedPacket() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is ControlCommand) return false
                return identifier == other.identifier && value.contentEquals(other.value)
            }
            override fun hashCode(): Int = 31 * identifier.toInt() + value.contentHashCode()
        }

        data class ConversationalAwareness(
            val level: Int
        ) : ParsedPacket()

        data class DeviceInfo(
            val name: String,
            val modelNumber: String,
            val manufacturer: String,
            val serialNumber: String
        ) : ParsedPacket()

        data class Unknown(val opcode: Byte, val data: ByteArray) : ParsedPacket()
    }

    /**
     * Parse incoming packet from AirPods
     */
    fun parsePacket(data: ByteArray): ParsedPacket? {
        if (data.size < 6) {
            Log.w(TAG, "Packet too short: ${data.size} bytes")
            return null
        }

        // Verify header
        if (data[0] != 0x04.toByte() || data[2] != 0x04.toByte()) {
            Log.w(TAG, "Invalid packet header")
            return null
        }

        val opcode = data[4]

        return when (opcode) {
            Opcodes.BATTERY_INFO -> parseBatteryPacket(data)
            Opcodes.EAR_DETECTION -> parseEarDetectionPacket(data)
            Opcodes.CONTROL_COMMAND -> parseControlCommandPacket(data)
            Opcodes.CONVERSATION_AWARENESS -> parseConversationalAwarenessPacket(data)
            Opcodes.INFORMATION -> parseDeviceInfoPacket(data)
            else -> {
                Log.d(TAG, "Unknown opcode: ${opcode.toHexString()}")
                ParsedPacket.Unknown(opcode, data)
            }
        }
    }

    private fun parseBatteryPacket(data: ByteArray): ParsedPacket.Battery {
        var leftLevel: Int? = null
        var rightLevel: Int? = null
        var caseLevel: Int? = null
        var leftCharging = false
        var rightCharging = false
        var caseCharging = false

        if (data.size < 7) return ParsedPacket.Battery(null, null, null, false, false, false)

        val count = data[6].toInt()
        var offset = 7

        repeat(count) {
            if (offset + 5 > data.size) return@repeat

            val component = BatteryComponent.fromByte(data[offset])
            val level = data[offset + 2].toInt() and 0xFF
            val status = data[offset + 3].toInt()
            val charging = status == 0x01

            when (component) {
                BatteryComponent.LEFT -> {
                    leftLevel = level
                    leftCharging = charging
                }
                BatteryComponent.RIGHT -> {
                    rightLevel = level
                    rightCharging = charging
                }
                BatteryComponent.CASE -> {
                    caseLevel = level
                    caseCharging = charging
                }
                null -> {}
            }
            offset += 5
        }

        return ParsedPacket.Battery(
            leftLevel, rightLevel, caseLevel,
            leftCharging, rightCharging, caseCharging
        )
    }

    private fun parseEarDetectionPacket(data: ByteArray): ParsedPacket.EarDetection {
        if (data.size < 8) {
            return ParsedPacket.EarDetection(EarStatus.OUT_OF_EAR, EarStatus.OUT_OF_EAR)
        }
        return ParsedPacket.EarDetection(
            primaryStatus = EarStatus.fromByte(data[6]),
            secondaryStatus = EarStatus.fromByte(data[7])
        )
    }

    private fun parseControlCommandPacket(data: ByteArray): ParsedPacket.ControlCommand {
        if (data.size < 7) {
            return ParsedPacket.ControlCommand(0, byteArrayOf())
        }
        val identifier = data[6]
        val value = if (data.size > 7) data.sliceArray(7 until minOf(11, data.size)) else byteArrayOf()
        return ParsedPacket.ControlCommand(identifier, value)
    }

    private fun parseConversationalAwarenessPacket(data: ByteArray): ParsedPacket.ConversationalAwareness {
        val level = if (data.size > 9) data[9].toInt() and 0xFF else 9
        return ParsedPacket.ConversationalAwareness(level)
    }

    private fun parseDeviceInfoPacket(data: ByteArray): ParsedPacket.DeviceInfo {
        val strings = mutableListOf<String>()
        var offset = 6

        while (offset < data.size) {
            // Skip null bytes
            while (offset < data.size && data[offset] == 0x00.toByte()) offset++
            if (offset >= data.size) break

            val start = offset
            while (offset < data.size && data[offset] != 0x00.toByte()) offset++

            if (start < offset) {
                strings.add(String(data, start, offset - start, Charsets.UTF_8))
            }
        }

        return ParsedPacket.DeviceInfo(
            name = strings.getOrNull(0) ?: "",
            modelNumber = strings.getOrNull(1) ?: "",
            manufacturer = strings.getOrNull(2) ?: "",
            serialNumber = strings.getOrNull(3) ?: ""
        )
    }

    private fun Byte.toHexString(): String = "%02X".format(this)
}
