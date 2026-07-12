package vn.vietbot.client

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import vn.vietbot.client.audio.AudioDeviceSelector
import vn.vietbot.client.data.MicSource
import vn.vietbot.client.data.SettingsRepository

class AudioRecorder(
    private val sampleRate: Int,
    private val channels: Int,
    private val frameSizeMs: Int,
    private val settingsRepository: SettingsRepository,
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioRecorder"
    }

    private val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        AudioFormat.ENCODING_PCM_16BIT
    ) * 2
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private val frameSize = (sampleRate * frameSizeMs) / 1000
    private val frameBytes = frameSize * channels * 2 // 16-bit PCM
    private val audioChannel = Channel<ByteArray>(capacity = 50)


    @SuppressLint("MissingPermission")
    fun startRecording(): Flow<ByteArray> {
        // 🔥 ECHO LOOP FIX: Use VOICE_COMMUNICATION audio source for built-in
        // hardware-level AEC. MIC source has weaker echo cancellation and lets
        // TTS playback leak back into mic → Gemini transcribes own output → repeats.

        // Apply user-selected mic source via setPreferredDevice (API 23+, minSdk 24).
        val preferredDevice = AudioDeviceSelector.findInputDevice(context, settingsRepository.micSource)
        Log.i(TAG, "Selected mic source: ${settingsRepository.micSource}, device=${preferredDevice?.productName ?: "default"}")

        audioRecord = try {
            val builder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)

            // Build first, then apply setPreferredDevice() on the AudioRecord itself (API 23+).
            // AudioRecord.Builder.setPreferredDevice() is API 26+, AudioRecord.setPreferredDevice() is API 23+.
            val ar = builder.build()
            if (preferredDevice != null && android.os.Build.VERSION.SDK_INT >= 23) {
                ar.setPreferredDevice(preferredDevice)
                Log.i(TAG, "Set preferred input device: ${preferredDevice.productName}")
            }
            ar
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.Builder failed (${e.message}), falling back to legacy constructor")
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        }.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                // Software AEC on top of hardware AEC (VOICE_COMMUNICATION source)
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(audioSessionId).apply {
                        enabled = true
                        Log.i(TAG, "AEC initialized (sessionId=$audioSessionId)")
                    }
                } else {
                    Log.w(TAG, "AEC not available on this device")
                }

                if(NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(audioSessionId).apply {
                        enabled = true
                        Log.i(TAG, "NS initialized")
                    }
                } else {
                    Log.w(TAG, "NS not available on this device")
                }

                // AutoGainControl helps normalize mic level for translation mode
                if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                    try {
                        val agc = android.media.audiofx.AutomaticGainControl.create(audioSessionId)
                        agc?.enabled = true
                        Log.i(TAG, "AGC initialized")
                    } catch (e: Exception) {
                        Log.w(TAG, "AGC init failed: ${e.message}")
                    }
                }

                startRecording()
                Log.i(TAG, "Started recording")
            } else {
                throw IllegalStateException("Failed to initialize AudioRecord")
            }
        }

        Thread {
            val buffer = ByteArray(frameBytes)
            while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                val read = audioRecord?.read(buffer, 0, frameBytes) ?: 0
                if (read > 0) {
                    audioChannel.trySend(buffer.copyOf(read)).isSuccess
                }
            }
        }.start()

        return audioChannel.receiveAsFlow()
    }

    fun stopRecording() {
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        aec?.enabled = false
        aec?.release()
        aec = null
        ns?.enabled = false
        ns?.release()
        ns = null
        audioChannel.close()
        Log.i(TAG, "Stopped recording")
    }
}