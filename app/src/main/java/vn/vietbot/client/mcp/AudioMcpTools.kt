package vn.vietbot.client.mcp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Audio MCP Tools for Android
 * Provides tools for:
 * - Starting/stopping audio recording
 * - Long audio recording (no time limit)
 * - Getting recording status
 */
class AudioMcpTools(private val context: Context) {

    companion object {
        private const val TAG = "AudioMcpTools"
        private const val MAX_SHORT_RECORDING_SECONDS = 60
    }

    // Recording state
    private var isRecording = false
    private var isLongRecording = false
    private var isRecordingPaused = false

    // Current recording file
    private var currentRecordingFile: File? = null
    private var currentRecordingPath: String? = null
    private var recordingStartTime: Long = 0
    private var recordingDurationSeconds: Int = 0

    // Audio recording components
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Recording buffer
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    private val cameraScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("AudioRecordBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    /**
     * Get all available audio tools
     */
    fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "self.audio.start_recording",
            description = """🎙️ BẮT ĐẦU GHI ÂM - Ghi âm âm thanh trong thời gian ngắn (tối đa 60 giây)

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Ghi âm", "Bắt đầu ghi âm", "Bắt đầu thu âm"
- Người dùng muốn ghi lại giọng nói/cuộc họp/câu chuyện
- Người dùng cần lưu lại âm thanh ngắn

GHI CHÚ:
- Tự động dừng sau 60 giây nếu chưa gọi stop
- File lưu vào thư mục VietBot/Audio trong điện thoại""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.audio.stop_recording",
            description = """⏹️ DỪNG GHI ÂM - Dừng ghi âm đang chạy và lưu file

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Dừng ghi âm", "Dừng thu âm", "Kết thúc ghi âm"
- Người dùng muốn lưu lại bản ghi âm vừa tạo
- Sau khi đã gọi start_recording

GHI CHÚ:
- File sẽ được lưu vào thư mục VietBot/Audio trong điện thoại
- Trả về đường dẫn file và thời lượng ghi âm""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.audio.start_recording_long",
            description = """🎙️ GHI ÂM DÀI - Bắt đầu ghi âm không giới hạn thời gian

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Bắt đầu ghi âm dài", "Ghi âm không giới hạn", "Ghi âm liên tục"
- Người dùng cần ghi âm cuộc họp/cuộc trò chuyện dài
- Người dùng muốn ghi âm cho đến khi chủ động dừng

GHI CHÚ:
- Có thể ghi âm liên tục vài giờ
- PHẢI gọi stop_recording_long để kết thúc
- Tự động dừng nếu app bị tắt hoặc chuyển sang ứng dụng khác""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.audio.stop_recording_long",
            description = """⏹️ DỪNG GHI ÂM DÀI - Dừng ghi âm dài đang chạy và lưu file

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Dừng ghi âm dài", "Kết thúc ghi âm", "Ngừng ghi"
- Người dùng đã hoàn thành việc ghi âm
- Sau khi đã gọi start_recording_long

GHI CHÚ:
- File sẽ được lưu vào thư mục VietBot/Audio trong điện thoại
- Trả về đường dẫn file, thời lượng và kích thước file""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.audio.get_recording_status",
            description = """📊 KIỂM TRA TRẠNG THÁI GHI ÂM - Kiểm tra xem có đang ghi âm không

BAT BUOC GOI FUNCTION KHI:
- Người dùng muốn biết có đang ghi âm không
- Kiểm tra trước khi bắt đầu ghi âm mới
- Kiểm tra trạng thái hệ thống ghi âm""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        )
    )

    /**
     * Execute an audio tool by name
     */
    suspend fun executeTool(toolName: String, args: JSONObject): McpToolResult {
        return when (toolName) {
            "self.audio.start_recording" -> startRecording(false)
            "self.audio.stop_recording" -> stopRecording()
            "self.audio.start_recording_long" -> startRecording(true)
            "self.audio.stop_recording_long" -> stopLongRecording()
            "self.audio.get_recording_status" -> getRecordingStatus()
            else -> McpToolResult(
                content = listOf(McpContent.TextContent("Unknown tool: $toolName")),
                isError = true
            )
        }
    }

    /**
     * Check if currently recording (short or long)
     */
    fun isRecording(): Boolean = isRecording || isLongRecording

    /**
     * Stop all recording immediately (synchronous, used for lifecycle events)
     * This does NOT wait for save operation to complete
     */
    fun stopAllRecordingImmediate() {
        if (isLongRecording || isRecording) {
            // Stop audio recording immediately
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping audio record immediately", e)
            }

            recordingJob?.cancel()
            recordingJob = null

            isRecording = false
            isLongRecording = false
            Log.i(TAG, "Stopped recording immediately due to lifecycle event")

            // Note: File may not be saved properly, but recording is stopped
            // This is intentional for lifecycle events
        }
    }

    /**
     * Stop all recording (used for lifecycle events)
     */
    suspend fun stopAllRecording(): McpToolResult {
        return if (isLongRecording) {
            stopLongRecording()
        } else if (isRecording) {
            stopRecording()
        } else {
            McpToolResult(
                content = listOf(McpContent.TextContent("No recording in progress"))
            )
        }
    }

    /**
     * Start audio recording (short or long mode)
     */
    private suspend fun startRecording(isLongRecording: Boolean): McpToolResult {
        // Check if already recording
        if (this.isRecording || this.isLongRecording) {
            return McpToolResult(
                content = listOf(McpContent.TextContent(
                    if (this.isLongRecording) "Already recording long audio. Please stop first."
                    else "Already recording. Please stop first."
                )),
                isError = true
            )
        }

        // Check microphone permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("Microphone permission not granted")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
            try {
                @Suppress("MissingPermission")
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Failed to initialize audio recorder")),
                        isError = true
                    ))
                    return@suspendCancellableCoroutine
                }

                // Create temp file
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val prefix = if (isLongRecording) "VIETBOT_LONG_AUDIO" else "VIETBOT_AUDIO"
                val tempFile = File(context.cacheDir, "${prefix}_$timestamp.pcm")
                currentRecordingFile = tempFile

                // Start recording
                audioRecord?.startRecording()
                recordingStartTime = System.currentTimeMillis()
                this.isRecording = !isLongRecording
                this.isLongRecording = isLongRecording

                // Start recording loop
                recordingJob = cameraScope.launch(Dispatchers.IO) {
                    var outputStream: FileOutputStream? = null
                    try {
                        outputStream = FileOutputStream(tempFile)
                        val buffer = ByteArray(bufferSize)

                        while (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val read = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                            if (read > 0) {
                                outputStream.write(buffer, 0, read)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Recording error", e)
                    } finally {
                        outputStream?.close()
                    }
                }

                // If short recording, schedule auto-stop
                if (!isLongRecording) {
                    backgroundHandler?.postDelayed({
                        if (isRecording) {
                            cameraScope.launch { stopRecording() }
                        }
                    }, (MAX_SHORT_RECORDING_SECONDS * 1000).toLong())
                }

                Log.i(TAG, "Started ${if (isLongRecording) "long" else "short"} recording")

                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent(
                        if (isLongRecording) "Bắt đầu ghi âm dài. Sẽ ghi cho đến khi bạn yêu cầu dừng."
                        else "Bắt đầu ghi âm. Sẽ tự động dừng sau ${MAX_SHORT_RECORDING_SECONDS} giây nếu không dừng tay."
                    ))
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Start recording error", e)
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("Failed to start recording: ${e.message}")),
                    isError = true
                ))
            }
        }
    }

    /**
     * Stop short recording and save to gallery
     */
    private suspend fun stopRecording(): McpToolResult {
        if (!isRecording) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("No short recording in progress")),
                isError = true
            )
        }

        return stopRecordingInternal("VIETBOT_AUDIO")
    }

    /**
     * Stop long recording and save to gallery
     */
    private suspend fun stopLongRecording(): McpToolResult {
        if (!isLongRecording) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("No long recording in progress")),
                isError = true
            )
        }

        return stopRecordingInternal("VIETBOT_LONG_AUDIO")
    }

    /**
     * Internal stop recording logic
     */
    private suspend fun stopRecordingInternal(prefix: String): McpToolResult {
        // Stop recording
        val duration = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
        recordingDurationSeconds = duration

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio record", e)
        }

        recordingJob?.cancel()
        recordingJob = null

        isRecording = false
        isLongRecording = false

        // Save PCM to WAV and gallery
        val result = withContext(Dispatchers.IO) {
            try {
                val pcmFile = currentRecordingFile
                if (pcmFile == null || !pcmFile.exists()) {
                    return@withContext McpToolResult(
                        content = listOf(McpContent.TextContent("Recording file not found")),
                        isError = true
                    )
                }

                // Convert PCM to WAV
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val wavFile = File(context.cacheDir, "${prefix}_$timestamp.wav")
                convertPcmToWav(pcmFile, wavFile)

                // Save to gallery
                val uri = saveAudioToGallery(wavFile)

                // Clean up temp files
                pcmFile.delete()

                val durationFormatted = formatDuration(recordingDurationSeconds)
                val fileSize = uri?.let {
                    val cursor = context.contentResolver.query(it, null, null, null, null)
                    cursor?.use { c ->
                        if (c.moveToFirst()) {
                            val size = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                            if (size >= 0) c.getLong(size) else 0L
                        } else 0L
                    }
                } ?: 0L

                currentRecordingFile = null
                currentRecordingPath = null

                McpToolResult(
                    content = listOf(McpContent.TextContent(
                        "Đã lưu ghi âm thành công!\n" +
                                "Thời lượng: $durationFormatted\n" +
                                "Kích thước: ${formatFileSize(fileSize)}"
                    )),
                    audioUri = uri?.toString()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error saving recording", e)
                McpToolResult(
                    content = listOf(McpContent.TextContent("Error saving recording: ${e.message}")),
                    isError = true
                )
            }
        }

        Log.i(TAG, "Stopped recording: ${recordingDurationSeconds}s")
        return result
    }

    /**
     * Get current recording status
     */
    private fun getRecordingStatus(): McpToolResult {
        return when {
            isLongRecording -> {
                val duration = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
                val durationFormatted = formatDuration(duration)
                McpToolResult(
                    content = listOf(McpContent.TextContent(
                        "Đang ghi âm dài\nThời gian: $durationFormatted"
                    ))
                )
            }
            isRecording -> {
                val duration = ((System.currentTimeMillis() - recordingStartTime) / 1000).toInt()
                val remaining = MAX_SHORT_RECORDING_SECONDS - duration
                McpToolResult(
                    content = listOf(McpContent.TextContent(
                        "Đang ghi âm (ngắn)\nThời gian: ${duration}s\nCòn lại: ${remaining}s"
                    ))
                )
            }
            else -> {
                McpToolResult(
                    content = listOf(McpContent.TextContent("Không có ghi âm nào đang chạy"))
                )
            }
        }
    }

    /**
     * Convert PCM to WAV format
     */
    private fun convertPcmToWav(pcmFile: File, wavFile: File) {
        val pcmData = pcmFile.readBytes()
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 1 * 16 / 8 // sampleRate * channels * bitsPerSample / 8

        FileOutputStream(wavFile).use { output ->
            // RIFF header
            output.write("RIFF".toByteArray())
            output.write(intToByteArray(totalDataLen))
            output.write("WAVE".toByteArray())

            // fmt chunk
            output.write("fmt ".toByteArray())
            output.write(intToByteArray(16)) // chunk size
            output.write(shortToByteArray(1)) // audio format (PCM)
            output.write(shortToByteArray(1)) // num channels
            output.write(intToByteArray(sampleRate)) // sample rate
            output.write(intToByteArray(byteRate)) // byte rate
            output.write(shortToByteArray(2)) // block align
            output.write(shortToByteArray(16)) // bits per sample

            // data chunk
            output.write("data".toByteArray())
            output.write(intToByteArray(pcmData.size))
            output.write(pcmData)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Save audio file to device gallery
     */
    private fun saveAudioToGallery(audioFile: File): android.net.Uri? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "VIETBOT_AUDIO_$timestamp.wav"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/VietBot/Audio")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    resolver.openOutputStream(it)?.use { stream ->
                        audioFile.inputStream().use { input ->
                            input.copyTo(stream)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }

                uri
            } else {
                val audioDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VietBot/Audio")
                if (!audioDir.exists()) {
                    audioDir.mkdirs()
                }
                val destFile = File(audioDir, filename)
                audioFile.copyTo(destFile, overwrite = true)

                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                }
                context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save audio to gallery", e)
            null
        }
    }

    /**
     * Format duration in seconds to mm:ss or hh:mm:ss
     */
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60

        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, secs)
            else -> String.format("%02d:%02d", minutes, secs)
        }
    }

    /**
     * Format file size to human readable format
     */
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }

    /**
     * Release resources
     */
    fun release() {
        stopAllRecordingImmediate()
        stopBackgroundThread()
    }
}
