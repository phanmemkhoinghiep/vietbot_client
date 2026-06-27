package vn.vietbot.client.protocol

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Mock Protocol base class enums and constants
enum class AbortReason { WAKE_WORD_DETECTED, NONE }
enum class ListeningMode { ALWAYS_ON, AUTO_STOP, MANUAL }
enum class AudioState { OPENED, CLOSED }

// Protocol abstract class (corresponds to C++ Protocol)
abstract class Protocol {
    protected var sessionId: String = "" // Session ID
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Callbacks changed to Flow.
    // Use Channel (not MutableSharedFlow) to guarantee strict FIFO ordering when
    // multiple threads emit concurrently. MutableSharedFlow with concurrent senders
    // can deliver messages out of order, causing lost text in STT/TTS streaming.
    //
    // receiveAsFlow() returns a Flow that consumes the Channel in strict FIFO order.
    // Single-consumer semantics: only one collector can consume the channel at a time.
    val incomingJsonFlow: kotlinx.coroutines.flow.Flow<JSONObject> = kotlinx.coroutines.channels.Channel<JSONObject>(
        kotlinx.coroutines.channels.Channel.BUFFERED
    ).also { jsonChannel = it }.receiveAsFlow()
    val incomingAudioFlow: kotlinx.coroutines.flow.Flow<ByteArray> = kotlinx.coroutines.channels.Channel<ByteArray>(
        kotlinx.coroutines.channels.Channel.BUFFERED
    ).also { audioChannel = it }.receiveAsFlow()
    val audioChannelStateFlow = MutableSharedFlow<AudioState>()
    val networkErrorFlow = MutableSharedFlow<String>()

    // Backing channels for the FIFO flows above. Subclasses call
    // jsonChannel.send(...) and audioChannel.send(...) directly to enqueue.
    protected lateinit var jsonChannel: kotlinx.coroutines.channels.Channel<JSONObject>
    protected lateinit var audioChannel: kotlinx.coroutines.channels.Channel<ByteArray>

    abstract suspend fun start()
    abstract suspend fun sendAudio(data: ByteArray)
    abstract suspend fun openAudioChannel(): Boolean
    abstract fun closeAudioChannel()
    abstract fun isAudioChannelOpened(): Boolean
    abstract suspend fun sendText(text: String)

    suspend fun sendAbortSpeaking(reason: AbortReason) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "abort")
            if (reason == AbortReason.WAKE_WORD_DETECTED) put("reason", "wake_word_detected")
        }
        sendText(json.toString())
    }

    suspend fun sendWakeWordDetected(wakeWord: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "detect")
            put("text", wakeWord)
        }
        sendText(json.toString())
    }

    suspend fun sendStartListening(mode: ListeningMode) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "start")
            put("mode", when (mode) {
                ListeningMode.ALWAYS_ON -> "realtime"
                ListeningMode.AUTO_STOP -> "auto"
                ListeningMode.MANUAL -> "manual"
            })
        }
        sendText(json.toString())
    }

    suspend fun sendStopListening() {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "listen")
            put("state", "stop")
        }
        sendText(json.toString())
    }

    suspend fun sendIotDescriptors(descriptors: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("descriptors", JSONObject(descriptors))
        }
        sendText(json.toString())
    }

    suspend fun sendIotStates(states: String) {
        val json = JSONObject().apply {
            put("session_id", sessionId)
            put("type", "iot")
            put("states", JSONObject(states))
        }
        sendText(json.toString())
    }

    abstract fun dispose()
}

