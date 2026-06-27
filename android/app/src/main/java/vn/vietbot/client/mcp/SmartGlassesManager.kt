package vn.vietbot.client.mcp

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import vn.vietbot.client.data.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

enum class GlassesConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}

@Singleton
class SmartGlassesManager @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    private val _connectionState = MutableStateFlow(GlassesConnectionState.DISCONNECTED)
    val connectionState: StateFlow<GlassesConnectionState> = _connectionState.asStateFlow()

    private val _bondedGlasses = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bondedGlasses: StateFlow<List<BluetoothDevice>> = _bondedGlasses.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _glassesName = MutableStateFlow<String?>(null)
    val glassesName: StateFlow<String?> = _glassesName.asStateFlow()

    // Scanned devices from BLE scan
    private val _scannedGlasses = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scannedGlasses: StateFlow<List<BluetoothDevice>> = _scannedGlasses.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var application: Application? = null

    // Callback receiver for BLE connection events - giống sample app
    private val bluetoothCallbackReceiver = object : QCBluetoothCallbackCloneReceiver() {
        override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
            Log.i(TAG, "connectStatue: device=${device?.name}, connected=$connected")
            if (device != null && connected) {
                if (device.name != null) {
                    DeviceManager.getInstance().deviceName = device.name
                    _glassesName.value = device.name
                    settingsRepository.glassesName = device.name
                }
                _connectionState.value = GlassesConnectionState.CONNECTED
                settingsRepository.isGlassesConnected = true
                Log.i(TAG, "Glasses connected via callback")
            } else {
                _connectionState.value = GlassesConnectionState.DISCONNECTED
                settingsRepository.isGlassesConnected = false
                Log.i(TAG, "Glasses disconnected via callback")
            }
        }

        override fun onServiceDiscovered() {
            Log.i(TAG, "onServiceDiscovered - BLE service ready")
            // Must receive this callback before sending other commands
            LargeDataHandler.getInstance().initEnable()
            BleOperateManager.getInstance().isReady = true
            _connectionState.value = GlassesConnectionState.CONNECTED
            settingsRepository.isGlassesConnected = true
            Log.i(TAG, "BLE service discovered, glasses ready")
        }

        override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
            // Not used
        }

        override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {
            // Not used
        }
    }

    // Device notify listener for glasses events - giống sample app
    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val type = response.loadData.getOrNull(6)?.toInt() ?: return
            Log.d(TAG, "DeviceNotify: cmdType=$cmdType, type=0x${type.toString(16)}")
            when (type) {
                // Battery level (0x05)
                0x05 -> {
                    val battery = response.loadData.getOrNull(7)?.toInt() ?: return
                    _batteryLevel.value = battery
                    settingsRepository.glassesBatteryLevel = battery
                    Log.d(TAG, "Battery level: $battery%")
                }
                // AI photo / shortcut trigger (0x02)
                0x02 -> {
                    Log.d(TAG, "AI photo / shortcut trigger received")
                    onGlassesPhotoEvent()
                }
                // Glasses start playing audio (0x03)
                0x03 -> {
                    if (response.loadData.getOrNull(7)?.toInt() == 1) {
                        Log.d(TAG, "Glasses started audio playback")
                    }
                }
                // OTA upgrade (0x04)
                0x04 -> {
                    try {
                        val download = response.loadData.getOrNull(7)?.toInt() ?: 0
                        val soc = response.loadData.getOrNull(8)?.toInt() ?: 0
                        val nor = response.loadData.getOrNull(9)?.toInt() ?: 0
                        Log.d(TAG, "OTA progress: dl=$download soc=$soc nor=$nor")
                    } catch (e: Exception) {
                        Log.e(TAG, "OTA parse error: ${e.message}")
                    }
                }
                // Pause event (0x0c)
                0x0c -> {
                    if (response.loadData.getOrNull(7)?.toInt() == 1) {
                        Log.d(TAG, "Glasses pause event")
                    }
                }
                // Disconnect event (0x0D)
                0x0D -> {
                    if (response.loadData.getOrNull(7)?.toInt() == 1) {
                        Log.d(TAG, "Glasses disconnect event")
                        _connectionState.value = GlassesConnectionState.DISCONNECTED
                        settingsRepository.isGlassesConnected = false
                    }
                }
                // Memory full warning (0x0e)
                0x0e -> {
                    Log.w(TAG, "Glasses memory full warning")
                }
                // Translation pause event (0x10)
                0x10 -> {
                    Log.d(TAG, "Translation pause event")
                }
                // Volume change event (0x12)
                0x12 -> {
                    try {
                        val currMusic = response.loadData.getOrNull(10)?.toInt() ?: 0
                        val currCall = response.loadData.getOrNull(14)?.toInt() ?: 0
                        val currSys = response.loadData.getOrNull(18)?.toInt() ?: 0
                        Log.d(TAG, "Volume: music=$currMusic call=$currCall system=$currSys")
                    } catch (e: Exception) {
                        Log.e(TAG, "Volume parse error: ${e.message}")
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown event type: 0x${type.toString(16)}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize(context: Context) {
        Log.i(TAG, "Initializing SmartGlassesManager...")

        // Get Application context - giống sample app
        application = context.applicationContext as? Application
            ?: (context as? Application)
            ?: throw IllegalStateException("Context must be an Application")

        // Initialize BLE SDK - theo sample app
        BleBaseControl.getInstance(application).setmContext(application)

        // Initialize LargeDataHandler and BleOperateManager - giống sample app
        LargeDataHandler.getInstance()
        BleOperateManager.getInstance(application)
        BleOperateManager.getInstance().setApplication(application)
        BleOperateManager.getInstance().init()

        // Register device notify listener for thumbnails/AI photo - giống sample app
        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)

        // Register BLE callback receiver via LocalBroadcastManager (critical for onServiceDiscovered)
        registerBluetoothCallback()

        // Register BLE state receiver (for Bluetooth ON/OFF events)
        registerBluetoothStateReceiver()

        // Scan for bonded glasses
        refreshBondedGlasses()

        // Auto connect to saved glasses only (not auto-scan) - user chooses in UI
        val savedAddress = settingsRepository.glassesAddress
        val savedName = settingsRepository.glassesName
        if (!savedAddress.isNullOrEmpty() && !savedName.isNullOrEmpty()) {
            Log.i(TAG, "Auto connecting to saved glasses: $savedName ($savedAddress)")
            _connectionState.value = GlassesConnectionState.CONNECTING
            DeviceManager.getInstance().setDeviceAddress(savedAddress)
            DeviceManager.getInstance().setDeviceName(savedName)
            BleOperateManager.getInstance().connectDirectly(savedAddress)

            // Set timeout - disconnect after 10 seconds if no connection
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (_connectionState.value == GlassesConnectionState.CONNECTING) {
                    Log.w(TAG, "Connection timeout, resetting state")
                    _connectionState.value = GlassesConnectionState.DISCONNECTED
                    settingsRepository.isGlassesConnected = false
                }
            }, 10000)
        }

        Log.i(TAG, "SmartGlassesManager initialized")
        sdkInitialized = true
    }

    private fun registerBluetoothCallback() {
        val app = application ?: return
        val intentFilter = BleAction.getIntentFilter()
        try {
            LocalBroadcastManager.getInstance(app).registerReceiver(bluetoothCallbackReceiver, intentFilter)
            Log.i(TAG, "Bluetooth callback receiver registered via LocalBroadcastManager")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bluetooth callback receiver: ${e.message}")
        }
    }

    private fun registerBluetoothStateReceiver() {
        val app = application ?: return
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        try {
            app.registerReceiver(bluetoothStateReceiver, intentFilter)
            Log.i(TAG, "Bluetooth state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register Bluetooth state receiver: ${e.message}")
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context == null || intent == null) return

            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val connectState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    Log.d(TAG, "Bluetooth state changed: $connectState")

                    if (connectState == BluetoothAdapter.STATE_OFF) {
                        Log.i(TAG, "Bluetooth OFF")
                        BleOperateManager.getInstance().setBluetoothTurnOff(false)
                        BleOperateManager.getInstance().disconnect()
                        _connectionState.value = GlassesConnectionState.DISCONNECTED
                        settingsRepository.isGlassesConnected = false
                    } else if (connectState == BluetoothAdapter.STATE_ON) {
                        Log.i(TAG, "Bluetooth ON")
                        BleOperateManager.getInstance().setBluetoothTurnOff(true)
                        // Auto reconnect - giống sample app
                        val savedAddress = DeviceManager.getInstance().deviceAddress
                        if (!savedAddress.isNullOrEmpty()) {
                            Log.i(TAG, "Auto reconnecting to: $savedAddress")
                            BleOperateManager.getInstance().reConnectMac = savedAddress
                            BleOperateManager.getInstance().connectDirectly(savedAddress)
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    Log.d(TAG, "ACL disconnected")
                    _connectionState.value = GlassesConnectionState.DISCONNECTED
                    settingsRepository.isGlassesConnected = false
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedGlasses() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return

        // Log all bonded devices for debugging
        val allBonded = adapter.bondedDevices?.toList() ?: emptyList()
        Log.d(TAG, "Total bonded devices: ${allBonded.size}")
        allBonded.forEach { device ->
            Log.d(TAG, "  - ${device.name ?: "null"} (${device.address})")
        }

        // Show all bonded devices (not just "M*" - let user choose)
        _bondedGlasses.value = allBonded
        Log.d(TAG, "Found ${allBonded.size} bonded devices")
    }

    /**
     * Auto connect to glasses - giống sample app MainActivity.autoConnectToGlasses()
     */
    @SuppressLint("MissingPermission")
    fun autoConnectToGlasses() {
        Log.i(TAG, "Auto connecting to glasses...")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            Log.d(TAG, "Bluetooth not enabled, waiting...")
            return
        }

        val bondedDevices = adapter.bondedDevices
        if (bondedDevices.isNullOrEmpty()) {
            Log.d(TAG, "No bonded devices found")
            return
        }

        val glasses = bondedDevices.filter {
            it.name != null && it.name.startsWith("M")
        }

        if (glasses.isEmpty()) {
            Log.d(TAG, "No glasses (M*) found in bonded devices")
            return
        }

        val target = glasses.first()
        Log.i(TAG, "Found glasses: ${target.name} (${target.address})")

        // Save device info
        DeviceManager.getInstance().setDeviceAddress(target.address)
        DeviceManager.getInstance().setDeviceName(target.name)
        settingsRepository.glassesName = target.name
        _glassesName.value = target.name

        // Connect
        _connectionState.value = GlassesConnectionState.CONNECTING
        BleOperateManager.getInstance().connectDirectly(target.address)
        Log.i(TAG, "Auto connecting to ${target.name}...")
    }

    /**
     * Set device as glasses (save info but don't connect yet)
     */
    fun setAsGlasses(device: BluetoothDevice) {
        val address = device.address
        val name = device.name ?: "Unknown"

        Log.i(TAG, "Setting as glasses: $name ($address)")

        // Save glasses info
        settingsRepository.glassesAddress = address
        settingsRepository.glassesName = name
        _glassesName.value = name

        // Also set in DeviceManager for SDK
        DeviceManager.getInstance().setDeviceAddress(address)
        DeviceManager.getInstance().setDeviceName(name)
    }

    /**
     * Get saved glasses name
     */
    fun getSavedGlassesName(): String? {
        return settingsRepository.glassesName
    }

    /**
     * Connect to specific glasses device
     */
    @SuppressLint("MissingPermission")
    fun connectToGlasses(device: BluetoothDevice) {
        val address = device.address
        val name = device.name ?: "Unknown"

        _connectionState.value = GlassesConnectionState.CONNECTING
        DeviceManager.getInstance().setDeviceAddress(address)
        DeviceManager.getInstance().setDeviceName(name)

        // Save glasses info for future auto-reconnect
        settingsRepository.glassesAddress = address
        settingsRepository.glassesName = name
        _glassesName.value = name

        Log.i(TAG, "Connecting to $name ($address)")
        BleOperateManager.getInstance().connectDirectly(address)
    }

    fun disconnectFromGlasses() {
        Log.i(TAG, "Disconnecting from glasses")
        BleOperateManager.getInstance().unBindDevice()
        _connectionState.value = GlassesConnectionState.DISCONNECTED
        settingsRepository.isGlassesConnected = false
        settingsRepository.glassesName = null
        settingsRepository.glassesAddress = null
        _glassesName.value = null
        _batteryLevel.value = -1
    }

    /**
     * Send command to glasses via BLE - giống sample app
     */
    fun glassesControl(
        command: ByteArray,
        callback: (Boolean, Int, Any?) -> Unit
    ) {
        if (!sdkInitialized) {
            Log.e(TAG, "glassesControl: SDK not initialized")
            callback(false, -1, null)
            return
        }
        try {
            val handler = LargeDataHandler.getInstance()
            handler.glassesControl(command) { cmdType, response ->
                val success = response != null
                callback(success, cmdType, response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "glassesControl error: ${e.message}")
            callback(false, -1, null)
        }
    }

    /**
     * Get AI thumbnail from glasses - giống sample app
     */
    fun getPictureThumbnails(callback: (Boolean, ByteArray?) -> Unit) {
        try {
            LargeDataHandler.getInstance().getPictureThumbnails { cmdType, success, data ->
                callback(success, data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPictureThumbnails error: ${e.message}")
            callback(false, null)
        }
    }

    @SuppressLint("MissingPermission")
    fun isGlassesConnected(): Boolean {
        // Check both the internal state and the settings repository flag
        // The settings flag may be updated via system Bluetooth events
        if (_connectionState.value == GlassesConnectionState.CONNECTED) return true
        if (settingsRepository.isGlassesConnected) return true

        // Also check if the saved glasses address is currently connected via system Bluetooth
        val savedAddress = settingsRepository.glassesAddress
        if (savedAddress != null) {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null) {
                    val device = adapter.getRemoteDevice(savedAddress)
                    val connectionIntents = device.javaClass.getMethod("isConnected")
                    val isConnected = connectionIntents.invoke(device) as Boolean
                    if (isConnected) {
                        // Update state to reflect actual connection
                        _connectionState.value = GlassesConnectionState.CONNECTED
                        return true
                    }
                }
            } catch (e: Exception) {
                // Ignore errors, just return false
            }
        }
        return false
    }

    private var sdkInitialized = false

    // For test photo capture - accumulates thumbnail data from multiple packets
    private val testPhotoAccumulator = java.io.ByteArrayOutputStream()
    private var testPhotoCallback: ((ByteArray?) -> Unit)? = null
    private var testPhotoTimeout: android.os.Handler? = null

    /**
     * Test capture: Send AI photo command to glasses and collect thumbnail
     * Returns image bytes on success, null on failure
     */
    fun takeTestPhoto(callback: (ByteArray?) -> Unit) {
        if (!sdkInitialized) {
            Log.e(TAG, "takeTestPhoto: SDK not initialized")
            callback(null)
            return
        }
        if (!isGlassesConnected()) {
            Log.e(TAG, "takeTestPhoto: glasses not connected")
            callback(null)
            return
        }

        // Store callback for notify listener to use
        testPhotoCallback = callback
        testPhotoAccumulator.reset()

        // Set timeout - if no thumbnail received in 15 seconds, give up
        testPhotoTimeout?.removeCallbacksAndMessages(null)
        testPhotoTimeout = android.os.Handler(android.os.Looper.getMainLooper())
        testPhotoTimeout?.postDelayed({
            if (testPhotoCallback != null) {
                Log.w(TAG, "takeTestPhoto: timeout waiting for thumbnail")
                testPhotoCallback?.invoke(null)
                testPhotoCallback = null
            }
        }, 15000)

        // Send AI photo command (same as sample app btnThumbnail)
        // Format: 0x02 (header) | 0x01 (sub) | 0x06 (AI photo cmd) | thumbnailSize | thumbnailSize | 0x02
        val thumbnailSize = 0x02
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x06, thumbnailSize.toByte(), thumbnailSize.toByte(), 0x02)
            ) { _, response ->
                if (response != null) {
                    Log.i(TAG, "takeTestPhoto: command response received")
                } else {
                    Log.i(TAG, "takeTestPhoto: command sent, waiting for glasses to process...")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "takeTestPhoto: glassesControl error: ${e.message}")
            testPhotoTimeout?.removeCallbacksAndMessages(null)
            testPhotoCallback = null
            callback(null)
        }
    }

    /**
     * Called by deviceNotifyListener when glasses sends a photo/AI event (type 0x02)
     * This triggers thumbnail collection
     */
    private fun onGlassesPhotoEvent() {
        if (testPhotoCallback == null) return
        Log.i(TAG, "Glasses photo event received, fetching thumbnails...")

        testPhotoAccumulator.reset()
        try {
            LargeDataHandler.getInstance().getPictureThumbnails { _, success, data ->
                data?.let { testPhotoAccumulator.write(it) }
                if (success) {
                    val fullImage = testPhotoAccumulator.toByteArray()
                    testPhotoAccumulator.reset()
                    Log.i(TAG, "takeTestPhoto: received ${fullImage.size} bytes")
                    testPhotoTimeout?.removeCallbacksAndMessages(null)
                    testPhotoCallback?.invoke(fullImage)
                    testPhotoCallback = null
                } else {
                    Log.d(TAG, "takeTestPhoto: intermediate thumbnail packet (${testPhotoAccumulator.size()} bytes)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "takeTestPhoto: getPictureThumbnails error: ${e.message}")
            testPhotoTimeout?.removeCallbacksAndMessages(null)
            testPhotoCallback?.invoke(null)
            testPhotoCallback = null
        }
    }

    fun getConnectedGlasses(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val name = _glassesName.value ?: return null
        return adapter.bondedDevices?.find { it.name == name }
    }

    /**
     * AI Photo capture: Send AI photo command, wait for glasses response, return thumbnail bytes
     * Used by GlassesCameraTools.photoAi() for AI vision
     */
    fun photoAiCapture(callback: (ByteArray?) -> Unit) {
        if (!sdkInitialized) {
            Log.e(TAG, "photoAiCapture: SDK not initialized")
            callback(null)
            return
        }
        if (!isGlassesConnected()) {
            Log.e(TAG, "photoAiCapture: glasses not connected")
            callback(null)
            return
        }

        // Store callback for notify listener to use
        testPhotoCallback = callback
        testPhotoAccumulator.reset()

        // Set timeout - if no thumbnail received in 15 seconds, give up
        testPhotoTimeout?.removeCallbacksAndMessages(null)
        testPhotoTimeout = android.os.Handler(android.os.Looper.getMainLooper())
        testPhotoTimeout?.postDelayed({
            if (testPhotoCallback != null) {
                Log.w(TAG, "photoAiCapture: timeout waiting for thumbnail")
                testPhotoCallback?.invoke(null)
                testPhotoCallback = null
            }
        }, 15000)

        // Send AI photo command (same as sample app btnThumbnail)
        val thumbnailSize = 0x02
        try {
            LargeDataHandler.getInstance().glassesControl(
                byteArrayOf(0x02, 0x01, 0x06, thumbnailSize.toByte(), thumbnailSize.toByte(), 0x02)
            ) { _, response ->
                if (response != null) {
                    Log.i(TAG, "photoAiCapture: command response received")
                } else {
                    Log.i(TAG, "photoAiCapture: command sent, waiting for glasses to process...")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "photoAiCapture: glassesControl error: ${e.message}")
            testPhotoTimeout?.removeCallbacksAndMessages(null)
            testPhotoCallback = null
            callback(null)
        }
    }

    fun destroy() {
        application?.let { app ->
            try {
                app.unregisterReceiver(bluetoothStateReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister state receiver: ${e.message}")
            }
            try {
                LocalBroadcastManager.getInstance(app).unregisterReceiver(bluetoothCallbackReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister callback receiver: ${e.message}")
            }
        }
        // Stop scanning if active
        stopScan()
        // Clean up test photo state
        testPhotoTimeout?.removeCallbacksAndMessages(null)
        testPhotoCallback = null
        sdkInitialized = false
    }

    /**
     * Start BLE scan for glasses devices - giống sample app DeviceBindActivity
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        Log.i(TAG, "Starting BLE scan for glasses...")
        if (_isScanning.value) {
            Log.d(TAG, "Already scanning")
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.e(TAG, "Bluetooth not available or disabled")
            return
        }

        _scannedGlasses.value = emptyList()
        _isScanning.value = true

        // Reset callback and start scan
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(application, null, scanCallback)

        Log.i(TAG, "BLE scan started")
    }

    /**
     * Stop BLE scan
     */
    fun stopScan() {
        Log.i(TAG, "Stopping BLE scan")
        application?.let {
            BleScannerHelper.getInstance().stopScan(it)
        }
        _isScanning.value = false
        Log.i(TAG, "BLE scan stopped")
    }

    private val scanCallback = object : ScanWrapperCallback {
        override fun onStart() {
            Log.d(TAG, "Scan started")
        }

        override fun onStop() {
            Log.d(TAG, "Scan stopped")
            _isScanning.value = false
        }

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (device != null && !device.name.isNullOrEmpty()) {
                Log.d(TAG, "Found device: ${device.name} (${device.address}) RSSI: $rssi")

                // Filter for glasses (name starts with M)
                if (device.name.startsWith("M")) {
                    val currentList = _scannedGlasses.value.toMutableList()
                    // Avoid duplicates
                    if (!currentList.any { it.address == device.address }) {
                        currentList.add(0, device)
                        _scannedGlasses.value = currentList
                        Log.i(TAG, "Added glasses: ${device.name}")
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            _isScanning.value = false
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: com.oudmon.ble.base.scan.ScanRecord?) {
            // Not used
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            // Not used
        }
    }

    companion object {
        private const val TAG = "SmartGlassesManager"

        @Volatile
        private var instance: SmartGlassesManager? = null

        fun getInstance(): SmartGlassesManager {
            return instance ?: throw IllegalStateException(
                "SmartGlassesManager not initialized. Call initialize() first."
            )
        }

        fun getInstanceOrNull(): SmartGlassesManager? = instance

        fun isInitialized(): Boolean = instance != null
    }

    fun isSdkInitialized(): Boolean = sdkInitialized

    init {
        instance = this
    }
}
