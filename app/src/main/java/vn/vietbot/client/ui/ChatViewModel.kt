package vn.vietbot.client.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import vn.vietbot.client.AudioRecorder
import vn.vietbot.client.NavigationEvents
import vn.vietbot.client.OpusDecoder
import vn.vietbot.client.OpusEncoder
import vn.vietbot.client.OpusStreamPlayer
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.data.model.DeviceInfo
import vn.vietbot.client.data.model.TransportType
import vn.vietbot.client.data.model.toJson
import vn.vietbot.client.mcp.SmartGlassesManager
import vn.vietbot.client.mcp.McpServer
import vn.vietbot.client.mcp.tools.AlarmTool
import vn.vietbot.client.mcp.tools.AppLauncherTool
import vn.vietbot.client.mcp.tools.ContactsTool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import vn.vietbot.client.mcp.tools.DeviceControlTool
import vn.vietbot.client.mcp.tools.NotificationTool
import vn.vietbot.client.protocol.AbortReason
import vn.vietbot.client.protocol.ListeningMode
import vn.vietbot.client.protocol.MqttProtocol
import vn.vietbot.client.protocol.Protocol
import vn.vietbot.client.protocol.WebsocketProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import android.util.Base64
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatViewMode @Inject constructor(
    @ApplicationContext private val context: Context,
    @NavigationEvents private val navigationEvents: MutableSharedFlow<String>,
    deviceInfo: DeviceInfo,
    settings: SettingsRepository,
    glassesManager: SmartGlassesManager?
) : ViewModel() {
    companion object {
        private const val TAG = "ChatViewModel"
    }

    private var _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    private var protocol: Protocol? = null
    private val deviceInfo: DeviceInfo = deviceInfo
    private val settings: SettingsRepository = settings

    val display = Display()
    var encoder: OpusEncoder? = null
    var decoder: OpusDecoder? = null
    var recorder: AudioRecorder? = null
    var player: OpusStreamPlayer? = null
    var aborted: Boolean = false
    var keepListening: Boolean = true
    val deviceStateFlow = MutableStateFlow(DeviceState.IDLE)
    var deviceState: DeviceState
        get() = deviceStateFlow.value
        set(value) {
            deviceStateFlow.value = value
        }

    private var audioJobStarted = false

    // MCP Server for device tools
    private val mcpServer = McpServer(context, glassesManager, settings, protocol).apply {
        AlarmTool(context).register(this)
        AppLauncherTool(context).register(this)
        DeviceControlTool(context).register(this)
        ContactsTool(context).register(this)
        NotificationTool(context).register(this)
        Log.i(TAG, "MCP tools registered")
    }

    // Expose MCP server for lifecycle management
    fun getMcpServer(): McpServer = mcpServer

    // Vision server URL - use vietbot.vn for Android
    private val visionServerUrl: String
        get() = settings.webSocketUrl?.let { wsUrl ->
            wsUrl.replace("wss://", "https://")
                .replace("ws://", "http://")
                .replace("/ws/", "/vision/explain")
                .removeSuffix("/")
        } ?: "https://vietbot.vn/vision/explain"

    // OTA API endpoint
    private val otaBaseUrl: String
        get() = settings.webSocketUrl?.let { wsUrl ->
            wsUrl.replace("wss://", "https://")
                .replace("ws://", "http://")
                .replace("/ws/", "/ota/")
                .removeSuffix("/")
        } ?: "https://vietbot.vn/ota/"

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    private val httpClient = OkHttpClient.Builder()
        .sslSocketFactory(SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, java.security.SecureRandom())
        }.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    /**
     * Gọi OTA API để lấy WebSocket URL và activation code
     */
    private suspend fun callOtaApi(): OtaApiResult = withContext(Dispatchers.IO) {
        try {
            val otaUrl = otaBaseUrl
            Log.i(TAG, "Calling OTA API: $otaUrl")

            val deviceJson = deviceInfo.toJson()
            Log.i(TAG, "OTA request body: $deviceJson")

            val request = Request.Builder()
                .url(otaUrl)
                .addHeader("Content-Type", "application/json")
                .addHeader("Device-Id", deviceInfo.mac_address)
                .addHeader("Client-Id", deviceInfo.uuid)
                .post(deviceJson.toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                Log.e(TAG, "OTA API failed: ${response.code} - $responseBody")
                return@withContext OtaApiResult.Error("API failed: ${response.code}")
            }

            if (responseBody == null) {
                Log.e(TAG, "OTA API returned empty response")
                return@withContext OtaApiResult.Error("Empty response")
            }

            // Parse JSON manually
            val json = org.json.JSONObject(responseBody)

            // Check for activation code
            val activation = json.optJSONObject("activation")
            val activationCode = activation?.optString("code")

            // Get websocket config
            val websocket = json.optJSONObject("websocket")
            val wsUrl = websocket?.optString("url")
            val wsToken = websocket?.optString("token")

            if (activationCode != null && activationCode.isNotEmpty()) {
                Log.w(TAG, "Device needs activation: code=$activationCode")
                return@withContext OtaApiResult.RequiresActivation(activationCode)
            }

            if (wsUrl != null && wsUrl.isNotEmpty()) {
                Log.i(TAG, "OTA success: WS URL=$wsUrl, token=${wsToken?.take(10)}...")
                return@withContext OtaApiResult.Success(wsUrl, wsToken)
            }

            Log.e(TAG, "OTA response missing websocket config")
            return@withContext OtaApiResult.Error("Missing websocket config")
        } catch (e: Exception) {
            Log.e(TAG, "OTA API exception", e)
            return@withContext OtaApiResult.Error(e.message ?: "Unknown error")
        }
    }

    sealed class OtaApiResult {
        data class Success(val wsUrl: String, val token: String?) : OtaApiResult()
        data class RequiresActivation(val code: String) : OtaApiResult()
        data class Error(val message: String) : OtaApiResult()
    }

    fun connect() {
        if (_isConnected.value || _isConnecting.value) return

        // Add separator when reconnecting (there are existing messages)
        if (display.chatFlow.value.isNotEmpty()) {
            display.clearHistoryWithSeparator()
        }

        viewModelScope.launch {
            _isConnecting.value = true
            deviceState = DeviceState.CONNECTING

            // Step 1: Call OTA API (WebSocket mode only)
            var wsProtocol: WebsocketProtocol? = null
            if (settings.transportType == TransportType.WebSockets) {
                when (val otaResult = callOtaApi()) {
                    is OtaApiResult.RequiresActivation -> {
                        Log.w(TAG, "Device not registered, activation code: ${otaResult.code}")
                        display.setChatMessage("system", "Thiết bị chưa được đăng ký!\nMã kích hoạt: ${otaResult.code}\n\nVui lòng nhập mã này trong mục Quản lý thiết bị của ứng dụng Vietbot Manager.")
                        _isConnecting.value = false
                        deviceState = DeviceState.IDLE
                        return@launch
                    }
                    is OtaApiResult.Success -> {
                        wsProtocol = WebsocketProtocol(deviceInfo, otaResult.wsUrl, otaResult.token ?: "test-token")
                    }
                    is OtaApiResult.Error -> {
                        Log.e(TAG, "OTA API error: ${otaResult.message}, falling back to direct WS")
                        wsProtocol = WebsocketProtocol(deviceInfo, settings.webSocketUrl ?: "wss://vietbot.vn/ws/", "test-token")
                    }
                }
            }

            protocol = when (settings.transportType) {
                TransportType.MQTT -> {
                    MqttProtocol(context, settings.mqttConfig!!)
                }
                TransportType.WebSockets -> {
                    wsProtocol!!
                }
            }

            try {
                protocol?.start()

                // Start JSON message collector BEFORE openAudioChannel
                if (!audioJobStarted) {
                    audioJobStarted = true
                    launch {
                        protocol?.incomingJsonFlow?.collect { json ->
                            val type = json.optString("type")
                            when (type) {
                                "tts" -> {
                                    val state = json.optString("state")
                                    when (state) {
                                        "start" -> {
                                            schedule {
                                                aborted = false
                                                if (deviceState == DeviceState.IDLE || deviceState == DeviceState.LISTENING) {
                                                    deviceState = DeviceState.SPEAKING
                                                }
                                            }
                                        }
                                        "stop" -> {
                                            schedule {
                                                if (deviceState == DeviceState.SPEAKING) {
                                                    Log.i(TAG, "waiting for TTS to stop")
                                                    player?.waitForPlaybackCompletion()
                                                    Log.i(TAG, "TTS stopped")
                                                    if (keepListening) {
                                                        protocol?.sendStartListening(ListeningMode.AUTO_STOP)
                                                        deviceState = DeviceState.LISTENING
                                                    } else {
                                                        deviceState = DeviceState.IDLE
                                                    }
                                                }
                                            }
                                        }
                                        "sentence_start" -> {
                                            val text = json.optString("text")
                                            if (text.isNotEmpty()) {
                                                Log.i(TAG, "<< $text")
                                                schedule {
                                                    display.setChatMessage("assistant", text)
                                                }
                                            }
                                        }
                                    }
                                }
                                "stt" -> {
                                    val text = json.optString("text")
                                    if (text.isNotEmpty()) {
                                        Log.i(TAG, ">> $text")
                                        schedule {
                                            display.setChatMessage("user", text)
                                        }
                                    }
                                }
                                "llm" -> {
                                    val emotion = json.optString("emotion")
                                    if (emotion.isNotEmpty()) {
                                        schedule {
                                            display.setEmotion(emotion)
                                        }
                                    }
                                }
                                "iot" -> {
                                    val commands = json.optJSONArray("commands")
                                    Log.i(TAG, "IOT commands: $commands")
                                }
                                "mcp" -> {
                                    handleMcpMessage(json)
                                }
                                "device_bind_required" -> {
                                    val bindCode = json.optString("bind_code")
                                    val deviceId = json.optString("device_id")
                                    Log.w(TAG, "Device needs binding: $deviceId, code: $bindCode")
                                    schedule {
                                        display.setChatMessage("system", "Thiết bị chưa được đăng ký!\nMã kích hoạt: $bindCode\n\nVui lòng đăng ký thiết bị trong ứng dụng Vietbot Manager.")
                                        deviceState = DeviceState.IDLE
                                    }
                                }
                                "session_end" -> {
                                    val reason = json.optString("reason", "unknown")
                                    val message = json.optString("message", "")
                                    Log.w(TAG, "Session ended: reason=$reason, message=$message")
                                    schedule {
                                        display.setChatMessage("system", message.ifEmpty { "Kết thúc phiên" })
                                        deviceState = DeviceState.IDLE
                                        disconnect()
                                    }
                                }
                                "translation_audio" -> {
                                    // Handle translation audio response from server
                                    val audioData = json.optString("audio_data", "")
                                    val transcription = json.optString("text", "")
                                    if (audioData.isNotEmpty()) {
                                        schedule {
                                            val decoded = Base64.decode(audioData, Base64.NO_WRAP)
                                            Log.d(TAG, "Received translation audio, size: ${decoded.size} bytes, text: $transcription")
                                            // Play translation audio directly
                                            decoder?.decode(decoded)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (protocol?.openAudioChannel() == true) {
                    protocol?.sendStartListening(ListeningMode.AUTO_STOP)
                    withContext(Dispatchers.IO) {
                        launch {
                            val sampleRate = 24000
                            val channels = 1
                            val frameSizeMs = 60
                            player = OpusStreamPlayer(sampleRate, channels, frameSizeMs)
                            decoder = OpusDecoder(sampleRate, channels, frameSizeMs)
                            player?.start(protocol!!.incomingAudioFlow.map {
                                deviceState = DeviceState.SPEAKING
                                decoder?.decode(it)
                            })
                        }
                    }
                } else {
                    Log.e("WS", "Failed to open audio channel")
                }

                // Start audio recording job
                if (audioJobStarted) {
                    delay(1000)
                    launch {
                        val sampleRate = 16000
                        val channels = 1
                        val frameSizeMs = 60
                        encoder = OpusEncoder(sampleRate, channels, frameSizeMs)
                        recorder = AudioRecorder(sampleRate, channels, frameSizeMs)
                        val audioFlow = recorder?.startRecording()
                        val opusFlow = audioFlow?.map { encoder?.encode(it) }
                        deviceState = DeviceState.LISTENING
                        opusFlow?.collect {
                            it?.let { protocol?.sendAudio(it) }
                        }
                    }
                }

                _isConnected.value = true
                _isConnecting.value = false
                deviceState = DeviceState.LISTENING

                // Configure MCP camera tools
                mcpServer.configureCameraTools(
                    visionUrl = visionServerUrl,
                    deviceId = deviceInfo.mac_address,
                    clientId = deviceInfo.uuid
                )

            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _isConnected.value = false
                _isConnecting.value = false
                deviceState = DeviceState.IDLE
                protocol?.dispose()
                protocol = null
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            try {
                recorder?.stopRecording()
                player?.stop()
                protocol?.dispose()
                encoder?.release()
                decoder?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            }
            protocol = null
            encoder = null
            decoder = null
            recorder = null
            player = null
            mcpServer.release()
            _isConnected.value = false
            _isConnecting.value = false
            deviceState = DeviceState.IDLE
            audioJobStarted = false
        }
    }

    /**
     * Handle MCP message from server
     * Also displays MCP tool results on screen for better user experience
     */
    private fun handleMcpMessage(json: JSONObject) {
        viewModelScope.launch {
            try {
                val payload = json.optJSONObject("payload") ?: return@launch
                val method = payload.optString("method", "")
                val id = payload.optInt("id", -1)

                // Handle tools/list request from server
                if (method == "tools/list") {
                    val response = mcpServer.handleMessage(payload)
                    response?.let {
                        val mcpResponse = JSONObject().apply {
                            put("type", "mcp")
                            put("payload", it)
                        }
                        protocol?.sendText(mcpResponse.toString())
                        Log.i(TAG, "Sent MCP tools/list response")
                    }
                    return@launch
                }

                // Handle incoming MCP request from server (tools/call)
                if (method == "tools/call") {
                    val response = mcpServer.handleMessage(payload)
                    response?.let {
                        // Send MCP response back to server
                        val mcpResponse = JSONObject().apply {
                            put("type", "mcp")
                            put("payload", it)
                        }
                        protocol?.sendText(mcpResponse.toString())
                        Log.i(TAG, "Sent MCP response")

                        // Display media in chat if present (only if non-empty)
                        val resultObj = it.optJSONObject("result")
                        val imageUri = resultObj?.optString("imageUri", "")?.takeIf { it.isNotEmpty() }
                        val videoUri = resultObj?.optString("videoUri", "")?.takeIf { it.isNotEmpty() }
                        val audioUri = resultObj?.optString("audioUri", "")?.takeIf { it.isNotEmpty() }
                        if (imageUri != null) {
                            display.setChatMessage("system", "", imageUri = imageUri)
                        }
                        if (videoUri != null) {
                            display.setChatMessage("system", "", videoUri = videoUri)
                        }
                        if (audioUri != null) {
                            display.setChatMessage("system", "", audioUri = audioUri)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling MCP message", e)
            }
        }
    }

    fun toggleChatState() {
        viewModelScope.launch {
            when (deviceState) {
                DeviceState.ACTIVATING -> {
                    reboot()
                }

                DeviceState.IDLE -> {
                    if (protocol?.openAudioChannel() == true) {
                        keepListening = true
                        protocol?.sendStartListening(ListeningMode.AUTO_STOP)
                        deviceState = DeviceState.LISTENING
                    } else {
                        deviceState = DeviceState.IDLE
                    }
                }

                DeviceState.SPEAKING -> {
                    abortSpeaking(AbortReason.NONE)
                }

                DeviceState.LISTENING -> {
                    protocol?.closeAudioChannel()
                }

                else -> {
                    Log.e(TAG, "Protocol not initialized or invalid state")
                }
            }
        }
    }

    fun startListening() {
        viewModelScope.launch {
            if (deviceState == DeviceState.ACTIVATING) {
                reboot()
                return@launch
            }

            keepListening = false
            if (deviceState == DeviceState.IDLE) {
                if (!protocol?.isAudioChannelOpened()!!) {
                    deviceState = DeviceState.CONNECTING
                    if (protocol?.openAudioChannel() != true) {
                        deviceState = DeviceState.IDLE
                        return@launch
                    }
                }
                protocol?.sendStartListening(ListeningMode.MANUAL)
                deviceState = DeviceState.LISTENING
            } else if (deviceState == DeviceState.SPEAKING) {
                abortSpeaking(AbortReason.NONE)
                protocol?.sendStartListening(ListeningMode.MANUAL)
                delay(120) // Wait for the speaker to empty the buffer
                deviceState = DeviceState.LISTENING
            }
        }
    }

    private fun reboot() {
        // Implement the reboot logic here
    }

    fun abortSpeaking(reason: AbortReason) {
        Log.i(TAG, "Abort speaking")
        aborted = true
        viewModelScope.launch {
            protocol?.sendAbortSpeaking(reason)
        }
    }
    private fun schedule(task: suspend () -> Unit) {
        viewModelScope.launch {
            task()
        }
    }


    fun stopListening() {
        viewModelScope.launch {
            if (deviceState == DeviceState.LISTENING) {
                protocol?.sendStopListening()
                deviceState = DeviceState.IDLE
            }
        }
    }

    // Send text message to server (for text input feature)
    fun sendTextMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            // Don't display here - server will send back via stt message
            // Send to server via protocol
            val json = JSONObject().apply {
                put("type", "listen")
                put("state", "detect")
                put("text", text)
            }
            protocol?.sendText(json.toString())
        }
    }

    override fun onCleared() {
        disconnect()
        super.onCleared()
    }
}

enum class DeviceState {
    UNKNOWN,
    STARTING,
    WIFI_CONFIGURING,
    IDLE,
    CONNECTING,
    LISTENING,
    SPEAKING,
    UPGRADING,
    ACTIVATING,
    FATAL_ERROR
}


class Display {
    val chatFlow = MutableStateFlow<List<Message>>(listOf())
    val emotionFlow = MutableStateFlow<String>("neutral")

    // Clear history and add separator when reconnecting
    fun clearHistoryWithSeparator() {
        chatFlow.value = chatFlow.value + Message(
            sender = "system",
            message = "─────────────────────────────",
            isSeparator = true
        )
    }

    fun setChatMessage(sender: String, message: String, imageUri: String? = null, videoUri: String? = null, audioUri: String? = null) {
        chatFlow.value = chatFlow.value + Message(sender, message, imageUri = imageUri, videoUri = videoUri, audioUri = audioUri)
    }

    fun setEmotion(emotion: String) {
        emotionFlow.value = emotion
    }
}

val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

data class Message(
    val sender: String = "",
    val message: String = "",
    val nowInString: String = df.format(System.currentTimeMillis()),
    val imageUri: String? = null,
    val videoUri: String? = null,
    val audioUri: String? = null,
    val isSeparator: Boolean = false
)