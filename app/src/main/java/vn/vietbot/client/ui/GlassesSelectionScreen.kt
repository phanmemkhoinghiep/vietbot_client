package vn.vietbot.client.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.core.content.ContextCompat
import vn.vietbot.client.mcp.SmartGlassesManager
import vn.vietbot.client.mcp.GlassesConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesSelectionScreen(
    glassesManager: SmartGlassesManager,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val connectionState by glassesManager.connectionState.collectAsState()
    val bondedDevices by glassesManager.bondedGlasses.collectAsState()
    val glassesName by glassesManager.glassesName.collectAsState()

    var isBluetoothEnabled by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

    // Load bonded devices on launch
    LaunchedEffect(Unit) {
        isBluetoothEnabled = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED &&
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

        if (isBluetoothEnabled) {
            glassesManager.refreshBondedGlasses()
        }

        // Set selected device from saved glasses
        val savedName = glassesManager.getSavedGlassesName()
        if (savedName != null) {
            selectedDevice = bondedDevices.find { it.name == savedName }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chọn kính Smart") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        glassesManager.refreshBondedGlasses()
                        // Reset selection if saved glasses not in list
                        val savedName = glassesManager.getSavedGlassesName()
                        if (savedName != null && !bondedDevices.any { it.name == savedName }) {
                            selectedDevice = null
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
        ) {
            // Bluetooth Status
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
                        imageVector = if (isBluetoothEnabled)
                            Icons.Filled.BluetoothConnected
                        else
                            Icons.Filled.BluetoothDisabled,
                        contentDescription = null,
                        tint = if (isBluetoothEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = if (isBluetoothEnabled) "Bluetooth đã bật" else "Bluetooth đã tắt",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (isBluetoothEnabled) {
                            Text(
                                text = "${bondedDevices.size} thiết bị đã ghép đôi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current Glasses Status
            val isCurrentlyConnected = connectionState == GlassesConnectionState.CONNECTED
            if (isCurrentlyConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.BluetoothConnected,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Đã kết nối: ${glassesName ?: "Kính"}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Sẵn sàng sử dụng camera kính",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Device List Title
            Text(
                text = if (isCurrentlyConnected)
                    "Hoặc chọn kính khác:"
                else
                    "Danh sách thiết bị đã ghép đôi:",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Device List
            if (!isBluetoothEnabled) {
                EmptyStateMessage("Bật Bluetooth để xem thiết bị")
            } else if (bondedDevices.isEmpty()) {
                EmptyStateMessage(
                    "Không có thiết bị nào đã ghép đôi.\n" +
                    "Vào Cài đặt > Bluetooth để ghép kính."
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(bondedDevices) { device ->
                        val isCurrentlyConnectedDevice = isCurrentlyConnected && glassesName == device.name
                        val isSelected = selectedDevice?.address == device.address

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Select device as glasses
                                    selectedDevice = device
                                    glassesManager.setAsGlasses(device)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isCurrentlyConnectedDevice ->
                                        MaterialTheme.colorScheme.primaryContainer
                                    isSelected ->
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    else ->
                                        MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (isCurrentlyConnectedDevice)
                                        Icons.Filled.BluetoothConnected
                                    else
                                        Icons.Filled.Bluetooth,
                                    contentDescription = null,
                                    tint = if (isCurrentlyConnectedDevice)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name ?: "Thiết bị không tên",
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = device.address ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                when {
                                    isCurrentlyConnectedDevice -> {
                                        Text(
                                            text = "Đã kết nối",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    isSelected -> {
                                        Text(
                                            text = "Đã chọn ✓",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    else -> {
                                        Icon(
                                            Icons.Filled.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Selected Device Info
            if (selectedDevice != null) {
                val wasNotConnectedBefore = !isCurrentlyConnected
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Đã chọn: ${selectedDevice?.name ?: "Thiết bị"}",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (wasNotConnectedBefore)
                                "Kính đã được chọn. Vào Cài đặt > Nguồn Camera > Chọn 'Kính' để sử dụng."
                            else
                                "Kính đã được cập nhật làm thiết bị mặc định.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "← Quay lại Cài đặt để tiếp tục",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Help
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Hướng dẫn",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "1. Vào Cài đặt > Bluetooth để ghép kính Smart\n" +
                               "2. Chọn kính trong danh sách trên\n" +
                               "3. Quay lại Cài đặt > Chọn 'Kính' làm nguồn camera",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}