package vn.vietbot.client.data.model

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.text.toLowerCase
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

data class ChipInfo(
    val model: Int,
    val cores: Int,
    val revision: Int,
    val features: Int
)

data class Application(
    val name: String,
    val version: String,
    val compile_time: String,
    val idf_version: String,
    val elf_sha256: String
)

data class Partition(
    val label: String,
    val type: Int,
    val subtype: Int,
    val address: Int,
    val size: Int
)

data class OTA(val label: String)

data class Board(
    val name: String,
    val revision: String,
    val features: List<String>,
    val manufacturer: String,
    val serial_number: String
)

data class DeviceInfo(
    val version: Int,
    val flash_size: Int,
    val psram_size: Int,
    val minimum_free_heap_size: Int,
    val mac_address: String,
    val uuid: String,
    val chip_model_name: String,
    val chip_info: ChipInfo,
    val application: Application,
    val partition_table: List<Partition>,
    val ota: OTA,
    val board: Board
)

fun DeviceInfo.toJson() = JSONObject().apply {
    put("version", version)
    put("flash_size", flash_size)
    put("psram_size", psram_size)
    put("minimum_free_heap_size", minimum_free_heap_size)
    put("mac_address", mac_address)
    put("uuid", uuid)
    put("chip_model_name", chip_model_name)
    put("chip_info", JSONObject().apply {
        put("model", chip_info.model)
        put("cores", chip_info.cores)
        put("revision", chip_info.revision)
        put("features", chip_info.features)
    })
    put("application", JSONObject().apply {
        put("name", application.name)
        put("version", application.version)
        put("compile_time", application.compile_time)
        put("idf_version", application.idf_version)
        put("elf_sha256", application.elf_sha256)
    })
    put("partition_table", JSONArray(partition_table.map {
        JSONObject().apply {
            put("label", it.label)
            put("type", it.type)
            put("subtype", it.subtype)
            put("address", it.address)
            put("size", it.size)
        }
    }))
    put("ota", JSONObject().apply {
        put("label", ota.label)
    })
    put("board", JSONObject().apply {
        put("name", board.name)
        put("revision", board.revision)
        put("features", JSONArray(board.features))
        put("manufacturer", board.manufacturer)
        put("serial_number", board.serial_number)
        // Add mac and ip inside board section (like ESP32)
        put("mac", mac_address)
        put("ip", board.serial_number)
    })
    // Also add at root level for compatibility
    put("mac", mac_address)
    put("ip", board.serial_number)
}.toString()

fun fromJsonToDeviceInfo(json: String): DeviceInfo {
    val obj = JSONObject(json)
    return DeviceInfo(
        version = obj.getInt("version"),
        flash_size = obj.getInt("flash_size"),
        psram_size = obj.getInt("psram_size"),
        minimum_free_heap_size = obj.getInt("minimum_free_heap_size"),
        mac_address = obj.getString("mac_address"),
        uuid = obj.getString("uuid"),
        chip_model_name = obj.getString("chip_model_name"),
        chip_info = ChipInfo(
            model = obj.getJSONObject("chip_info").getInt("model"),
            cores = obj.getJSONObject("chip_info").getInt("cores"),
            revision = obj.getJSONObject("chip_info").getInt("revision"),
            features = obj.getJSONObject("chip_info").getInt("features")
        ),
        application = Application(
            name = obj.getJSONObject("application").getString("name"),
            version = obj.getJSONObject("application").getString("version"),
            compile_time = obj.getJSONObject("application").getString("compile_time"),
            idf_version = obj.getJSONObject("application").getString("idf_version"),
            elf_sha256 = obj.getJSONObject("application").getString("elf_sha256")
        ),
        partition_table = obj.getJSONArray("partition_table").let { jsonArray ->
            (0 until jsonArray.length()).map { index ->
                jsonArray.getJSONObject(index).let {
                    Partition(
                        label = it.getString("label"),
                        type = it.getInt("type"),
                        subtype = it.getInt("subtype"),
                        address = it.getInt("address"),
                        size = it.getInt("size")
                    )
                }
            }
        },
        ota = OTA(obj.getJSONObject("ota").getString("label")),
        board = Board(
            name = obj.getJSONObject("board").getString("name"),
            revision = obj.getJSONObject("board").getString("revision"),
            features = (0 until obj.getJSONObject("board").getJSONArray("features").length()).map { index ->
                obj.getJSONObject("board").getJSONArray("features").getString(index)
            },
            manufacturer = obj.getJSONObject("board").getString("manufacturer"),
            serial_number = obj.getJSONObject("board").getString("serial_number")
        )
    )
}

