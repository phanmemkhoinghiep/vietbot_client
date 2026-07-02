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
import vn.vietbot.client.mcp.tools.YouTubeMcpTool
// Disabled non-core MCP tools - imports commented out
// import vn.vietbot.client.mcp.tools.AlarmTool
// import vn.vietbot.client.mcp.tools.AppLauncherTool
// import vn.vietbot.client.mcp.tools.ContactsTool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
// import vn.vietbot.client.mcp.tools.DeviceControlTool
// import vn.vietbot.client.mcp.tools.NotificationTool
import vn.vietbot.client.protocol.AbortReason
import vn.vietbot.client.protocol.ListeningMode
import vn.vietbot.client.protocol.MqttProtocol
import vn.vietbot.client.protocol.Protocol
import vn.vietbot.client.protocol.WebsocketProtocol
import vn.vietbot.client.translation.TranslationManager
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

    // Translation manager for offline TTS playback
    private val translationManager = TranslationManager(context)

    /**
     * Ensure OpusStreamPlayer is started for server audio playback.
     * Called on connect, tts.start, and translation mode activation.
     */
    private fun ensureAudioPlayerActive() {
        if (player == null && protocol?.isAudioChannelOpened() == true) {
            viewModelScope.launch(Dispatchers.IO) {
                val sampleRate = 24000
                val channels = 1
                val frameSizeMs = 60
                player = OpusStreamPlayer(sampleRate, channels, frameSizeMs)
                player?.start(protocol!!.incomingAudioFlow)
                Log.i(TAG, "OpusStreamPlayer started for audio playback")
            }
        }
    }

    var encoder: OpusEncoder? = null
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

    // Text values that the app rendered locally as a typed user bubble, used to
    // dedup a possible stt echo from the server (legacy pipeline echoes the
    // text back; Gemini Live does not). We never want a typed message to be
    // shown twice. The set is bounded to keep memory in check.
    private val dedupOwnTypedEcho = ArrayDeque<String>(8)

    /**
     * Register `text` (plus its growing whitespace-split prefixes) as a
     * "do not display if echoed back as stt" value. Used to avoid showing
     * the user's typed message twice when the server re-emits it through
     * the stt channel (legacy pipeline behaviour). Bounded FIFO.
     */
    private fun rememberTypedEchoFor(text: String) {
        val full = text.trim()
        if (full.isEmpty()) return
        synchronized(dedupOwnTypedEcho) {
            // Register the full text
            dedupOwnTypedEcho.addLast(full)
            // Register word-prefix chunks so incremental cumulative echo
            // (e.g. "xin", "xin chào", "xin chào bạn") is also caught.
            val tokens = full.split(' ')
            val sb = StringBuilder()
            for (i in tokens.indices) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(tokens[i])
                val prefix = sb.toString()
                if (prefix != full) dedupOwnTypedEcho.addLast(prefix)
            }
            // Bound memory: keep at most the last 64 entries (full + ~3 prefixes per text).
            while (dedupOwnTypedEcho.size > 64) {
                dedupOwnTypedEcho.removeFirst()
            }
        }
    }

    // MCP Server for device tools
    // Disabled non-core tools (kept for future re-enable; permissions removed in
    // AndroidManifest.xml so they would crash at runtime even if registered).
    private val mcpServer = McpServer(context, glassesManager, settings, protocol).apply {
        YouTubeMcpTool(context, settings).register(this) // YouTube playback - opens YouTube app via deep link
        // AlarmTool(context).register(this)         // disabled - out of voice/BLE scope
        // AppLauncherTool(context).register(this)   // disabled - out of voice/BLE scope
        // DeviceControlTool(context).register(this) // disabled - out of voice/BLE scope
        // ContactsTool(context).register(this)      // disabled - requires READ_CONTACTS/CALL_PHONE/SEND_SMS
        // NotificationTool(context).register(this)  // disabled - requires POST_NOTIFICATIONS
        Log.i(TAG, "MCP tools registered (core only: camera/audio/glasses/youtube)")
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
                                            ensureAudioPlayerActive()
                                            schedule {
                                                aborted = false
                                                if (deviceState == DeviceState.IDLE || deviceState == DeviceState.LISTENING) {
                                                    deviceState = DeviceState.SPEAKING
                                                }
                                                // Finalize any open streaming bubble (likely a user STT bubble
                                                // that just finished) and open a new TTS streaming bubble.
                                                // The first sentence_start will fill in the text.
                                                display.finalizeLastMessage()
                                                display.startTts()
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
                                                // Finalize the TTS streaming bubble (timestamp now visible)
                                                display.finalizeLastMessage()
                                            }
                                        }
                                        "sentence_start" -> {
                                            val text = json.optString("text")
                                            if (text.isNotEmpty()) {
                                                Log.i(TAG, "<< $text")

                                                // Server sends translation text just like any other TTS text — no
                                                // special prefix, no special routing. Render it like every
                                                // other assistant sentence and let the server's audio
                                                // stream (OpusStreamPlayer) handle playback.
                                                schedule {
                                                    display.appendTtsText(text)
                                                }
                                            }
                                        }
                                    }
                                }
                                "stt" -> {
                                    val text = json.optString("text")
                                    if (text.isNotEmpty()) {
                                        Log.i(TAG, ">> $text")
                                        // Dedup: if the server echoes the exact text we just
                                        // typed via sendTextMessage(), drop it. We already
                                        // rendered it locally as a finalized user bubble.
                                        // Some server modes (legacy pipeline) echo the typed
                                        // text back as stt; Gemini Live does not. Either way,
                                        // we must show the bubble exactly once.
                                        val normalized = text.trim()
                                        val isEcho = synchronized(dedupOwnTypedEcho) {
                                            dedupOwnTypedEcho.remove(normalized)
                                        }
                                        if (isEcho) {
                                            Log.d(TAG, "Skipping duplicate echo of typed text: $normalized")
                                        } else {
                                            schedule {
                                                // Server sends cumulative STT - replace text in the same bubble
                                                // so the user sees one bubble per turn that updates word-by-word.
                                                // startStt() is idempotent: if a streaming bubble already exists
                                                // for the user, it just keeps using it.
                                                if (display.streamingIndex < 0) {
                                                    display.startStt()
                                                }
                                                display.updateSttText(text)
                                            }
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
                            player?.start(protocol!!.incomingAudioFlow)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            }
            protocol = null
            encoder = null
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
            // Register the typed text so a possible stt echo from the server
            // can be filtered out. We register the full text plus its growing
            // prefixes, because some servers echo the cumulative transcript
            // chunk by chunk (e.g. "xin", "xin chào", "xin chào bạn") — each
            // chunk is a prefix of the final text and must be ignored.
            rememberTypedEchoFor(text)

            // Display the typed text locally as a finalized user bubble.
            // Finalize any open streaming bubble (e.g. leftover STT/TTS) first
            // so the new user bubble doesn't overwrite it.
            schedule {
                display.finalizeLastMessage()
                display.setChatMessage("user", text)
            }
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

    // Current translation message (combines all segments into one complete sentence)
    private val _currentTranslation = MutableStateFlow<TranslationDisplay?>(null)
    val currentTranslation: StateFlow<TranslationDisplay?> = _currentTranslation.asStateFlow()

    // Index of the currently active streaming bubble (STT or TTS), or -1 if none.
    // Used to update the same bubble in-place instead of appending a new one each time.
    private var _streamingIndex: Int = -1

    /** Read-only view: -1 if no streaming bubble, else its index in [chatFlow]. */
    val streamingIndex: Int
        get() = _streamingIndex

    /** True when a streaming bubble is currently open (and can be appended/replaced). */
    val isStreaming: Boolean
        get() = _streamingIndex >= 0

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

    /**
     * Start a new streaming bubble for STT (user speech recognition).
     * Server sends cumulative text - each chunk is the full transcript so far.
     * Call updateSttText() with each new chunk. Call finalizeLastMessage() when done.
     */
    fun startStt() {
        // If a previous streaming bubble is still open, finalize it first
        finalizeLastMessage()
        val msg = Message(sender = "user", message = "", isStreaming = true)
        chatFlow.value = chatFlow.value + msg
        _streamingIndex = chatFlow.value.lastIndex
    }

    /**
     * Start a new streaming bubble for TTS (assistant response).
     * Idempotent: if an assistant TTS streaming bubble is already open, reuse it
     * (do NOT finalize and create a new one). This protects against the case where
     * sentence_start text arrives before tts.start (Gemini Live sends them out of
     * order since sentence_start runs in a background asyncio task and tts.start
     * runs in the main audio loop). Only finalize a different streaming bubble
     * (e.g. an open STT user bubble) when starting TTS.
     * Call appendTtsText() per segment. Call finalizeLastMessage() when TTS stops.
     */
    fun startTts() {
        val currentIdx = _streamingIndex
        if (currentIdx >= 0 && currentIdx < chatFlow.value.size) {
            val current = chatFlow.value[currentIdx]
            // If we already have an open TTS bubble, keep using it - don't drop its text
            if (current.sender == "assistant" && current.isStreaming) {
                return
            }
        }
        // Otherwise finalize the previous streaming bubble (e.g. open STT user bubble)
        // and open a new TTS streaming bubble.
        finalizeLastMessage()
        val msg = Message(sender = "assistant", message = "", isStreaming = true)
        chatFlow.value = chatFlow.value + msg
        _streamingIndex = chatFlow.value.lastIndex
    }

    /**
     * Replace the text in the current STT streaming bubble (server sends cumulative).
     * No-op if no streaming bubble exists.
     */
    fun updateSttText(text: String) {
        val idx = _streamingIndex
        if (idx < 0 || idx >= chatFlow.value.size) return
        val current = chatFlow.value[idx]
        if (current.sender != "user") return  // safety
        val updated = chatFlow.value.toMutableList()
        updated[idx] = current.copy(message = text)
        chatFlow.value = updated
    }

    /**
     * Append a sentence segment to the current TTS streaming bubble.
     * New segments join existing text with a space. If no TTS streaming bubble
     * exists yet (e.g. sentence_start arrived before tts.start), one is created.
     * If the current streaming bubble is a different sender (e.g. an open STT
     * user bubble), it is finalized first to avoid losing STT text.
     *
     * Also tracks segments for karaoke-style highlighting: when a new segment
     * arrives, all previous segments are marked as played (audio lags behind
     * text display, so this is the "catch-up" mark).
     */
    fun appendTtsText(text: String) {
        if (text.isBlank()) return
        val idx = _streamingIndex
        if (idx >= 0 && idx < chatFlow.value.size) {
            val current = chatFlow.value[idx]
            if (current.sender == "assistant" && current.isStreaming) {
                // Append to existing TTS bubble. Mark previous segments as played
                // (audio has caught up past them), keep the new one unplayed.
                val prevSegments = current.segments.orEmpty()
                val markedPrev = if (prevSegments.isNotEmpty()) {
                    prevSegments.map { it.copy(isPlayed = true) }
                } else {
                    // First time we see segments — backfill from existing text so
                    // highlighting kicks in even for the very first chunk.
                    if (current.message.isBlank()) emptyList()
                    else listOf(TtsSegment(text = current.message, isPlayed = true))
                }
                val newSegments = markedPrev + TtsSegment(text = text, isPlayed = false)
                val newMessage = if (current.message.isBlank()) text else current.message + " " + text
                val updated = chatFlow.value.toMutableList()
                updated[idx] = current.copy(message = newMessage, segments = newSegments)
                chatFlow.value = updated
                return
            }
            // Open bubble belongs to a different sender (e.g. STT user bubble);
            // finalize it first to preserve its text, then start TTS.
            finalizeLastMessage()
        }
        // No TTS streaming bubble yet - start one and append
        startTts()
        // startTts() should have created a new assistant streaming bubble
        val newIdx = _streamingIndex
        if (newIdx >= 0 && newIdx < chatFlow.value.size) {
            val updated = chatFlow.value.toMutableList()
            updated[newIdx] = updated[newIdx].copy(
                message = text,
                segments = listOf(TtsSegment(text = text, isPlayed = false))
            )
            chatFlow.value = updated
        }
    }

    /**
     * Mark the current streaming bubble as complete (no longer streaming).
     * Hides the streaming state and shows timestamp normally.
     * All segments are marked as played (no more highlight contrast needed).
     */
    fun finalizeLastMessage() {
        val idx = _streamingIndex
        if (idx < 0 || idx >= chatFlow.value.size) {
            _streamingIndex = -1
            return
        }
        val current = chatFlow.value[idx]
        if (current.isStreaming) {
            val updated = chatFlow.value.toMutableList()
            val finalized = current.copy(
                isStreaming = false,
                segments = current.segments?.map { it.copy(isPlayed = true) }
            )
            updated[idx] = finalized
            chatFlow.value = updated
        }
        _streamingIndex = -1
    }

    /**
     * Add a translation segment to the current translation message
     * Translation text is accumulated into one complete sentence
     */
    fun addTranslationSegment(text: String) {
        val current = _currentTranslation.value
        if (current == null) {
            // First segment - create new translation display
            _currentTranslation.value = TranslationDisplay(
                segments = listOf(TranslationSegmentDisplay(text = text, isPlayed = false)),
                isComplete = false
            )
        } else {
            // Add segment to existing translation
            _currentTranslation.value = current.copy(
                segments = current.segments + TranslationSegmentDisplay(text = text, isPlayed = false)
            )
        }
    }

    /**
     * Mark a translation segment as played (by index)
     */
    fun markTranslationSegmentPlayed(index: Int) {
        val current = _currentTranslation.value ?: return
        if (index < current.segments.size) {
            val updatedSegments = current.segments.toMutableList()
            updatedSegments[index] = updatedSegments[index].copy(isPlayed = true)
            _currentTranslation.value = current.copy(segments = updatedSegments)
        }
    }

    /**
     * Clear current translation (when translation mode ends)
     */
    fun clearCurrentTranslation() {
        _currentTranslation.value = null
    }

    /**
     * Mark translation as complete (all segments played)
     */
    fun completeTranslation() {
        val current = _currentTranslation.value ?: return
        _currentTranslation.value = current.copy(isComplete = true)
    }
}

/**
 * Translation display data for UI
 */
data class TranslationDisplay(
    val segments: List<TranslationSegmentDisplay>,
    val isComplete: Boolean = false
) {
    val fullText: String
        get() = segments.joinToString(" ") { it.text }

    val playedText: String
        get() = segments.filter { it.isPlayed }.joinToString(" ") { it.text }
}

data class TranslationSegmentDisplay(
    val text: String,
    val isPlayed: Boolean = false
)

val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

/**
 * A single sentence/segment in a streaming TTS message.
 * Server sends text in chunks via `sentence_start`; each chunk becomes one segment.
 * `isPlayed = true` once the segment has been spoken (marked when the NEXT
 * segment arrives, because audio lags behind text display).
 */
data class TtsSegment(
    val text: String,
    val isPlayed: Boolean = false
)

data class Message(
    val sender: String = "",
    val message: String = "",
    val nowInString: String = df.format(System.currentTimeMillis()),
    val imageUri: String? = null,
    val videoUri: String? = null,
    val audioUri: String? = null,
    val isSeparator: Boolean = false,
    // True for the active streaming bubble (STT or TTS in progress).
    // - STT: shows user speech being recognized in real time (1 bubble per turn, text replaced)
    // - TTS: shows assistant response accumulating sentence by sentence (1 bubble per turn, text appended)
    // False for completed/static bubbles (already finalized, timestamp visible).
    val isStreaming: Boolean = false,
    // Sentence-level segments for karaoke-style highlighting on TTS bubbles.
    // Null on non-streaming / non-TTS messages (renders as plain text).
    val segments: List<TtsSegment>? = null
)