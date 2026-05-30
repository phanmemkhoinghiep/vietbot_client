package vn.vietbot.client

import android.Manifest
import android.app.Activity
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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.mcp.GlassesConnectionState
import vn.vietbot.client.ui.ChatViewMode
import vn.vietbot.client.ui.MainScreen
import vn.vietbot.client.ui.theme.VietbotTheme


@AndroidEntryPoint
class MainActivity : ComponentActivity(), LifecycleOwner {

    private val chatViewModel: ChatViewMode by viewModels()
    private var lifecycleObserver: LifecycleEventObserver? = null

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissions.forEach { (permission, granted) ->
            Log.d("MainActivity", "$permission: $granted")
            if (permission == Manifest.permission.ACCESS_FINE_LOCATION) {
                if (granted) {
                    Log.i("MainActivity", "Location permission granted - MAC address can be read")
                } else {
                    Log.w("MainActivity", "Location permission denied - MAC address may not be available")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate started")

        enableEdgeToEdge()

        // Request location permission for MAC address access
        requestLocationPermissionIfNeeded()

        // Request Bluetooth permissions for Android 12+
        requestBluetoothPermissionsIfNeeded()

        // Initialize HeyCyan Glasses Manager
        initializeGlassesManager()

        // Register lifecycle observer for recording safety
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
        // Stop all recording operations immediately
        val mcpServer = chatViewModel.getMcpServer()
        mcpServer.stopAllRecordingImmediate()
        Log.i("MainActivity", "Stopped all recording due to app background")

        // Disconnect WebSocket
        if (chatViewModel.isConnected.value) {
            Log.i("MainActivity", "Disconnecting WebSocket due to app background")
            chatViewModel.disconnect()
            Toast.makeText(this, "Đã dừng ghi âm/video và ngắt kết nối do ứng dụng chuyển sang nền", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                Log.d("MainActivity", "Location permission already granted")
            }
            shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                Log.d("MainActivity", "Showing permission rationale for location")
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            }
            else -> {
                Log.d("MainActivity", "Requesting location permission")
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
        }
    }

    private fun initializeGlassesManager() {
        try {
            val entryPoint = EntryPointAccessors.fromActivity(this, GlassesManagerEntryPoint::class.java)
            val glassesManager = entryPoint.getHeyCyanGlassesManager()
            glassesManager.initialize(this)
            Log.i("MainActivity", "HeyCyanGlassesManager initialized")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize GlassesManager: ${e.message}")
        }
    }
}
