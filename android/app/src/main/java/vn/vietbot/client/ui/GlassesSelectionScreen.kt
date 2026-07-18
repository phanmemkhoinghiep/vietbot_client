package vn.vietbot.client.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import vn.vietbot.client.mcp.SmartGlassesManager
import vn.vietbot.client.mcp.GlassesConnectionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassesSelectionScreen(
    glassesManager: SmartGlassesManager,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val connectionState by glassesManager.connectionState.collectAsState()
    val bondedDevices by glassesManager.bondedGlasses.collectAsState()
    val glassesName by glassesManager.glassesName.collectAsState()

    var selectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }

    // Required Bluetooth permissions for this screen:
    //   - ACCESS_FINE_LOCATION (BLE scan on Android 6-11)
    //   - BLUETOOTH_CONNECT (bondedDevices + connectDirectly on Android 12+)
    //   - BLUETOOTH_SCAN (startScan on Android 12+)
    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
    }

    // Track whether the user has permanently denied any permission — in that case
    // the system will not show the dialog again and we must send them to Settings.
    fun hasBluetoothPermissions(): Boolean =
        requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }

    var hasPermissions by remember { mutableStateOf(hasBluetoothPermissions()) }
    var showPermanentlyDeniedHint by remember { mutableStateOf(false) }

    // ActivityResult launcher for the runtime permission dialog.
    // On Android 13+ the system dialog itself only asks for the permissions passed
    // here; we still re-check after the result because OEM ROMs sometimes silently
    // deny individual permissions.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.all { it }
        hasPermissions = granted || hasBluetoothPermissions()
        Log.i("GlassesSelection", "Permission result: granted=$granted, recomputed=${hasPermissions}")
        if (hasPermissions) {
            showPermanentlyDeniedHint = false
            // Refresh bonded devices now that we have the right to call bondedDevices
            glassesManager.refreshBondedGlasses()
        } else {
            // User denied at least one — keep showing the prompt card.
            // The permanently-denied state (shouldShowRequestPermissionRationale == false
            // after a denial) is handled in the card onClick below.
        }
    }

    // Re-evaluate on resume — covers the case where the user goes to system Settings,
    // toggles the permission, and returns.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = hasBluetoothPermissions()
                if (now && !hasPermissions) {
                    Log.i("GlassesSelection", "Permissions granted via Settings; refreshing")
                    glassesManager.refreshBondedGlasses()
                }
                hasPermissions = now
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isBluetoothOn = remember(hasPermissions) {
        hasPermissions && BluetoothAdapter.getDefaultAdapter()?.isEnabled == true
    }

    // Load bonded devices on launch (only when we already have permissions)
    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            glassesManager.refreshBondedGlasses()
        }
        // Set selected device from saved glasses (this works even without perms,
        // it only reads SharedPreferences)
        val savedName = glassesManager.getSavedGlassesName()
        if (savedName != null) {
            selectedDevice = bondedDevices.find { it.name == savedName }
        }
    }

    fun requestBluetoothPermissions() {
        // If user previously denied with "Don't ask again", the launcher will be
        // a no-op. Detect that via shouldShowRequestPermissionRationale and surface
        // a button that deep-links to app settings instead.
        val activity = context as? android.app.Activity
        val canAsk = activity?.let {
            // If ANY of the required permissions is allowed to be requested, ask.
            // Otherwise the user has permanently denied → send to app settings.
            requiredPermissions.any { perm ->
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(it, perm)
            }
        } ?: true

        if (canAsk) {
            showPermanentlyDeniedHint = false
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        } else {
            showPermanentlyDeniedHint = true
            Log.w("GlassesSelection", "Permissions permanently denied; user must grant via Settings")
        }
    }

    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("GlassesSelection", "Failed to open app settings", e)
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
            // Bluetooth Status / Permission Prompt
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        !hasPermissions -> MaterialTheme.colorScheme.errorContainer
                        isBluetoothOn -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
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
                            imageVector = when {
                                !hasPermissions -> Icons.Filled.BluetoothDisabled
                                isBluetoothOn -> Icons.Filled.BluetoothConnected
                                else -> Icons.Filled.BluetoothDisabled
                            },
                            contentDescription = null,
                            tint = when {
                                !hasPermissions -> MaterialTheme.colorScheme.error
                                isBluetoothOn -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    !hasPermissions -> "Ứng dụng chưa được cấp quyền Bluetooth"
                                    isBluetoothOn -> "Bluetooth đã bật"
                                    else -> "Bluetooth đã tắt"
                                },
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = when {
                                    !hasPermissions -> "Cần quyền Bluetooth & Vị trí để tìm kính đã ghép đôi."
                                    isBluetoothOn -> "${bondedDevices.size} thiết bị đã ghép đôi"
                                    else -> "Bật Bluetooth trong Cài đặt hệ thống để tiếp tục."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!hasPermissions) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { requestBluetoothPermissions() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Cấp quyền Bluetooth")
                        }
                        if (showPermanentlyDeniedHint) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { openAppSettings() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Mở Cài đặt ứng dụng")
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Bạn đã từ chối quyền trước đó. Vui lòng bật thủ công trong Cài đặt > Ứng dụng > VietBot > Quyền.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
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
            if (!hasPermissions) {
                EmptyStateMessage("Cấp quyền Bluetooth để xem danh sách")
            } else if (!isBluetoothOn) {
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