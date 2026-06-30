package vn.vietbot.client

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import vn.vietbot.client.mcp.SmartGlassesManager
import vn.vietbot.client.ui.ChatViewMode
import vn.vietbot.client.ui.MainScreen
import vn.vietbot.client.ui.theme.VietbotTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity(), LifecycleOwner {

    private val chatViewModel: ChatViewMode by viewModels()
    private var lifecycleObserver: LifecycleEventObserver? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, granted) ->
            Log.d("MainActivity", "$permission: $granted")
        }
        // OPTION A: Defer glasses init until permissions are granted.
        // On Android 12+, BluetoothAdapter.bondedDevices requires BLUETOOTH_CONNECT
        // runtime permission. Calling initialize() before grant caused SecurityException
        // or empty bonded list, breaking HeyCyan glasses connection.
        if (allGlassesPermissionsGranted()) {
            Log.i("MainActivity", "All glasses permissions granted, initializing GlassesManager")
            initializeGlassesManager()
        } else {
            Log.w("MainActivity", "Some glasses permissions denied, GlassesManager not initialized")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")

        enableEdgeToEdge()

        // Request all critical runtime permissions upfront
        requestCriticalPermissions()

        // Request location + bluetooth for glasses
        requestLocationPermissionIfNeeded()
        val btGranted = requestBluetoothPermissionsIfNeeded()

        // OPTION A: Only init glasses if permissions already granted.
        // Otherwise permissionLauncher callback above will init when user grants.
        if (allGlassesPermissionsGranted()) {
            Log.i("MainActivity", "Permissions pre-granted, initializing GlassesManager now")
            initializeGlassesManager()
        }

        registerLifecycleObserver()

        val entryPoint = EntryPointAccessors.fromActivity(this, NavigationEntryPoint::class.java)
        val settingsRepository = entryPoint.getSettingsRepository()

        setContent {
            VietbotTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        settingsRepository = settingsRepository,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        Log.d("MainActivity", "onCreate finished")
    }

    private fun registerLifecycleObserver() {
        lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    Log.i("MainActivity", "App going to background")
                    handleAppBackground()
                }
                Lifecycle.Event.ON_RESUME -> {
                    Log.i("MainActivity", "App resumed")
                    // Refresh bonded glasses list
                    SmartGlassesManager.getInstanceOrNull()?.refreshBondedGlasses()
                    // Note: recording is NOT auto-restarted on resume.
                    // User must tap mic button to start recording.
                    // WebSocket stays alive even when background, so voice commands
                    // can still be received when user returns to foreground.
                }
                Lifecycle.Event.ON_STOP -> {
                    Log.i("MainActivity", "App stopped")
                }
                else -> { }
            }
        }
        lifecycle?.addObserver(lifecycleObserver!!)
    }

    private fun handleAppBackground() {
        val mcpServer = chatViewModel.getMcpServer()
        mcpServer.stopAllRecordingImmediate()
        Log.i("MainActivity", "Stopped all recording due to app background")

        // Keep WebSocket connection alive when background — only stop recording.
        // This allows user to receive voice commands via MCP tools (e.g. stop YouTube)
        // when Vietbot is in background but another app (YouTube) is active.
        // WS disconnect only happens on explicit user action or timeout.
        if (chatViewModel.isConnected.value) {
            Log.i("MainActivity", "WebSocket kept alive in background — recording stopped only")
        }
    }

    /**
     * Request all critical runtime permissions needed for the app to function:
     * - RECORD_AUDIO: voice chat (core feature)
     * - CAMERA: photo/video capture for vision AI
     * - READ_MEDIA_IMAGES + READ_MEDIA_VIDEO (Android 13+): access saved media
     */
    private fun requestCriticalPermissions() {
        val critical = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            critical.add(Manifest.permission.READ_MEDIA_IMAGES)
            critical.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        val notGranted = critical.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            Log.d("MainActivity", "Requesting critical permissions: $notGranted")
            permissionLauncher.launch(notGranted.toTypedArray())
        } else {
            Log.d("MainActivity", "Critical permissions already granted")
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "Location permission already granted")
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
            else -> {
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }

    /**
     * Check if all runtime permissions needed for BLE glasses operation are granted.
     * - ACCESS_FINE_LOCATION: required for BLE scan on Android 6-11
     * - BLUETOOTH_CONNECT: required for bondedDevices / connectDirectly on Android 12+
     * - BLUETOOTH_SCAN: required for startScan on Android 12+
     */
    private fun allGlassesPermissionsGranted(): Boolean {
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
            required.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissions = arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
            val notGranted = permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (notGranted.isNotEmpty()) {
                Log.d("MainActivity", "Requesting Bluetooth permissions: $notGranted")
                permissionLauncher.launch(notGranted.toTypedArray())
            } else {
                Log.d("MainActivity", "Bluetooth permissions already granted")
            }
        } else {
            Log.d("MainActivity", "Pre-Android 12, no BT permissions needed")
        }
    }

    private fun initializeGlassesManager() {
        try {
            val entryPoint = EntryPointAccessors.fromActivity(this, GlassesManagerEntryPoint::class.java)
            val glassesManager = entryPoint.getSmartGlassesManager()

            // Check if SDK is truly initialized (not just the instance created)
            if (glassesManager.isSdkInitialized()) {
                Log.i("MainActivity", "SmartGlassesManager SDK already initialized, skipping")
            } else {
                glassesManager.initialize(this)
                Log.i("MainActivity", "SmartGlassesManager initialized")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize GlassesManager: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up glasses manager
        SmartGlassesManager.getInstanceOrNull()?.destroy()
    }
}
