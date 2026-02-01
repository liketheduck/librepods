package com.openpods.app.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openpods.app.bluetooth.AirPodsConnectionService
import com.openpods.app.bluetooth.L2CAPManager
import com.openpods.app.data.AirPodsState
import com.openpods.app.data.ListeningMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    service: AirPodsConnectionService?,
    onRequestPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val airPodsState by service?.airPodsState?.collectAsState() ?: remember { mutableStateOf(AirPodsState()) }
    val connectionState by service?.connectionState?.collectAsState() ?: remember { mutableStateOf(L2CAPManager.ConnectionState.DISCONNECTED) }
    val errorMessage by service?.errorMessage?.collectAsState() ?: remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = if (airPodsState.isConnected) airPodsState.name else "OpenPods",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = onRequestPermissions) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Connection Status Card
            ConnectionStatusCard(
                state = airPodsState,
                connectionState = connectionState,
                errorMessage = errorMessage,
                onConnect = {
                    scope.launch {
                        val device = findAirPodsDevice(context, airPodsState.macAddress)
                        device?.let { service?.connectL2cap(it) }
                    }
                },
                onDisconnect = {
                    service?.disconnectL2cap()
                }
            )

            // Battery Card
            AnimatedVisibility(visible = airPodsState.isConnected) {
                BatteryCard(state = airPodsState)
            }

            // Noise Control Card
            AnimatedVisibility(
                visible = airPodsState.isL2capConnected && airPodsState.model.hasANC
            ) {
                NoiseControlCard(
                    currentMode = airPodsState.listeningMode,
                    hasAdaptive = airPodsState.model.hasAdaptive,
                    onModeChange = { mode ->
                        scope.launch {
                            service?.setListeningMode(mode)
                        }
                    }
                )
            }

            // Adaptive Strength Slider
            AnimatedVisibility(
                visible = airPodsState.isL2capConnected &&
                         airPodsState.model.hasAdaptive &&
                         airPodsState.listeningMode == ListeningMode.ADAPTIVE
            ) {
                AdaptiveStrengthCard(
                    currentStrength = airPodsState.adaptiveStrength,
                    onStrengthChange = { level ->
                        scope.launch {
                            service?.setAdaptiveStrength(level)
                        }
                    }
                )
            }

            // Conversational Awareness Card
            AnimatedVisibility(
                visible = airPodsState.isL2capConnected && airPodsState.model.hasConversationalAwareness
            ) {
                FeatureToggleCard(
                    title = "Conversational Awareness",
                    description = "Automatically lowers audio and enhances voices when you start speaking",
                    icon = Icons.Default.RecordVoiceOver,
                    enabled = airPodsState.conversationalAwareness,
                    onToggle = { enabled ->
                        scope.launch {
                            service?.setConversationalAwareness(enabled)
                        }
                    }
                )
            }

            // L2CAP Not Available Message
            AnimatedVisibility(
                visible = airPodsState.isConnected && !airPodsState.isL2capConnected &&
                         connectionState == L2CAPManager.ConnectionState.FAILED
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                "Limited Functionality",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            "L2CAP connection failed. Battery info is available via BLE, " +
                            "but noise control features require L2CAP which may not be " +
                            "supported on your device without root.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        errorMessage?.let {
                            Text(
                                "Error: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // Voice Command Info
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            "Voice Commands",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Text(
                        "Try saying:\n" +
                        "• \"Hey Google, turn on noise cancellation\"\n" +
                        "• \"Hey Google, enable transparency mode\"\n" +
                        "• \"Hey Google, check AirPods battery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    state: AirPodsState,
    connectionState: L2CAPManager.ConnectionState,
    errorMessage: String?,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                state.isL2capConnected -> MaterialTheme.colorScheme.primaryContainer
                state.isConnected -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = when {
                    state.isL2capConnected -> Icons.Default.BluetoothConnected
                    state.isConnected -> Icons.Default.BluetoothSearching
                    else -> Icons.Default.BluetoothDisabled
                },
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = when {
                    state.isL2capConnected -> MaterialTheme.colorScheme.onPrimaryContainer
                    state.isConnected -> MaterialTheme.colorScheme.onTertiaryContainer
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Text(
                text = when {
                    connectionState == L2CAPManager.ConnectionState.CONNECTING -> "Connecting..."
                    state.isL2capConnected -> "Connected (Full Control)"
                    state.isConnected -> "BLE Connected (Limited)"
                    else -> "Scanning for AirPods..."
                },
                style = MaterialTheme.typography.titleMedium
            )

            if (state.isConnected) {
                Text(
                    text = "${state.model.displayName} • ${state.color}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Connect/Disconnect button
            if (state.isConnected && !state.isL2capConnected &&
                connectionState != L2CAPManager.ConnectionState.CONNECTING) {
                FilledTonalButton(onClick = onConnect) {
                    Text("Try L2CAP Connection")
                }
            } else if (state.isL2capConnected) {
                OutlinedButton(onClick = onDisconnect) {
                    Text("Disconnect Control")
                }
            }
        }
    }
}

@Composable
fun BatteryCard(state: AirPodsState) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Battery",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                state.leftBattery?.let {
                    BatteryItem(
                        label = "Left",
                        percentage = it,
                        isCharging = state.isLeftCharging,
                        isInEar = state.isLeftInEar
                    )
                }
                state.rightBattery?.let {
                    BatteryItem(
                        label = "Right",
                        percentage = it,
                        isCharging = state.isRightCharging,
                        isInEar = state.isRightInEar
                    )
                }
                state.caseBattery?.let {
                    BatteryItem(
                        label = "Case",
                        percentage = it,
                        isCharging = state.isCaseCharging,
                        isInEar = false
                    )
                }
            }
        }
    }
}

