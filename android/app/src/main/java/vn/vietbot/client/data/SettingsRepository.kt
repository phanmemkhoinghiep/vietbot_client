package vn.vietbot.client.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import vn.vietbot.client.data.model.MqttConfig
import vn.vietbot.client.data.model.TransportType
import javax.inject.Inject
import javax.inject.Singleton

enum class CameraSource { PHONE, GLASSES }

interface SettingsRepository {
    var transportType: TransportType
    var mqttConfig: MqttConfig?
    var webSocketUrl: String?
    var fontFamily: String
    var fontSize: Int
    var textColorHex: String
    var bubbleColorHex: String

    // Camera source for AI vision / photo
    var cameraSource: CameraSource

    // Translation TTS mode: true = offline (local TTS, faster), false = server audio (slower, higher quality)
    var useOfflineTts: Boolean

    // Saved glasses device info
    var glassesAddress: String?
    var glassesName: String?

    // Runtime flags - NOT persisted (set by SmartGlassesManager at runtime)
    var isGlassesConnected: Boolean
    var glassesBatteryLevel: Int

    // Persist settings to storage
    fun save()

    // Reload settings from storage
    fun reload()
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val context: Context
) : SettingsRepository {

    companion object {
        private const val PREFS_NAME = "vietbot_settings"
        private const val KEY_TRANSPORT_TYPE = "transport_type"
        private const val KEY_MQTT_CONFIG = "mqtt_config"
        private const val KEY_WEB_SOCKET_URL = "web_socket_url"
        private const val KEY_FONT_FAMILY = "font_family"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_TEXT_COLOR = "text_color"
        private const val KEY_BUBBLE_COLOR = "bubble_color"
        private const val KEY_CAMERA_SOURCE = "camera_source"
        private const val KEY_USE_OFFLINE_TTS = "use_offline_tts"
        private const val KEY_GLASSES_ADDRESS = "glasses_address"
        private const val KEY_GLASSES_NAME = "glasses_name"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Runtime flags (not persisted)
    override var isGlassesConnected: Boolean = false
    override var glassesBatteryLevel: Int = -1

    // Persistent settings
    override var transportType: TransportType
        get() = try {
            val value = prefs.getString(KEY_TRANSPORT_TYPE, TransportType.WebSockets.name)
            TransportType.valueOf(value ?: TransportType.WebSockets.name)
        } catch (e: Exception) { TransportType.WebSockets }
        set(value) { prefs.edit().putString(KEY_TRANSPORT_TYPE, value.name).apply() }

    override var mqttConfig: MqttConfig?
        get() {
            val json = prefs.getString(KEY_MQTT_CONFIG, null) ?: return null
            return try {
                val jsonObj = JSONObject(json)
                MqttConfig(
                    endpoint = jsonObj.optString("endpoint", ""),
                    port = jsonObj.optInt("port", 1883),
                    clientId = jsonObj.optString("clientId", ""),
                    username = jsonObj.optString("username", ""),
                    password = jsonObj.optString("password", ""),
                    publishTopic = jsonObj.optString("publishTopic", ""),
                    subscribeTopic = jsonObj.optString("subscribeTopic", "")
                )
            } catch (e: Exception) { null }
        }
        set(value) {
            val jsonObj = if (value != null) {
                JSONObject().apply {
                    put("endpoint", value.endpoint)
                    put("port", value.port)
                    put("clientId", value.clientId)
                    put("username", value.username)
                    put("password", value.password)
                    put("publishTopic", value.publishTopic)
                    put("subscribeTopic", value.subscribeTopic)
                }
            } else JSONObject()
            prefs.edit().putString(KEY_MQTT_CONFIG, jsonObj.toString()).apply()
        }

    override var webSocketUrl: String?
        get() = prefs.getString(KEY_WEB_SOCKET_URL, null)
        set(value) { prefs.edit().putString(KEY_WEB_SOCKET_URL, value).apply() }

    override var fontFamily: String
        get() = prefs.getString(KEY_FONT_FAMILY, "System") ?: "System"
        set(value) { prefs.edit().putString(KEY_FONT_FAMILY, value).apply() }

    override var fontSize: Int
        get() = prefs.getInt(KEY_FONT_SIZE, 16)
        set(value) { prefs.edit().putInt(KEY_FONT_SIZE, value).apply() }

    override var textColorHex: String
        get() = prefs.getString(KEY_TEXT_COLOR, "#000000") ?: "#000000"
        set(value) { prefs.edit().putString(KEY_TEXT_COLOR, value).apply() }

    override var bubbleColorHex: String
        get() = prefs.getString(KEY_BUBBLE_COLOR, "#E3F2FD") ?: "#E3F2FD"
        set(value) { prefs.edit().putString(KEY_BUBBLE_COLOR, value).apply() }

    override var cameraSource: CameraSource
        get() = try {
            val value = prefs.getString(KEY_CAMERA_SOURCE, CameraSource.PHONE.name)
            CameraSource.valueOf(value ?: CameraSource.PHONE.name)
        } catch (e: Exception) { CameraSource.PHONE }
        set(value) { prefs.edit().putString(KEY_CAMERA_SOURCE, value.name).apply() }

    override var useOfflineTts: Boolean
        get() = prefs.getBoolean(KEY_USE_OFFLINE_TTS, true)  // default: fast translation
        set(value) { prefs.edit().putBoolean(KEY_USE_OFFLINE_TTS, value).apply() }

    override var glassesAddress: String?
        get() = prefs.getString(KEY_GLASSES_ADDRESS, null)
        set(value) { prefs.edit().putString(KEY_GLASSES_ADDRESS, value).apply() }

    override var glassesName: String?
        get() = prefs.getString(KEY_GLASSES_NAME, null)
        set(value) { prefs.edit().putString(KEY_GLASSES_NAME, value).apply() }

    override fun save() {
        // All settings are saved immediately via SharedPreferences
        // This method is kept for API compatibility
    }

    override fun reload() {
        // SharedPreferences values are always fresh
        // This method is kept for API compatibility
    }
}