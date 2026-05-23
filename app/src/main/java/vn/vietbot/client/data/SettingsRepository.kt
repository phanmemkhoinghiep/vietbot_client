package vn.vietbot.client.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import vn.vietbot.client.data.model.MqttConfig
import vn.vietbot.client.data.model.TransportType
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    var transportType: TransportType
    var mqttConfig: MqttConfig?
    var webSocketUrl: String?
    var fontFamily: String
    var fontSize: Int
    var textColorHex: String
    var bubbleColorHex: String
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
}