@Composable
fun BatteryItem(
    label: String,
    percentage: Int,
    isCharging: Boolean,
    isInEar: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(
                    when {
                        percentage > 50 -> Color(0xFF4CAF50).copy(alpha = 0.2f)
                        percentage > 20 -> Color(0xFFFFC107).copy(alpha = 0.2f)
                        else -> Color(0xFFF44336).copy(alpha = 0.2f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "$percentage%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (isCharging) {
                    Icon(
                        Icons.Default.BatteryChargingFull,
                        contentDescription = "Charging",
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF4CAF50)
                    )
                }
            }
        }

        Text(
            label,
            style = MaterialTheme.typography.bodySmall
        )

        if (isInEar) {
            Text(
                "In Ear",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun NoiseControlCard(
    currentMode: ListeningMode,
    hasAdaptive: Boolean,
    onModeChange: (ListeningMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Noise Control",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                NoiseControlButton(
                    icon = Icons.Default.VolumeOff,
                    label = "Off",
                    selected = currentMode == ListeningMode.OFF,
                    onClick = { onModeChange(ListeningMode.OFF) }
                )
                NoiseControlButton(
                    icon = Icons.Default.NoiseAware,
                    label = "ANC",
                    selected = currentMode == ListeningMode.NOISE_CANCELLATION,
                    onClick = { onModeChange(ListeningMode.NOISE_CANCELLATION) }
                )
                NoiseControlButton(
                    icon = Icons.Default.HearingDisabled,
                    label = "Transparency",
                    selected = currentMode == ListeningMode.TRANSPARENCY,
                    onClick = { onModeChange(ListeningMode.TRANSPARENCY) }
                )
                if (hasAdaptive) {
                    NoiseControlButton(
                        icon = Icons.Default.AutoAwesome,
                        label = "Adaptive",
                        selected = currentMode == ListeningMode.ADAPTIVE,
                        onClick = { onModeChange(ListeningMode.ADAPTIVE) }
                    )
                }
            }
        }
    }
}

@Composable
fun NoiseControlButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
        label = "backgroundColor"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(backgroundColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun AdaptiveStrengthCard(
    currentStrength: Int,
    onStrengthChange: (Int) -> Unit
) {
    var sliderValue by remember { mutableFloatStateOf(currentStrength.toFloat()) }

    LaunchedEffect(currentStrength) {
        sliderValue = currentStrength.toFloat()
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Adaptive Noise Level",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${sliderValue.toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "More\nNoise",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = { onStrengthChange(sliderValue.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Less\nNoise",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun FeatureToggleCard(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@SuppressLint("MissingPermission")
private fun findAirPodsDevice(context: Context, macAddress: String): BluetoothDevice? {
    if (macAddress.isEmpty()) return null

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val adapter = bluetoothManager?.adapter ?: return null

    return adapter.bondedDevices.find { it.address == macAddress }
}
