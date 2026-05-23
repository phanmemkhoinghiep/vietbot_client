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
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatViewMode @Inject constructor(
    @ApplicationContext private val context: Context,
    @NavigationEvents private val navigationEvents: MutableSharedFlow<String>,
    deviceInfo: DeviceInfo,
    settings: SettingsRepository
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

    fun connect() {
        if (_isConnected.value || _isConnecting.value) return

        viewModelScope.launch {
            _isConnecting.value = true
            deviceState = DeviceState.CONNECTING

            protocol = when (settings.transportType) {
                TransportType.MQTT -> {
                    MqttProtocol(context, settings.mqttConfig!!)
                }
                TransportType.WebSockets -> {
                    WebsocketProtocol(deviceInfo, settings.webSocketUrl ?: "wss://vietbot.vn/ws/", "test-token")
                }
            }

            try {
                protocol?.start()

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

                if (!audioJobStarted) {
                    audioJobStarted = true
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
                            }
                        }
                    }
                }

                _isConnected.value = true
                _isConnecting.value = false
                deviceState = DeviceState.LISTENING

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
                encoder?.release()
                player?.stop()
                decoder?.release()
                protocol?.dispose()
            } catch (e: Exception) {
                Log.e(TAG, "Error during disconnect", e)
            }
            protocol = null
            encoder = null
            decoder = null
            recorder = null
            player = null
            _isConnected.value = false
            _isConnecting.value = false
            deviceState = DeviceState.IDLE
            audioJobStarted = false
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
            // Display user message
            display.setChatMessage("user", text)
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
    fun setChatMessage(sender: String, message: String) {
        chatFlow.value = chatFlow.value + Message(sender, message)
    }

    fun setEmotion(emotion: String) {
        emotionFlow.value = emotion
    }
}

val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

data class Message(
    val sender: String = "",
    val message: String = "",
    val nowInString: String = df.format(System.currentTimeMillis())
)
