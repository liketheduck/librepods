package com.openpods.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.openpods.app.bluetooth.AirPodsConnectionService
import com.openpods.app.bluetooth.L2CAPManager
import com.openpods.app.data.ListeningMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Activity that handles Google Assistant voice commands for AirPods control
 *
 * Supports commands like:
 * - "Hey Google, turn on noise cancellation on my AirPods"
 * - "Hey Google, enable transparency mode"
 * - "Hey Google, turn off conversational awareness"
 * - "Hey Google, check AirPods battery"
 */
class VoiceCommandActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    companion object {
        private const val TAG = "VoiceCommand"

        // Action constants
        const val ACTION_NOISE_CONTROL = "com.openpods.app.ACTION_NOISE_CONTROL"
        const val ACTION_CONVERSATIONAL_AWARENESS = "com.openpods.app.ACTION_CONVERSATIONAL_AWARENESS"
        const val ACTION_CHECK_BATTERY = "com.openpods.app.ACTION_CHECK_BATTERY"
        const val ACTION_ADAPTIVE_STRENGTH = "com.openpods.app.ACTION_ADAPTIVE_STRENGTH"
        const val ACTION_EAR_DETECTION = "com.openpods.app.ACTION_EAR_DETECTION"

        // Generic actions for natural language
        const val ACTION_TOGGLE_ON = "actions.intent.TOGGLE_ON"
        const val ACTION_TOGGLE_OFF = "actions.intent.TOGGLE_OFF"
        const val ACTION_GET_CHARGE = "actions.intent.GET_CHARGE_LEVEL"
    }

    private var tts: TextToSpeech? = null
    private var service: AirPodsConnectionService? = null
    private var serviceBound = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? AirPodsConnectionService.LocalBinder
            service = localBinder?.getService()
            serviceBound = true
            Log.d(TAG, "Service connected")
            processIntent()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            serviceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "VoiceCommandActivity created with action: ${intent.action}")

        // Initialize TTS for voice feedback
        tts = TextToSpeech(this, this)

        // Start and bind to the service
        val serviceIntent = Intent(this, AirPodsConnectionService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.getDefault()
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS initialization failed")
        }
    }

    private fun processIntent() {
        val action = intent.action ?: return

        Log.d(TAG, "Processing action: $action")
        Log.d(TAG, "Extras: ${intent.extras?.keySet()?.joinToString { "$it=${intent.extras?.get(it)}" }}")

        scope.launch {
            // Give the service a moment to stabilize if just started
            delay(200)

            val result = when (action) {
                ACTION_NOISE_CONTROL, ACTION_TOGGLE_ON, ACTION_TOGGLE_OFF -> handleNoiseControl()
                ACTION_CONVERSATIONAL_AWARENESS -> handleConversationalAwareness()
                ACTION_CHECK_BATTERY, ACTION_GET_CHARGE -> handleBatteryCheck()
                ACTION_ADAPTIVE_STRENGTH -> handleAdaptiveStrength()
                ACTION_EAR_DETECTION -> handleEarDetection()
                else -> handleGenericToggle()
            }

            // Provide feedback
            speak(result)

            // Wait for TTS to complete, then finish
            delay(2500)
            finishAndRemoveTask()
        }
    }

    private suspend fun handleNoiseControl(): String {
        val svc = service ?: return getString(R.string.voice_not_connected)

        if (svc.connectionState.value != L2CAPManager.ConnectionState.CONNECTED) {
            return getString(R.string.voice_not_connected)
        }

        val modeString = intent.getStringExtra("mode")
            ?: intent.getStringExtra("targetDevice.name")
            ?: "noise_cancellation"

        val mode = when {
            modeString.contains("off", ignoreCase = true) -> ListeningMode.OFF
            modeString.contains("transparency", ignoreCase = true) -> ListeningMode.TRANSPARENCY
            modeString.contains("adaptive", ignoreCase = true) -> ListeningMode.ADAPTIVE
            modeString.contains("cancel", ignoreCase = true) -> ListeningMode.NOISE_CANCELLATION
            modeString.contains("anc", ignoreCase = true) -> ListeningMode.NOISE_CANCELLATION
            else -> ListeningMode.NOISE_CANCELLATION
        }

        val success = svc.setListeningMode(mode)

        return if (success) {
            when (mode) {
                ListeningMode.NOISE_CANCELLATION -> getString(R.string.voice_anc_enabled)
                ListeningMode.TRANSPARENCY -> getString(R.string.voice_transparency_enabled)
                ListeningMode.ADAPTIVE -> getString(R.string.voice_adaptive_enabled)
                ListeningMode.OFF -> getString(R.string.voice_noise_off)
                else -> "Mode changed"
            }
        } else {
            getString(R.string.voice_command_failed)
        }
    }

    private suspend fun handleConversationalAwareness(): String {
        val svc = service ?: return getString(R.string.voice_not_connected)

        if (svc.connectionState.value != L2CAPManager.ConnectionState.CONNECTED) {
            return getString(R.string.voice_not_connected)
        }

        val enabled = intent.getStringExtra("enabled")?.toBoolean()
            ?: intent.getBooleanExtra("enabled", true)

        val success = svc.setConversationalAwareness(enabled)

        return if (success) {
            if (enabled) getString(R.string.voice_ca_enabled)
            else getString(R.string.voice_ca_disabled)
        } else {
            getString(R.string.voice_command_failed)
        }
    }

    private fun handleBatteryCheck(): String {
        val svc = service ?: return getString(R.string.voice_not_connected)
        val state = svc.airPodsState.value

        if (!state.isConnected) {
            return getString(R.string.voice_not_connected)
        }

        return buildString {
            append("AirPods battery: ")

            val parts = mutableListOf<String>()
            state.leftBattery?.let { parts.add("Left at $it percent") }
            state.rightBattery?.let { parts.add("Right at $it percent") }
            state.caseBattery?.let { parts.add("Case at $it percent") }

            if (parts.isEmpty()) {
                append("Battery information not available")
            } else {
                append(parts.joinToString(", "))
            }

            // Add charging info
            val charging = mutableListOf<String>()
            if (state.isLeftCharging) charging.add("left")
            if (state.isRightCharging) charging.add("right")
            if (state.isCaseCharging) charging.add("case")

            if (charging.isNotEmpty()) {
                append(". ")
                append(charging.joinToString(" and ").replaceFirstChar { it.uppercase() })
                append(if (charging.size == 1) " is" else " are")
                append(" charging")
            }
        }
    }

    private suspend fun handleAdaptiveStrength(): String {
        val svc = service ?: return getString(R.string.voice_not_connected)

        if (svc.connectionState.value != L2CAPManager.ConnectionState.CONNECTED) {
            return getString(R.string.voice_not_connected)
        }

        val level = intent.getIntExtra("level", 50).coerceIn(0, 100)
        val success = svc.setAdaptiveStrength(level)

        return if (success) {
            "Adaptive strength set to $level percent"
        } else {
            getString(R.string.voice_command_failed)
        }
    }

    private suspend fun handleEarDetection(): String {
        // Ear detection toggle would be implemented here
        // For now, just provide feedback
        return "Ear detection setting not yet available via voice"
    }

    private suspend fun handleGenericToggle(): String {
        // Handle generic toggle intents from Google Assistant
        val targetDevice = intent.getStringExtra("targetDevice.name") ?: ""

        return when {
            targetDevice.contains("noise", ignoreCase = true) ||
            targetDevice.contains("cancel", ignoreCase = true) ||
            targetDevice.contains("anc", ignoreCase = true) -> {
                intent.putExtra("mode", if (intent.action == ACTION_TOGGLE_OFF) "off" else "noise_cancellation")
                handleNoiseControl()
            }

            targetDevice.contains("transparency", ignoreCase = true) ||
            targetDevice.contains("passthrough", ignoreCase = true) ||
            targetDevice.contains("ambient", ignoreCase = true) -> {
                intent.putExtra("mode", "transparency")
                handleNoiseControl()
            }

            targetDevice.contains("adaptive", ignoreCase = true) -> {
                intent.putExtra("mode", "adaptive")
                handleNoiseControl()
            }

            targetDevice.contains("conversation", ignoreCase = true) ||
            targetDevice.contains("awareness", ignoreCase = true) -> {
                intent.putExtra("enabled", intent.action != ACTION_TOGGLE_OFF)
                handleConversationalAwareness()
            }

            else -> {
                Log.d(TAG, "Unknown target device: $targetDevice")
                "I'm not sure what setting you want to change. Try saying noise cancellation, transparency, or adaptive mode."
            }
        }
    }

    private fun speak(text: String) {
        Log.d(TAG, "Speaking: $text")

        // Show toast as visual feedback
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

        // Speak the result
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "voice_response")
    }

    override fun onDestroy() {
        super.onDestroy()

        tts?.stop()
        tts?.shutdown()

        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }

        scope.cancel()
    }
}
