package com.openpods.app.data

/**
 * Represents the current state of connected AirPods
 */
data class AirPodsState(
    val isConnected: Boolean = false,
    val isL2capConnected: Boolean = false,
    val name: String = "AirPods",
    val model: AirPodsModel = AirPodsModel.UNKNOWN,
    val leftBattery: Int? = null,
    val rightBattery: Int? = null,
    val caseBattery: Int? = null,
    val isLeftCharging: Boolean = false,
    val isRightCharging: Boolean = false,
    val isCaseCharging: Boolean = false,
    val isLeftInEar: Boolean = false,
    val isRightInEar: Boolean = false,
    val listeningMode: ListeningMode = ListeningMode.UNKNOWN,
    val conversationalAwareness: Boolean = false,
    val adaptiveStrength: Int = 50,
    val macAddress: String = "",
    val color: String = "White"
)

/**
 * AirPods models with their capabilities
 */
enum class AirPodsModel(
    val displayName: String,
    val modelId: Int,
    val hasANC: Boolean = false,
    val hasTransparency: Boolean = false,
    val hasAdaptive: Boolean = false,
    val hasConversationalAwareness: Boolean = false,
    val hasHearingAid: Boolean = false
) {
    UNKNOWN("Unknown AirPods", 0x0000),
    AIRPODS_1("AirPods (1st Gen)", 0x0220),
    AIRPODS_2("AirPods (2nd Gen)", 0x0F20),
    AIRPODS_3("AirPods (3rd Gen)", 0x1320),
    AIRPODS_4("AirPods 4", 0x1920),
    AIRPODS_4_ANC("AirPods 4 (ANC)", 0x1B20, hasANC = true, hasTransparency = true, hasAdaptive = true, hasConversationalAwareness = true),
    AIRPODS_PRO("AirPods Pro", 0x0E20, hasANC = true, hasTransparency = true),
    AIRPODS_PRO_2("AirPods Pro 2", 0x1420, hasANC = true, hasTransparency = true, hasAdaptive = true, hasConversationalAwareness = true, hasHearingAid = true),
    AIRPODS_PRO_2_USBC("AirPods Pro 2 (USB-C)", 0x2420, hasANC = true, hasTransparency = true, hasAdaptive = true, hasConversationalAwareness = true, hasHearingAid = true),
    AIRPODS_MAX("AirPods Max", 0x0A20, hasANC = true, hasTransparency = true, hasAdaptive = true, hasConversationalAwareness = true),
    AIRPODS_MAX_USBC("AirPods Max (USB-C)", 0x1F20, hasANC = true, hasTransparency = true, hasAdaptive = true, hasConversationalAwareness = true);

    companion object {
        fun fromModelId(id: Int): AirPodsModel {
            return entries.find { it.modelId == id } ?: UNKNOWN
        }
    }
}

/**
 * Listening/Noise control modes
 */
enum class ListeningMode(val value: Byte, val displayName: String) {
    OFF(0x01, "Off"),
    NOISE_CANCELLATION(0x02, "Noise Cancellation"),
    TRANSPARENCY(0x03, "Transparency"),
    ADAPTIVE(0x04, "Adaptive"),
    UNKNOWN(0x00, "Unknown");

    companion object {
        fun fromByte(value: Byte): ListeningMode {
            return entries.find { it.value == value } ?: UNKNOWN
        }
    }
}

/**
 * Battery component types
 */
enum class BatteryComponent(val value: Byte) {
    LEFT(0x04),
    RIGHT(0x02),
    CASE(0x08);

    companion object {
        fun fromByte(value: Byte): BatteryComponent? {
            return entries.find { it.value == value }
        }
    }
}

/**
 * Ear detection status
 */
enum class EarStatus(val value: Byte) {
    IN_EAR(0x00),
    OUT_OF_EAR(0x01),
    IN_CASE(0x02);

    companion object {
        fun fromByte(value: Byte): EarStatus {
            return entries.find { it.value == value } ?: OUT_OF_EAR
        }
    }
}
