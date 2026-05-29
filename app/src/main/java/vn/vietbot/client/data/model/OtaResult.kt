package vn.vietbot.client.data.model

import org.json.JSONObject

//{
//    "mqtt":{
//    "endpoint":"post-cn-apg3xckag01.mqtt.aliyuncs.com",
//    "client_id":"GID_test@@@A4_B1_C2_D3_E4_F5",
//    "username":"Signature|LTAI5tF8J3CrdWmRiuTjxHbF|post-cn-apg3xckag01",
//    "password":"xdDhCgk9xjQpLECVH+5UsSBs0/k=",
//    "publish_topic":"device-server",
//    "subscribe_topic":"devices/A4_B1_C2_D3_E4_F5"
//},
//    "server_time":{
//    "timestamp":1740736167303,
//    "timezone":"Asia/Shanghai",
//    "timezone_offset":480
//},
//    "firmware":{
//    "version":"2.3.1",
//    "url":""
//},
//    "activation":{
//    "code":"010215",
//    "message":"xiaozhi.me\n010215"
//}
//}



data class OtaResult(
    val mqttConfig: MqttConfig,
    val activation: Activation?,
    val serverTime: ServerTime?,
    val firmware: Firmware?
)

fun fromJsonToOtaResult(json: JSONObject): OtaResult {
    return OtaResult(
        mqttConfig = fromJsonToMqttConfig(json.getJSONObject("mqtt")),
        activation = json.optJSONObject("activation")?.let { fromJsonToActivation(it) },
        serverTime = json.optJSONObject("server_time")?.let { fromJsonToServerTime(it) },
        firmware = json.optJSONObject("firmware")?.let { fromJsonToFirmware(it) }
    )
}


data class ServerTime(
    val timestamp: Long,
    val timezone: String?,
    val timezoneOffset: Int
)

fun fromJsonToServerTime(json: JSONObject): ServerTime {
    return ServerTime(
        timestamp = json.getLong("timestamp"),
        timezone = json.optString("timezone", null),
        timezoneOffset = json.getInt("timezone_offset")
    )
}


data class Firmware(
    val version: String,
    val url: String
)

fun fromJsonToFirmware(json: JSONObject): Firmware {
    return Firmware(
        version = json.getString("version"),
        url = json.getString("url")
    )
}


data class Activation(
    val code: String,
    val message: String,
    val challenge: String? = null
)

fun fromJsonToActivation(json: JSONObject): Activation {
    return Activation(
        code = json.getString("code"),
        message = json.getString("message"),
        challenge = json.optString("challenge", null)
    )
}



data class MqttConfig(
    val endpoint: String,
    val port: Int,
    val clientId: String,
    val username: String,
    val password: String,
    val publishTopic: String,
    val subscribeTopic: String
) {
    // Full MQTT broker URL for Paho client
    val brokerUrl: String
        get() = if (port == 8883) "ssl://$endpoint:$port" else "tcp://$endpoint:$port"
}
// Vietbot MQTT Gateway response format:
//{
//    "endpoint": "vietbot.vn",
//    "port": 1883,
//    "client_id": "GID_xxx@@@AA_BB_CC_DD_EE_FF@@@uuid",
//    "username": "base64_encoded_json",
//    "password": "hmac_signature",
//    "publish_topic": "device-server",
//    "subscribe_topic": "devices/xxx"
//}
fun fromJsonToMqttConfig(json: JSONObject): MqttConfig {
    val endpoint = json.optString("endpoint", "vietbot.vn")
    val port = json.optInt("port", 1883)
    return MqttConfig(
        endpoint,
        port,
        json.getString("client_id"),
        json.getString("username"),
        json.getString("password"),
        json.getString("publish_topic"),
        json.optString("subscribe_topic", "devices/null")
    )
}
