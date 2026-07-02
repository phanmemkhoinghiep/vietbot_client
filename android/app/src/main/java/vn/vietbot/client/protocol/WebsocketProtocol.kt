package vn.vietbot.client.protocol
import android.util.Log
import vn.vietbot.client.data.model.DeviceInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

// WebsocketProtocol implementation
class WebsocketProtocol(private val deviceInfo: DeviceInfo,
                        private val url: String,
                        private val accessToken: String) : Protocol() {
    companion object {
        private const val TAG = "WS"
        private const val OPUS_FRAME_DURATION_MS = 60
    }

    private var isOpen: Boolean = false
    private var websocket: WebSocket? = null

    private val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    private val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, java.security.SecureRandom())
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // Disable read timeout for continuous WebSocket streaming
        .writeTimeout(0, TimeUnit.SECONDS) // Disable write timeout
        .pingInterval(30, TimeUnit.SECONDS) // WebSocket keepalive ping
        .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    val helloReceived = CompletableDeferred<Boolean>()

    // Store OTA response data for activation code check
    var otaActivationCode: String? = null
    var otaWebsocketUrl: String? = null
    var otaWebsocketToken: String? = null

    init {
        sessionId = "your_session_id" // Mock session_id, actual from system
    }

    override suspend fun start() {
        // Empty implementation, consistent with C++
    }

    override suspend fun sendAudio(data: ByteArray) {
        // Log.i(TAG, "Sending audio: ${data.size}")
        websocket?.run {
            send(ByteString.of(*data))
        } ?: Log.e(TAG, "WebSocket is null")
    }

    override suspend fun sendText(text: String) {
        Log.i(TAG, "Sending text: $text")
        websocket?.run {
            send(text)
        } ?: Log.e(TAG, "WebSocket is null")
    }

    override fun isAudioChannelOpened(): Boolean {
        return websocket != null && isOpen
    }

    override fun closeAudioChannel() {
        websocket?.close(1000, "Normal closure")
        websocket = null
    }

    override suspend fun openAudioChannel(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "openAudioChannel started on thread: ${Thread.currentThread().name}")

        // Check if device needs activation first
        if (otaActivationCode != null) {
            Log.w(TAG, "⚠️ Device needs activation: code=$otaActivationCode")
            // Device not registered, activation required
            return@withContext false
        }

        // Close old connection
        closeAudioChannel()

        try {
            // Create WebSocket request
            val request = Request.Builder()
                .url(otaWebsocketUrl ?: url)
                .addHeader("Authorization", "Bearer ${otaWebsocketToken ?: accessToken}")
                .addHeader("Protocol-Version", "1")
                .addHeader("Device-Id", deviceInfo.mac_address)
                .addHeader("Client-Id", deviceInfo.uuid)
                .build()
            Log.i(TAG, "WebSocket connecting to ${otaWebsocketUrl ?: url}")

            // Initialize WebSocket
            websocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isOpen = true
                    Log.i(TAG, "WebSocket connected on thread: ${Thread.currentThread().name}")
                    scope.launch {
                        audioChannelStateFlow.emit(AudioState.OPENED)
                    }

                    // Send Hello message
                    val helloMessage = JSONObject().apply {
                        put("type", "hello")
                        put("version", 1)
                        put("transport", "websocket")
                        put("client_type", "android")
                        put("audio_params", JSONObject().apply {
                            put("format", "opus")
                            put("sample_rate", 16000)
                            put("channels", 1)
                            put("frame_duration", OPUS_FRAME_DURATION_MS)
                        })
                        // Enable MCP support
                        put("features", JSONObject().apply {
                            put("mcp", true)
                        })
                    }
                    Log.i(TAG, "WebSocket hello: $helloMessage")
                    webSocket.send(helloMessage.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.i(TAG, "WebSocket message: $text")
                    scope.launch {
                        val json = JSONObject(text)
                        val type = json.optString("type")
                        when (type) {
                            "hello" -> parseServerHello(json)
                            else -> jsonChannel.send(json)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    scope.launch {
                        try {
                            val audioData = bytes.toByteArray()
                            Log.d(TAG, "📥 WebSocket audio received: ${audioData.size} bytes")
                            audioChannel.send(audioData)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to process audio frame", e)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WebSocket closing: $code: $reason")
                    super.onClosing(webSocket, code, reason)
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    isOpen = false;
                    Log.i(TAG, "WebSocket closed: $code: $reason")
                    scope.launch {
                        audioChannelStateFlow.emit(AudioState.CLOSED)
                    }
                    websocket = null
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    isOpen = false;
                    Log.e(TAG, "WebSocket error: ${t.message}", t)
                    scope.launch {
                        networkErrorFlow.emit("Server not found")
                    }
                    websocket = null
                }
            })

            // Wait for server Hello
            try {
                withTimeout(10000) {
                    Log.i(TAG, "Waiting for server hello")
                    helloReceived.await()
                    Log.i(TAG, "Server hello received")
                    true
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Failed to receive server hello - timeout")
                networkErrorFlow.emit("Server timeout")
                closeAudioChannel()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in openAudioChannel: ${e.message}", e)
            false
        }
    }

    private fun parseServerHello(root: JSONObject) {
        val transport = root.optString("transport")
        if (transport != "websocket") {
            Log.e(TAG, "Unsupported transport: $transport")
            return
        }

        val audioParams = root.optJSONObject("audio_params")
        audioParams?.let {
            val sampleRate = it.optInt("sample_rate", -1)
            if (sampleRate != -1) {
                serverSampleRate = sampleRate
            }
        }
        sessionId = root.optString("session_id")


        helloReceived.complete(true)
    }

    // Clean up resources
    override fun dispose() {
        scope.cancel()
        closeAudioChannel()
        client.dispatcher.executorService.shutdown()
    }

    private var serverSampleRate: Int = -1 // Mock C++ member variable
}