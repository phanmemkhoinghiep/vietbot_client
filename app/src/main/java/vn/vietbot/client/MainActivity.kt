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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")

        enableEdgeToEdge()

        requestLocationPermissionIfNeeded()
        val btGranted = requestBluetoothPermissionsIfNeeded()

        // Initialize Smart Glasses Manager after permissions are requested
        // The SDK will handle permission checks internally
        initializeGlassesManager()

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

        if (chatViewModel.isConnected.value) {
            Log.i("MainActivity", "Disconnecting WebSocket due to app background")
            chatViewModel.disconnect()
            Toast.makeText(this, "Đã dừng ghi âm/video và ngắt kết nối do ứng dụng chuyển sang nền", Toast.LENGTH_SHORT).show()
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
