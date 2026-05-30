package vn.vietbot.client.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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

    // Runtime flags - set by HeyCyanGlassesManager
    var isGlassesConnected: Boolean
    var glassesName: String?
    var glassesBatteryLevel: Int
}

@Singleton
class SettingsRepositoryImpl @Inject constructor() : SettingsRepository {
    override var transportType: TransportType = TransportType.WebSockets
    override var mqttConfig: MqttConfig? = null
    override var webSocketUrl: String? = null
    override var fontFamily: String = "System"
    override var fontSize: Int = 16
    override var textColorHex: String = "#000000"
    override var bubbleColorHex: String = "#E3F2FD"

    override var cameraSource: CameraSource = CameraSource.PHONE
    override var isGlassesConnected: Boolean = false
    override var glassesName: String? = null
    override var glassesBatteryLevel: Int = -1
}