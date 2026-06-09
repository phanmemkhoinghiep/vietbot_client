package vn.vietbot.client.mcp

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioStreamManager - Quản lý audio streaming từ mic kính
 *
 * Khi kính HeyCyan đã pair với điện thoại (HFP profile),
 * AudioSource.VOICE_RECOGNITION tự động dùng mic của kính.
 */
@Singleton
class AudioStreamManager @Inject constructor() {

    companion object {
        private const val TAG = "AudioStreamManager"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var isStreaming = false
    private var streamingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val bufferSize: Int

    val isStreamingFlow = MutableStateFlow(false)

    init {
        bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        Log.i(TAG, "AudioStreamManager init: bufferSize=$bufferSize")
    }

    /**
     * Bắt đầu streaming audio từ mic kính
     * @param sourceLang Ngôn ngữ nguồn (mặc định: en)
     * @param targetLang Ngôn ngữ đích (mặc định: vi)
     */
    fun startStreaming(sourceLang: String, targetLang: String) {
        if (isStreaming) {
            Log.w(TAG, "Already streaming, ignore start")
            return
        }

        try {
            // AudioSource.VOICE_RECOGNITION = mic kính khi pair HFP
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isStreaming = true
            isStreamingFlow.value = true

            Log.i(TAG, "Started streaming: VOICE_RECOGNITION source ($sourceLang → $targetLang)")

            // Coroutine-based audio capture
            streamingJob = scope.launch {
                val buffer = ByteArray(bufferSize)
                while (isActive && isStreaming) {
                    val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                    if (read > 0) {
                        // Gửi chunk PCM qua protocol.sendAudio() - đã có sẵn
                        protocol?.sendAudio(buffer.copyOf(read))
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start streaming", e)
            stopStreaming()
        }
    }

    /**
     * Dừng streaming audio
     */
    fun stopStreaming() {
        isStreaming = false
        isStreamingFlow.value = false

        try {
            streamingJob?.cancel()
            streamingJob = null
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        Log.i(TAG, "Stopped streaming")
    }

    /**
 * Đăng ký protocol để gửi audio
 */
    var protocol: vn.vietbot.client.protocol.Protocol? = null
}