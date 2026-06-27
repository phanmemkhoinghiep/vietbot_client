package vn.vietbot.client.data.model

// :feature:form/data/model/ServerFormData.kt
data class ServerFormData(
    val xiaoZhiConfig: XiaoZhiConfig = XiaoZhiConfig()
)

data class XiaoZhiConfig(
    val webSocketUrl: String = "wss://vietbot.vn/ws/",
    val qtaUrl: String = "https://vietbot.vn/ota/"
) {
    // Always use WebSocket
    val transportType: TransportType = TransportType.WebSockets
}

enum class TransportType {
    MQTT, WebSockets
}

// :feature:form/data/model/ValidationResult.kt
data class ValidationResult(
    val isValid: Boolean,
    val errors: Map<String, String> = emptyMap()
)