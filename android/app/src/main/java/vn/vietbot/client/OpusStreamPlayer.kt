package vn.vietbot.client

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class OpusStreamPlayer(
    private val sampleRate: Int,
    private val channels: Int,
    private val frameSizeMs: Int
) {
    companion object {
        private const val TAG = "OpusStreamPlayer"
        // Jitter buffer: 8 PCM frames = ~480ms @ 60ms/frame
        // Mirrors ESP32's MAX_PLAYBACK_TASKS_IN_QUEUE to decouple decode from output
        private const val PLAYBACK_QUEUE_CAPACITY = 8
    }

    private var audioTrack: AudioTrack
    private val playerScope = CoroutineScope(Dispatchers.IO + Job())
    private var isPlaying = false
    private var decoder: OpusDecoder? = null

    // Jitter buffer: holds decoded PCM between decode-coroutine and output-coroutine
    // Backed by Channel for FIFO ordering (matches ESP32's audio_playback_queue_)
    private val playbackQueue = Channel<ByteArray>(capacity = PLAYBACK_QUEUE_CAPACITY)

    init {
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 4 // Increase buffer size 4x for streaming headroom (was 2x - caused underflow)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start(opusFlow: Flow<ByteArray>) {
        if (!isPlaying) {
            isPlaying = true
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.play()
            }

            // Create decoder here - dedicated to this player instance
            decoder = OpusDecoder(sampleRate, channels, frameSizeMs)
            Log.i(TAG, "OpusStreamPlayer started: sampleRate=$sampleRate channels=$channels frameSizeMs=$frameSizeMs")

            // Coroutine 1: Decode (opus → PCM, push to playback queue)
            // Runs independently - decode latency doesn't stall output
            playerScope.launch {
                try {
                    opusFlow.collect { opusData ->
                        Log.d(TAG, "📥 Decoding opus frame: ${opusData.size} bytes")
                        val pcmData = decoder?.decode(opusData)
                        if (pcmData != null) {
                            // Suspends if queue full (backpressure) - prevents OOM
                            playbackQueue.send(pcmData)
                            Log.d(TAG, "✅ PCM enqueued: ${pcmData.size} bytes")
                        } else {
                            Log.w(TAG, "⚠️ Decode returned null")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Decode coroutine error", e)
                }
            }

            // Coroutine 2: Output (pop PCM → audioTrack.write)
            // Dedicated to playback - blocking write doesn't stall decode
            playerScope.launch {
                try {
                    for (pcmData in playbackQueue) {
                        Log.d(TAG, "🔊 Writing PCM to AudioTrack: ${pcmData.size} bytes")
                        audioTrack.write(pcmData, 0, pcmData.size)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Output coroutine error", e)
                }
            }
        } else {
            Log.w(TAG, "start() called but already playing")
        }
    }

    fun stop() {
        if (isPlaying) {
            isPlaying = false
            if (audioTrack.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack.stop()
            }
        }
    }

    fun release() {
        stop()
        playbackQueue.close()
        audioTrack.release()
    }

    suspend fun waitForPlaybackCompletion() {
        var position = 0
        while (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING && audioTrack.playbackHeadPosition != position) {
            Log.i(TAG, "audioTrack.playState: ${audioTrack.playState}, playbackHeadPosition: ${audioTrack.playbackHeadPosition}")
            position = audioTrack.playbackHeadPosition
            delay(100) // Check interval
        }
    }

    protected fun finalize() {
        release()
    }
}