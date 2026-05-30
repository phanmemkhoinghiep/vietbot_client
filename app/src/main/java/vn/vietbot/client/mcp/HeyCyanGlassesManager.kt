package vn.vietbot.client.mcp

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
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
class HeyCyanGlassesManager @Inject constructor(
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

    private val deviceNotifyListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val type = response.loadData.getOrNull(6)?.toInt() ?: return
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
                }
                // Disconnect event (0x0D)
                0x0D -> {
                    val reason = response.loadData.getOrNull(7)?.toInt() ?: 0
                    Log.d(TAG, "Glasses disconnected: reason=$reason")
                    _connectionState.value = GlassesConnectionState.DISCONNECTED
                    settingsRepository.isGlassesConnected = false
                }
                else -> {
                    Log.d(TAG, "Unknown event type: 0x${type.toString(16)}")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun initialize(context: Context) {
        // Initialize BLE SDK - get Application from context
        val application = context.applicationContext as? Application
            ?: (context as? Application)
            ?: throw IllegalStateException("Context must be an Application")

        BleOperateManager.getInstance(application)
        BleOperateManager.getInstance().setApplication(application)
        BleOperateManager.getInstance().init()

        // Register device notify listener
        LargeDataHandler.getInstance().addOutDeviceListener(100, deviceNotifyListener)

        // Scan for bonded glasses
        refreshBondedGlasses()

        Log.i(TAG, "HeyCyanGlassesManager initialized")
    }

    @SuppressLint("MissingPermission")
    fun refreshBondedGlasses() {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        val glasses = adapter.bondedDevices
            ?.filter { it.name?.startsWith("M") == true }
            ?: emptyList()
        _bondedGlasses.value = glasses
        Log.d(TAG, "Found ${glasses.size} bonded glasses")
    }

    @SuppressLint("MissingPermission")
    fun connectToGlasses(device: BluetoothDevice) {
        val address = device.address
        val name = device.name ?: "Unknown"
        _connectionState.value = GlassesConnectionState.CONNECTING
        DeviceManager.getInstance().setDeviceAddress(address)
        DeviceManager.getInstance().setDeviceName(name)
        settingsRepository.glassesName = name
        _glassesName.value = name
        Log.i(TAG, "Connecting to $name ($address)")
        BleOperateManager.getInstance().connectDirectly(address)
        _connectionState.value = GlassesConnectionState.CONNECTED
        settingsRepository.isGlassesConnected = true
    }

    fun disconnectFromGlasses() {
        Log.i(TAG, "Disconnecting from glasses")
        BleOperateManager.getInstance().unBindDevice()
        _connectionState.value = GlassesConnectionState.DISCONNECTED
        settingsRepository.isGlassesConnected = false
        settingsRepository.glassesName = null
        _glassesName.value = null
        _batteryLevel.value = -1
    }

    /**
     * Send command to glasses via BLE
     * SDK callback: (cmdType: Int, response: GlassModelControlResponse)
     */
    fun glassesControl(
        command: ByteArray,
        callback: (Boolean, Int, Any?) -> Unit
    ) {
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
     * Get AI thumbnail from glasses
     * Callback receives: (success: Boolean, data: ByteArray?)
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

    /**
     * Get workTypeIng from response for status check
     */
    fun getWorkTypeFromResponse(response: Any?): Int {
        return try {
            if (response == null) return -1
            // Try to access workTypeIng via reflection or direct access
            val workType = response.javaClass.getMethod("getWorkTypeIng").invoke(response)
            workType as? Int ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    @SuppressLint("MissingPermission")
    fun isGlassesConnected(): Boolean {
        return _connectionState.value == GlassesConnectionState.CONNECTED
    }

    fun getConnectedGlasses(): BluetoothDevice? {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        val name = _glassesName.value ?: return null
        return adapter.bondedDevices?.find { it.name == name }
    }

    companion object {
        private const val TAG = "HeyCyanGlassesManager"

        @Volatile
        private var instance: HeyCyanGlassesManager? = null

        fun getInstance(): HeyCyanGlassesManager {
            return instance ?: throw IllegalStateException(
                "HeyCyanGlassesManager not initialized. Call initialize() first."
            )
        }

        fun getInstanceOrNull(): HeyCyanGlassesManager? = instance
    }

    init {
        instance = this
    }
}