object DummyDataGenerator {
    fun generate(context: android.content.Context): DeviceInfo {
        return DeviceInfo(
            version = 2,
            flash_size = 8388608,
            psram_size = 4194304,
            minimum_free_heap_size = Random.nextInt(200000, 300000),
            mac_address = getMacAddress(context),
            uuid = UUID.randomUUID().toString(),
            chip_model_name = "android",
            chip_info = ChipInfo(
                model = 3,
                cores = 2,
                revision = 1,
                features = 5
            ),
            application = Application(
                name = "vietbot-android",
                version = "1.0.0",
                compile_time = "2026-05-18T12:00:00Z",
                idf_version = "android-sdk-${android.os.Build.VERSION.SDK_INT}",
                elf_sha256 = generateRandomSha256()
            ),
            partition_table = listOf(
                Partition("app", 1, 2, 65536, 2097152),
                Partition("nvs", 1, 1, 32768, 65536),
                Partition("phy_init", 1, 3, 98304, 8192)
            ),
            ota = OTA("ota_1"),
            board = Board(
                name = getDeviceModel(),
                revision = "v1.0",
                features = listOf("WiFi", "Bluetooth"),
                manufacturer = android.os.Build.MANUFACTURER,
                serial_number = android.os.Build.SERIAL
            )
        )
    }

    private fun getMacAddress(context: android.content.Context): String {
        // Check for location permission (required for MAC access on Android 10+)
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // Try WiFi manager first (only works when WiFi is connected)
        try {
            val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val macAddress = wifiInfo.macAddress

            if (macAddress != null && macAddress != "02:00:00:00:00:00" && macAddress != "00:00:00:00:00:00") {
                android.util.Log.d("DeviceInfo", "Got MAC from WiFi: $macAddress (hasLocationPermission=$hasLocationPermission)")
                return macAddress.lowercase()
            }
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfo", "Failed to get MAC from WiFi", e)
        }

        // Try network interfaces (workaround for some devices)
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.equals("wlan0", ignoreCase = true)) {
                    val mac = networkInterface.hardwareAddress
                    if (mac != null && mac.isNotEmpty()) {
                        val macStr = mac.joinToString(":") { String.format("%02x", it) }
                        android.util.Log.d("DeviceInfo", "Got MAC from network interface: $macStr")
                        return macStr.lowercase()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DeviceInfo", "Failed to get MAC from network interface", e)
        }

        // Final fallback: generate stable MAC based on device serial
        val stableMac = generateStableMac(context)
        android.util.Log.w("DeviceInfo", "Using stable generated MAC: $stableMac")
        return stableMac
    }

    private fun generateStableMac(context: android.content.Context): String {
        // Generate deterministic MAC based on device serial to avoid random MAC on each restart
        val serial = android.os.Build.SERIAL ?: android.os.Build.getSerial() ?: "unknown"
        val hash = serial.hashCode()
        val mac = String.format("%02x:%02x:%02x:%02x:%02x:%02x",
            (hash shr 40 and 0xFF).toInt() or 0x02,  // Set locally administered bit
            (hash shr 32 and 0xFF).toInt(),
            (hash shr 24 and 0xFF).toInt(),
            (hash shr 16 and 0xFF).toInt(),
            (hash shr 8 and 0xFF).toInt(),
            (hash and 0xFF).toInt()
        )
        android.util.Log.d("DeviceInfo", "Generated stable MAC from serial: $mac")
        return mac
    }

    private fun getDeviceModel(): String {
        val manufacturer = android.os.Build.MANUFACTURER
        val model = android.os.Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    private fun generateMacAddress(): String {
        return List(6) { Random.nextInt(0x00, 0xFF) }
            .joinToString(":") { String.format("%02x", it) }
    }

    private fun generateRandomSha256(): String {
        val chars = "0123456789abcdef"
        return (1..64).map { chars.random() }.joinToString("")
    }
}

