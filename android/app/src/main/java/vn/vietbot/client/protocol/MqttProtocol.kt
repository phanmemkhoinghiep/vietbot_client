package vn.vietbot.client.protocol

import android.content.Context
import android.util.Log
import vn.vietbot.client.data.model.MqttConfig
import info.mqtt.android.service.MqttAndroidClient
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MqttProtocol(
    private val context: Context,
    private val mqttConfig: MqttConfig
) : Protocol() {
    private val TAG = "MqttProtocol"
    private var mqttClient: MqttAndroidClient? = null
    private var udpClient: UdpClient? = null
    private val channelMutex = Any()
    private var isReconnecting = false
    private var pendingHello = false

    // Use broker URL from config (includes protocol and port)
    private var endpoint: String = mqttBrokerUrl
    private var clientId: String = mqttConfig.clientId
    private var username: String = mqttConfig.username
    private var password: String = mqttConfig.password
    private var publishTopic: String = mqttConfig.publishTopic
    private var subscribeTopic: String = mqttConfig.subscribeTopic

    private lateinit var aesKey: SecretKeySpec
    private var aesNonce: ByteArray = ByteArray(16)
    private var localSequence: Long = 0
    private var remoteSequence: Long = 0

    // Extract host from endpoint (may include port), then use mqttConfig.port
    private val mqttBrokerUrl: String
        get() {
            val host = mqttConfig.endpoint.removePrefix("tcp://").removePrefix("ssl://")
                .substringBefore(":")  // Strip any existing port
            return if (mqttConfig.port == 8883) "ssl://$host:${mqttConfig.port}"
                   else "tcp://$host:${mqttConfig.port}"
        }

    override suspend fun start() {
        startMqttClient()
    }

    private suspend fun startMqttClient() = withContext(Dispatchers.IO) {
        if (mqttClient?.isConnected == true) {
            Log.w(TAG, "MQTT client already started")
            mqttClient?.disconnect()
        }

        if (endpoint.isEmpty()) {
            Log.e(TAG, "MQTT endpoint is not specified")
            networkErrorFlow.emit("Server not found")
            return@withContext
        }

        mqttClient = MqttAndroidClient(context, endpoint, clientId).apply {
            setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    Log.i(TAG, "Connected to endpoint: $serverURI (reconnect=$reconnect)")
                    // Subscribe to receive responses
                    try {
                        this@apply.subscribe(subscribeTopic, 1)
                        Log.i(TAG, "Subscribed to topic: $subscribeTopic")
                        // If this is a reconnect and we were waiting for hello, re-send it
                        if (reconnect && pendingHello) {
                            Log.i(TAG, "Reconnected, re-sending hello after brief delay")
                            scope.launch {
                                delay(500)
                                sendPendingHello()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to subscribe", e)
                    }
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.i(TAG, "Disconnected from endpoint")
                    scope.launch { networkErrorFlow.emit("Connection lost") }
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val payload = String(message.payload)
                    Log.d(TAG, "Message on topic '$topic': $payload")
                    scope.launch {
                        try {
                            val json = JSONObject(payload)
                            when (json.optString("type")) {
                                "hello" -> parseServerHello(json)
                                "goodbye" -> {
                                    val sid = json.optString("session_id")
                                    if (sid.isEmpty() || sid == sessionId) {
                                        closeAudioChannel()
                                    }
                                }
                                "mcp" -> {
                                    // Forward MCP messages to incomingJsonFlow for unified handling
                                    // ChatViewModel will process these via its collector
                                    jsonChannel.send(json)
                                }
                                else -> jsonChannel.send(json)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse message: $payload", e)
                        }
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
        }

        val options = MqttConnectOptions().apply {
            keepAliveInterval = 90
            isAutomaticReconnect = false  // Disabled: Paho reflection crashes on Android 15
            userName = username
            password = this@MqttProtocol.password.toCharArray()
            connectionTimeout = 30
        }

        try {
            mqttClient?.connect(options)?.waitForCompletion()
            Log.i(TAG, "Connecting to endpoint $endpoint")
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to connect to endpoint", e)
            networkErrorFlow.emit("Server not connected")
        }
    }

    override suspend fun sendAudio(data: ByteArray) {
        synchronized(channelMutex) {
            if (udpClient == null) return

            val nonce = aesNonce.copyOf().apply {
                val size = data.size.toShort()
                this[2] = (size.toInt() shr 8).toByte()
                this[3] = size.toByte()
                val seq = (++localSequence).toInt()
                this[12] = (seq shr 24).toByte()
                this[13] = (seq shr 16).toByte()
                this[14] = (seq shr 8).toByte()
                this[15] = seq.toByte()
            }

            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, aesKey, javax.crypto.spec.IvParameterSpec(nonce))
            }
            val encrypted = cipher.doFinal(data)
            val packet = nonce + encrypted
            udpClient?.send(packet)
        }
    }

    override suspend fun openAudioChannel(): Boolean = withContext(Dispatchers.IO) {
        if (mqttClient?.isConnected != true) {
            Log.i(TAG, "MQTT is not connected, trying to connect now")
            startMqttClient()
            if (mqttClient?.isConnected != true) return@withContext false
        }

        sessionId = ""
        val helloMessage = JSONObject().apply {
            put("type", "hello")
            put("version", 3)
            put("transport", "udp")
            put("audio_params", JSONObject().apply {
                put("format", "opus")
                put("sample_rate", 16000)
                put("channels", 1)
                put("frame_duration", 20)
            })
        }
        pendingHello = true
        sendText(helloMessage.toString())
        Log.i(TAG, "Sent hello message, waiting for response...")

        suspendCoroutine { cont ->
            scope.launch {
                delay(10000)
                if (sessionId.isEmpty()) {
                    Log.e(TAG, "Failed to receive server hello")
                    pendingHello = false
                    networkErrorFlow.emit("Server timeout")
                    cont.resume(false)
                } else {
                    pendingHello = false
                    Log.i(TAG, "Received server hello, session established")
                    cont.resume(true)
                }
            }
        }
    }

    private suspend fun sendPendingHello() {
        val helloMessage = JSONObject().apply {
            put("type", "hello")
            put("version", 3)
            put("transport", "udp")
            put("audio_params", JSONObject().apply {
                put("format", "opus")
                put("sample_rate", 16000)
                put("channels", 1)
                put("frame_duration", 20)
            })
        }
        sendText(helloMessage.toString())
        Log.i(TAG, "Re-sent hello message after reconnect")
    }

    override fun closeAudioChannel() {
        synchronized(channelMutex) {
            udpClient?.close()
            udpClient = null
        }
        pendingHello = false
        scope.launch {
            val goodbyeMessage = JSONObject().apply {
                put("session_id", sessionId)
                put("type", "goodbye")
            }
            sendText(goodbyeMessage.toString())
            audioChannelStateFlow.emit(AudioState.CLOSED)
        }
    }

    override fun isAudioChannelOpened(): Boolean = udpClient != null

    override suspend fun sendText(text: String) {
        if (publishTopic.isEmpty() || mqttClient?.isConnected != true) return
        try {
            mqttClient?.publish(publishTopic, text.toByteArray(), 0, false)  // QoS 0 only!
        } catch (e: MqttException) {
            Log.e(TAG, "Failed to publish message", e)
            networkErrorFlow.emit("Server error")
        }
    }

    private suspend fun parseServerHello(json: JSONObject) {
        if (json.optString("transport") != "udp") {
            Log.e(TAG, "Unsupported transport: ${json.optString("transport")}")
            networkErrorFlow.emit("Unsupported transport")
            return
        }

        sessionId = json.optString("session_id")
        Log.i(TAG, "Session ID: $sessionId")

        val udp = json.optJSONObject("udp") ?: run {
            networkErrorFlow.emit("UDP not specified")
            return
        }
        val server = udp.optString("server")
        val port = udp.optInt("port")
        val key = decodeHexString(udp.optString("key"))
        aesNonce = decodeHexString(udp.optString("nonce"))
        aesKey = SecretKeySpec(key, "AES")

        synchronized(channelMutex) {
            udpClient?.close()
            udpClient = UdpClient(server, port).apply {
                setOnMessage { data ->
                    scope.launch {
                        if (data.size < aesNonce.size) {
                            Log.e(TAG, "Invalid audio packet size: ${data.size}")
                            return@launch
                        }
                        if (data[0].toInt() != 1) {
                            Log.e(TAG, "Invalid audio packet type: ${data[0]}")
                            return@launch
                        }
                        // Parse sequence as UNSIGNED (Java bytes are signed!)
                        val sequence = (((data[12].toInt() and 0xFF) shl 24) or
                                ((data[13].toInt() and 0xFF) shl 16) or
                                ((data[14].toInt() and 0xFF) shl 8) or
                                (data[15].toInt() and 0xFF)).toLong()

                        // Skip only truly old packets
                        if (remoteSequence > 0 && sequence < remoteSequence - 1000) {
                            Log.w(TAG, "Old sequence: $sequence, expected: $remoteSequence, skipping")
                            return@launch
                        }

                        try {
                            val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
                                init(Cipher.DECRYPT_MODE, aesKey, javax.crypto.spec.IvParameterSpec(data.copyOfRange(0, aesNonce.size)))
                            }
                            val decrypted = cipher.doFinal(data, aesNonce.size, data.size - aesNonce.size)
                            audioChannel.send(decrypted)
                            remoteSequence = sequence
                        } catch (e: Exception) {
                            Log.e(TAG, "Audio decrypt failed, seq=$sequence", e)
                        }
                    }
                }
            }
        }
        audioChannelStateFlow.emit(AudioState.OPENED)
    }

    private fun decodeHexString(hexString: String): ByteArray {
        return hexString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private suspend fun handleMcpMessage(json: JSONObject) {
        val payload = json.optJSONObject("payload") ?: return
        val method = payload.optString("method")
        val id = payload.optInt("id")

        Log.i(TAG, "Received MCP method: $method (id=$id)")

        // Respond to initialize with result
        if (method == "initialize") {
            val response = JSONObject().apply {
                put("type", "mcp")
                put("payload", JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("result", JSONObject().apply {
                        put("protocolVersion", "2024-11-05")
                        put("capabilities", JSONObject())
                        put("serverInfo", JSONObject().apply {
                            put("name", "vietbot-android")
                            put("version", "1.0.0")
                        })
                    })
                })
            }
            sendText(response.toString())
            Log.i(TAG, "Sent MCP initialize response")
        }

        // Handle tools/list request from server
        if (method == "tools/list") {
            // MqttProtocol uses a static method without access to McpServer
            // Forward to ChatViewModel via incomingJsonFlow for processing
            Log.i(TAG, "MCP tools/list received via MQTT - forwarding for processing")
            // The tools/list will be handled by ChatViewModel's incomingJsonFlow collector
        }
    }

    override fun dispose() {
        scope.cancel()
        mqttClient?.disconnect()
        udpClient?.close()
    }
}

class UdpClient(
    private val server: String,
    private val port: Int
) {
    private val TAG = "UdpClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: DatagramSocket? = null
    private var serverAddress: InetAddress? = null
    private var isRunning = false
    private var onMessage: ((ByteArray) -> Unit)? = null

    init {
        try {
            serverAddress = InetAddress.getByName(server)
            // Let system pick free port
            socket = DatagramSocket()
            isRunning = true
            startReceiving()
            Log.i(TAG, "UDP initialized: local=${socket?.localPort} -> $server:$port")
        } catch (e: Exception) {
            Log.e(TAG, "UDP init failed: $server:$port", e)
            close()
        }
    }

    fun send(data: ByteArray) {
        val sock = socket
        val addr = serverAddress
        if (!isRunning || sock == null || addr == null) return

        try {
            val packet = DatagramPacket(data, data.size, addr, port)
            sock.send(packet)
        } catch (e: Exception) {
            // Silent fail - audio loss acceptable
        }
    }

    fun setOnMessage(callback: (ByteArray) -> Unit) {
        onMessage = callback
    }

    private fun startReceiving() {
        scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning) {
                val sock = socket ?: break
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    sock.receive(packet)
                    onMessage?.invoke(packet.data.copyOf(packet.length))
                } catch (e: java.net.SocketTimeoutException) {
                    // Normal
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        }
    }

    fun close() {
        isRunning = false
        try { socket?.close() } catch (e: Exception) {}
        socket = null
    }
}

