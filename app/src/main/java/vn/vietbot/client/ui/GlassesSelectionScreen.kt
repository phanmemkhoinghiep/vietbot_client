package vn.vietbot.client.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Battery6Bar
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import vn.vietbot.client.mcp.GlassesConnectionState
import vn.vietbot.client.mcp.HeyCyanGlassesManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesSelectionScreen(
    glassesManager: HeyCyanGlassesManager,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val connectionState by glassesManager.connectionState.collectAsState()
    val bondedGlasses by glassesManager.bondedGlasses.collectAsState()
    val batteryLevel by glassesManager.batteryLevel.collectAsState()
    val glassesName by glassesManager.glassesName.collectAsState()

    var isBluetoothEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        glassesManager.refreshBondedGlasses()
        val adapter = BluetoothAdapter.getDefaultAdapter()
        isBluetoothEnabled = adapter?.isEnabled == true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kết nối kính HeyCyan") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Bluetooth status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isBluetoothEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isBluetoothEnabled) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothDisabled,
                        contentDescription = "Bluetooth",
                        tint = if (isBluetoothEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBluetoothEnabled) "Bluetooth đã bật" else "Bluetooth đã tắt",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!isBluetoothEnabled) {
                            Text(
                                text = "Bật Bluetooth trong Cài đặt để kết nối kính",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Connection status card
            if (connectionState == GlassesConnectionState.CONNECTED) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.BluetoothConnected,
                                contentDescription = "Connected",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Đã kết nối: ${glassesName ?: "Kính"}",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Battery6Bar,
                                contentDescription = "Battery",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (batteryLevel >= 0) "Pin: $batteryLevel%" else "Pin: Không xác định",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { glassesManager.disconnectFromGlasses() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Ngắt kết nối")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Glasses list
            Text(
                text = "Kính đã ghép đôi",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!isBluetoothEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Bật Bluetooth để xem kính đã ghép đôi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (bondedGlasses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🔍",
                            style = MaterialTheme.typography.headlineLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Không tìm thấy kính HeyCyan đã ghép đôi",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Ghép đôi kính trong Cài đặt > Bluetooth",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(bondedGlasses) { device ->
                        GlassesDeviceItem(
                            device = device,
                            isConnecting = connectionState == GlassesConnectionState.CONNECTING,
                            isConnected = connectionState == GlassesConnectionState.CONNECTED && glassesName == device.name,
                            onClick = {
                                if (connectionState != GlassesConnectionState.CONNECTING) {
                                    glassesManager.connectToGlasses(device)
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Help text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp)
                ) {
                    Text(
                        text = "💡 Hướng dẫn",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "1. Ghép đôi kính HeyCyan trong Cài đặt > Bluetooth\n2. Chọn kính trong danh sách để kết nối\n3. Sau khi kết nối, có thể chọn 'Kính' trong Cài đặt để dùng camera kính",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun GlassesDeviceItem(
    device: BluetoothDevice,
    isConnecting: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isConnecting && !isConnected, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = "Bluetooth",
                tint = if (isConnected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: "Kính không tên",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = device.address ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                isConnecting -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                }
                isConnected -> {
                    Text(
                        text = "✓",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = "Connect",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}