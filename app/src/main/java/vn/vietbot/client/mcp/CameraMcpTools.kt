package vn.vietbot.client.mcp

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.buffer
import okio.sink
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera MCP Tools for Android
 * Provides tools for:
 * - Taking photos and saving to gallery
 * - Recording videos and saving to gallery
 * - Taking photos and uploading to server
 */
class CameraMcpTools(private val context: Context) {

    companion object {
        private const val TAG = "CameraMcpTools"
        private const val TIMEOUT_MS = 10000L
        private const val MAX_IMAGE_SIZE = 5 * 1024 * 1024 // 5MB
    }

    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentVideoFile: File? = null
    private var isVideoRecording = false
    private var videoRecordingStartTime: Long = 0
    private var videoSession: CameraCaptureSession? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Vision server configuration - set this before using photo_ai
    var visionServerUrl: String = "https://esp32.vietbot.vn/vision/explain"
    var deviceId: String = ""
    var clientId: String = ""

    // HTTP client for vision API
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Coroutine scope for background tasks
    private val cameraScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
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
     * Get all available camera tools
     */
    fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "self.camera.take_photo",
            description = """📸 CHỤP ẢNH - Chụp ảnh và lưu vào thư viện thiết bị

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Chụp ảnh", "Chụp hình", "Lấy ảnh", "Chụp một tấm hình"
- Người dùng muốn lưu ảnh vào điện thoại/thư viện
- Người dùng chỉ muốn chụp ảnh, KHÔNG yêu cầu AI phân tích/mô tả

GHI CHÚ: Chụp ảnh đơn thuần, không gửi lên AI phân tích.""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", "Không sử dụng cho tool này")
                    })
                })
            }
        ),
        McpTool(
            name = "self.camera.record_video",
            description = """🎥 QUAY VIDEO - Quay video và lưu vào thư viện thiết bị

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Quay video", "Quay phim", "Ghi hình"
- Người dùng muốn quay video lưu vào điện thoại
- Người dùng chỉ muốn quay video, KHÔNG yêu cầu AI phân tích/mô tả

GHI CHÚ: Quay video đơn thuần, không gửi lên AI phân tích.""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("duration", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Thời lượng video (giây, mặc định: 10, tối đa: 180)")
                        put("minimum", 1)
                        put("maximum", 180)
                        put("default", 10)
                    })
                })
            }
        ),
        McpTool(
            name = "self.camera.start_video_recording",
            description = """📹 BẮT ĐẦU QUAY VIDEO - Bắt đầu quay video không giới hạn thời gian

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Bắt đầu quay video", "Quay video dài", "Quay không giới hạn"
- Người dùng cần quay video dài (cuộc họp, sự kiện...)
- Người dùng muốn quay cho đến khi chủ động dừng

GHI CHÚ:
- Quay video không giới hạn thời gian
- PHẢI gọi stop_video_recording để kết thúc
- Tự động dừng nếu app bị tắt hoặc chuyển ứng dụng""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.stop_video_recording",
            description = """⏹️ DỪNG QUAY VIDEO - Dừng quay video đang chạy và lưu file

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Dừng quay", "Kết thúc quay", "Ngừng quay video"
- Người dùng đã hoàn thành việc quay video
- Sau khi đã gọi start_video_recording

GHI CHÚ: Video được lưu vào thư mục VietBot/Movies trong điện thoại""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.get_video_recording_status",
            description = """📊 KIỂM TRA TRẠNG THÁI QUAY VIDEO - Kiểm tra xem có đang quay video không

BAT BUOC GOI FUNCTION KHI:
- Người dùng muốn biết có đang quay video không
- Kiểm tra trước khi bắt đầu quay mới""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.photo_ai",
            description = """👁️ PHÂN TÍCH ẢNH BẰNG AI - Chụp ảnh và gửi lên AI để phân tích/mô tả

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Phân tích hình ảnh", "Nhìn xem có gì", "Mô tả những gì bạn thấy"
- Người dùng hỏi: "Đây là cái gì", "Có gì trong ảnh", "Hãy nhìn vào camera"
- Người dùng muốn AI nhìn và mô tả: "Xem có ai không", "Đọc chữ trên ảnh", "Nhận diện vật thể"
- Người dùng nói: "Camera", "Nhìn camera", "Xem camera"
- BẤT KỲ yêu cầu nào liên quan đến việc AI cần NHÌN/PHÂN TÍCH nội dung camera

GHI CHÚ: KHÔNG dùng khi người dùng chỉ muốn chụp ảnh lưu điện thoại (dùng self.camera.take_photo thay thế).""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", "Câu hỏi AI cần trả lời sau khi phân tích ảnh (ví dụ: 'mô tả những gì bạn thấy', 'có ai trong ảnh không')")
                    })
                })
                put("required", org.json.JSONArray())
            }
        )
    )

    /**
     * Execute a camera tool by name
     */
    suspend fun executeTool(toolName: String, args: JSONObject): McpToolResult {
        return when (toolName) {
            "self.camera.take_photo" -> takePhoto(args.optString("question", ""))
            "self.camera.record_video" -> recordVideo(args.optInt("duration", 10))
            "self.camera.photo_ai" -> takePhotoAndUpload(args.optString("question", ""))
            "self.camera.start_video_recording" -> startVideoRecording()
            "self.camera.stop_video_recording" -> stopVideoRecording()
            "self.camera.get_video_recording_status" -> getVideoRecordingStatus()
            else -> McpToolResult(
                content = listOf(McpContent.TextContent("Unknown tool: $toolName")),
                isError = true
            )
        }
    }

    /**
     * Take a photo and save to gallery
     */
    private suspend fun takePhoto(question: String): McpToolResult = suspendCancellableCoroutine { cont ->
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findBackCamera(cameraManager) ?: run {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("No camera available")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("Camera permission not granted")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            val imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)
            var capturedImage: ByteArray? = null

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    capturedImage = ByteArray(buffer.remaining())
                    buffer.get(capturedImage!!)
                    image.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                        }

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: android.hardware.camera2.TotalCaptureResult
                                            ) {
                                                // Photo captured, now save to gallery
                                                capturedImage?.let { imageData ->
                                                    val rotatedImageData = rotateImageIfNeeded(imageData)
                                                    val uri = saveImageToGallery(rotatedImageData)
                                                    val responseText = if (uri != null) {
                                                        "Photo saved to gallery successfully!"
                                                    } else {
                                                        "Photo captured but failed to save to gallery"
                                                    }

                                                    val textWithQuestion = if (question.isNotEmpty()) {
                                                        "$responseText\n\nNote: $question"
                                                    } else {
                                                        responseText
                                                    }

                                                    cont.resume(McpToolResult(
                                                        content = listOf(McpContent.TextContent(textWithQuestion)),
                                                        imageUri = uri?.toString()
                                                    ))
                                                }
                                                closeCamera()
                                            }

                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: android.hardware.camera2.CaptureFailure
                                            ) {
                                                cont.resume(McpToolResult(
                                                    content = listOf(McpContent.TextContent("Camera capture failed")),
                                                    isError = true
                                                ))
                                                closeCamera()
                                            }
                                        }, backgroundHandler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Capture error", e)
                                        cont.resume(McpToolResult(
                                            content = listOf(McpContent.TextContent("Capture error: ${e.message}")),
                                            isError = true
                                        ))
                                        closeCamera()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    cont.resume(McpToolResult(
                                        content = listOf(McpContent.TextContent("Camera configuration failed")),
                                        isError = true
                                    ))
                                    closeCamera()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Open camera error", e)
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Failed to open camera: ${e.message}")),
                            isError = true
                        ))
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    if (!cont.isCompleted) {
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Camera error: $error")),
                            isError = true
                        ))
                    }
                }
            }, backgroundHandler)

        } catch (e: SecurityException) {
            cont.resume(McpToolResult(
                content = listOf(McpContent.TextContent("Camera permission denied: ${e.message}")),
                isError = true
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Take photo error", e)
            cont.resume(McpToolResult(
                content = listOf(McpContent.TextContent("Failed to take photo: ${e.message}")),
                isError = true
            ))
        }
    }

    /**
     * Record a video and save to gallery
     */
    private suspend fun recordVideo(durationSeconds: Int): McpToolResult = suspendCancellableCoroutine { cont ->
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findBackCamera(cameraManager) ?: run {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("No camera available")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("Camera permission not granted")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            // Create temp file for video
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val videoFile = File(context.cacheDir, "VID_$timestamp.mp4")

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile.absolutePath)
                setVideoEncodingBitRate(10000000)
                setVideoFrameRate(30)
                setVideoSize(1920, 1080)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = mediaRecorder?.surface ?: throw Exception("No surface")

                        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                        }

                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.setRepeatingRequest(
                                            previewRequest.build(),
                                            null,
                                            backgroundHandler
                                        )

                                        mediaRecorder?.start()

                                        // Schedule stop after duration
                                        backgroundHandler?.postDelayed({
                                            try {
                                                mediaRecorder?.stop()
                                                mediaRecorder?.reset()
                                                mediaRecorder?.release()
                                                mediaRecorder = null

                                                // Save to gallery
                                                val uri = saveVideoToGallery(videoFile)

                                                cont.resume(McpToolResult(
                                                    content = listOf(McpContent.TextContent(
                                                        if (uri != null) "Video saved to gallery successfully! Duration: ${durationSeconds}s"
                                                        else "Video recorded but failed to save to gallery"
                                                    )),
                                                    imageUri = uri?.toString()
                                                ))
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Stop recording error", e)
                                                cont.resume(McpToolResult(
                                                    content = listOf(McpContent.TextContent("Recording error: ${e.message}")),
                                                    isError = true
                                                ))
                                            } finally {
                                                closeCamera()
                                            }
                                        }, (durationSeconds * 1000).toLong())

                                    } catch (e: Exception) {
                                        Log.e(TAG, "Start recording error", e)
                                        cont.resume(McpToolResult(
                                            content = listOf(McpContent.TextContent("Failed to start recording: ${e.message}")),
                                            isError = true
                                        ))
                                        closeCamera()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    cont.resume(McpToolResult(
                                        content = listOf(McpContent.TextContent("Camera configuration failed")),
                                        isError = true
                                    ))
                                    closeCamera()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Recording setup error", e)
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Setup error: ${e.message}")),
                            isError = true
                        ))
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    if (!cont.isCompleted) {
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Camera error: $error")),
                            isError = true
                        ))
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Record video error", e)
            cont.resume(McpToolResult(
                content = listOf(McpContent.TextContent("Failed to record video: ${e.message}")),
                isError = true
            ))
        }
    }

    /**
     * Start video recording (unlimited duration)
     */
    private suspend fun startVideoRecording(): McpToolResult = suspendCancellableCoroutine { cont ->
        // Check if already recording
        if (isVideoRecording) {
            cont.resume(McpToolResult(
                content = listOf(McpContent.TextContent("Already recording video. Please stop first.")),
                isError = true
            ))
            return@suspendCancellableCoroutine
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findBackCamera(cameraManager) ?: run {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("No camera available")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("Camera permission not granted")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            // Create temp file for video
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val videoFile = File(context.cacheDir, "VID_LONG_$timestamp.mp4")
            currentVideoFile = videoFile

            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(videoFile.absolutePath)
                setVideoEncodingBitRate(10000000)
                setVideoFrameRate(30)
                setVideoSize(1920, 1080)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                prepare()
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val surface = mediaRecorder?.surface ?: throw Exception("No surface")

                        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                        }

                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        videoSession = session
                                        session.setRepeatingRequest(
                                            previewRequest.build(),
                                            null,
                                            backgroundHandler
                                        )

                                        mediaRecorder?.start()
                                        isVideoRecording = true
                                        videoRecordingStartTime = System.currentTimeMillis()

                                        Log.i(TAG, "Started long video recording")
                                        cont.resume(McpToolResult(
                                            content = listOf(McpContent.TextContent("Bắt đầu quay video. Quay cho đến khi bạn yêu cầu dừng."))
                                        ))

                                    } catch (e: Exception) {
                                        Log.e(TAG, "Start video recording error", e)
                                        cont.resume(McpToolResult(
                                            content = listOf(McpContent.TextContent("Failed to start video recording: ${e.message}")),
                                            isError = true
                                        ))
                                        closeCamera()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    cont.resume(McpToolResult(
                                        content = listOf(McpContent.TextContent("Camera configuration failed")),
                                        isError = true
                                    ))
                                    closeCamera()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Recording setup error", e)
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Setup error: ${e.message}")),
                            isError = true
                        ))
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    if (!cont.isCompleted) {
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Camera error: $error")),
                            isError = true
                        ))
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Start video recording error", e)
            cont.resume(McpToolResult(
                content = listOf(McpContent.TextContent("Failed to start video recording: ${e.message}")),
                isError = true
            ))
        }
    }

    /**
     * Stop video recording and save to gallery
     */
    private suspend fun stopVideoRecording(): McpToolResult {
        if (!isVideoRecording) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("No video recording in progress")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            try {
                val duration = ((System.currentTimeMillis() - videoRecordingStartTime) / 1000).toInt()
                val videoFile = currentVideoFile

                // Stop recording
                mediaRecorder?.stop()
                mediaRecorder?.reset()
                mediaRecorder?.release()
                mediaRecorder = null
                isVideoRecording = false

                // Close session
                try {
                    videoSession?.close()
                    videoSession = null
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing session", e)
                }
                closeCamera()

                // Save to gallery
                if (videoFile != null && videoFile.exists()) {
                    cameraScope.launch(Dispatchers.IO) {
                        try {
                            val uri = saveVideoToGallery(videoFile)
                            videoFile.delete()

                            val durationFormatted = formatDuration(duration)
                            cont.resume(McpToolResult(
                                content = listOf(McpContent.TextContent(
                                    "Đã lưu video thành công!\nThời lượng: $durationFormatted"
                                )),
                                videoUri = uri?.toString()
                            ))
                        } catch (e: Exception) {
                            Log.e(TAG, "Error saving video", e)
                            cont.resume(McpToolResult(
                                content = listOf(McpContent.TextContent("Error saving video: ${e.message}")),
                                isError = true
                            ))
                        }
                    }
                } else {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Video file not found")),
                        isError = true
                    ))
                }

            } catch (e: Exception) {
                Log.e(TAG, "Stop video recording error", e)
                isVideoRecording = false
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("Failed to stop video recording: ${e.message}")),
                    isError = true
                ))
            }
        }
    }

    /**
     * Get video recording status
     */
    private fun getVideoRecordingStatus(): McpToolResult {
        return if (isVideoRecording) {
            val duration = ((System.currentTimeMillis() - videoRecordingStartTime) / 1000).toInt()
            val durationFormatted = formatDuration(duration)
            McpToolResult(
                content = listOf(McpContent.TextContent("Đang quay video\nThời gian: $durationFormatted"))
            )
        } else {
            McpToolResult(
                content = listOf(McpContent.TextContent("Không có video nào đang quay"))
            )
        }
    }

    /**
     * Check if video recording is in progress
     */
    fun isVideoRecordingActive(): Boolean = isVideoRecording

    /**
     * Stop all video recording immediately (for lifecycle events - synchronous)
     */
    fun stopAllVideoRecording(): McpToolResult {
        if (!isVideoRecording) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("No video recording to stop"))
            )
        }

        // Stop immediately without waiting for save
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media recorder", e)
        }

        try {
            videoSession?.close()
            videoSession = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing session", e)
        }

        isVideoRecording = false
        closeCamera()

        // Clean up temp file
        currentVideoFile?.delete()
        currentVideoFile = null

        return McpToolResult(
            content = listOf(McpContent.TextContent("Video recording stopped"))
        )
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
     * Take a photo and return image for AI vision analysis
     * Image is encoded to base64 and sent via MCP response through WebSocket
     */
    private suspend fun takePhotoAndUpload(description: String): McpToolResult = suspendCancellableCoroutine { cont ->
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = findBackCamera(cameraManager) ?: run {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("No camera available")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                cont.resume(McpToolResult(
                    content = listOf(McpContent.TextContent("Camera permission not granted")),
                    isError = true
                ))
                return@suspendCancellableCoroutine
            }

            val imageReader = ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 1)
            var capturedImage: ByteArray? = null

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val buffer = image.planes[0].buffer
                    capturedImage = ByteArray(buffer.remaining())
                    buffer.get(capturedImage!!)
                    image.close()
                }
            }, backgroundHandler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    try {
                        val captureBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader.surface)
                        }

                        camera.createCaptureSession(
                            listOf(imageReader.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(captureBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                                            override fun onCaptureCompleted(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                result: android.hardware.camera2.TotalCaptureResult
                                            ) {
                                                capturedImage?.let { imageData ->
                                                    cameraScope.launch(Dispatchers.IO) {
                                                        try {
                                                            // Rotate image to match device orientation
                                                            val rotatedImageData = rotateImageIfNeeded(imageData)

                                                            // Save to gallery
                                                            val galleryUri = saveImageToGallery(rotatedImageData)

                                                            // Get question from description
                                                            val question = if (description.isNotEmpty()) {
                                                                description
                                                            } else {
                                                                "Mô tả những gì bạn nhìn thấy trong ảnh này"
                                                            }

                                                            // Encode image to base64 for WebSocket transfer
                                                            val base64Image = android.util.Base64.encodeToString(rotatedImageData, android.util.Base64.NO_WRAP)

                                                            // Return ImageContent + TextContent with question
                                                            // Server will detect image and call Gemini Vision internally
                                                            cont.resume(McpToolResult(
                                                                content = listOf(
                                                                    McpContent.ImageContent("image/jpeg", base64Image),
                                                                    McpContent.TextContent(question)
                                                                ),
                                                                imageUri = galleryUri?.toString()
                                                            ))
                                                        } catch (e: Exception) {
                                                            Log.e(TAG, "Image processing error", e)
                                                            cont.resume(McpToolResult(
                                                                content = listOf(McpContent.TextContent("Failed to process image: ${e.message}")),
                                                                isError = true
                                                            ))
                                                        }
                                                    }
                                                }
                                                closeCamera()
                                            }

                                            override fun onCaptureFailed(
                                                session: CameraCaptureSession,
                                                request: CaptureRequest,
                                                failure: android.hardware.camera2.CaptureFailure
                                            ) {
                                                cont.resume(McpToolResult(
                                                    content = listOf(McpContent.TextContent("Camera capture failed")),
                                                    isError = true
                                                ))
                                                closeCamera()
                                            }
                                        }, backgroundHandler)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Capture error", e)
                                        cont.resume(McpToolResult(
                                            content = listOf(McpContent.TextContent("Capture error: ${e.message}")),
                                            isError = true
                                        ))
                                        closeCamera()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    cont.resume(McpToolResult(
                                        content = listOf(McpContent.TextContent("Camera configuration failed")),
                                        isError = true
                                    ))
                                    closeCamera()
                                }
                            },
                            backgroundHandler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Open camera error", e)
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Failed to open camera: ${e.message}")),
                            isError = true
                        ))
                        closeCamera()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    closeCamera()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    closeCamera()
                    if (!cont.isCompleted) {
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Camera error: $error")),
                            isError = true
                        ))
                    }
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Take photo and upload error", e)
            cont.resume(McpToolResult(
                content = listOf(McpContent.TextContent("Failed to capture photo: ${e.message}")),
                isError = true
            ))
        }
    }

    /**
     * Upload image to vision server via HTTP multipart/form-data
     * DEPRECATED: Use base64 via WebSocket instead for better performance
     */
    private suspend fun uploadToVisionServer(imageData: ByteArray, question: String): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Uploading ${imageData.size} bytes to vision server: $visionServerUrl")

                // Build multipart request body using a ByteArray
                val boundary = "----ESP32_CAMERA_BOUNDARY_${System.currentTimeMillis()}"
                val contentType = "multipart/form-data; boundary=$boundary"

                // Build the multipart body as a byte array
                val bodyBytes = buildMultipartBody(imageData, question, boundary)

                val request = Request.Builder()
                    .url(visionServerUrl)
                    .post(RequestBody.create(contentType.toMediaTypeOrNull(), bodyBytes))
                    .header("Device-Id", deviceId)
                    .header("Client-Id", clientId)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: "{}"

                Log.i(TAG, "Vision response: $responseBody")

                // Parse JSON response
                val json = JSONObject(responseBody)
                return@withContext if (json.optBoolean("success", false)) {
                    json.optString("response", "Analysis complete")
                } else {
                    json.optString("message", "Analysis failed")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Upload to vision server failed", e)
                return@withContext "Failed to upload image: ${e.message}"
            }
        }
    }

    private fun buildMultipartBody(imageData: ByteArray, question: String, boundary: String): ByteArray {
        val stream = ByteArrayOutputStream()
        val utf8 = StandardCharsets.UTF_8

        // Write question field
        stream.write("--$boundary\r\n".toByteArray(utf8))
        stream.write("Content-Disposition: form-data; name=\"question\"\r\n\r\n".toByteArray(utf8))
        stream.write(question.toByteArray(utf8))
        stream.write("\r\n".toByteArray(utf8))

        // Write image field
        stream.write("--$boundary\r\n".toByteArray(utf8))
        stream.write("Content-Disposition: form-data; name=\"file\"; filename=\"camera.jpg\"\r\n".toByteArray(utf8))
        stream.write("Content-Type: image/jpeg\r\n\r\n".toByteArray(utf8))
        stream.write(imageData)
        stream.write("\r\n".toByteArray(utf8))

        // Write closing boundary
        stream.write("--$boundary--\r\n".toByteArray(utf8))

        return stream.toByteArray()
    }

    private fun findBackCamera(cameraManager: CameraManager): String? {
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId
            }
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    /**
     * Rotate image bytes based on device rotation
     * Ensures image displays correctly regardless of device orientation
     */
    private fun rotateImageIfNeeded(imageData: ByteArray): ByteArray {
        try {
            // Get display rotation
            val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val rotationDegrees = when (displayRotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> 0f
            }

            if (rotationDegrees == 0f) return imageData

            // Decode bitmap
            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

            // Encode back to bytes
            val stream = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            bitmap.recycle()
            rotated.recycle()

            return stream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate image", e)
            return imageData
        }
    }

    private fun closeCamera() {
        try {
            cameraDevice?.close()
            cameraDevice = null
            imageReader?.close()
            imageReader = null
            try {
                mediaRecorder?.stop()
                mediaRecorder?.release()
            } catch (e: Exception) {
                // Ignore errors when stopping
            }
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    private fun saveImageToGallery(imageData: ByteArray): Uri? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "VIETBOT_IMG_$timestamp.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VietBot")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    // Bucket metadata for album grouping
                    put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "VietBot")
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    resolver.openOutputStream(it)?.use { stream ->
                        stream.write(imageData)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }

                uri
            } else {
                val imagesDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "VietBot")
                if (!imagesDir.exists()) {
                    imagesDir.mkdirs()
                }
                val imageFile = File(imagesDir, filename)
                imageFile.writeBytes(imageData)

                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, imageFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.BUCKET_DISPLAY_NAME, "VietBot")
                }
                context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image to gallery", e)
            null
        }
    }

    private fun saveVideoToGallery(videoFile: File): Uri? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "VIETBOT_VID_$timestamp.mp4"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VietBot")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    resolver.openOutputStream(it)?.use { stream ->
                        videoFile.inputStream().use { input ->
                            input.copyTo(stream)
                        }
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(it, contentValues, null, null)
                }

                // Delete temp file
                videoFile.delete()

                uri
            } else {
                val videosDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VietBot")
                if (!videosDir.exists()) {
                    videosDir.mkdirs()
                }
                val destFile = File(videosDir, filename)
                videoFile.copyTo(destFile, overwrite = true)
                videoFile.delete()

                val contentValues = ContentValues().apply {
                    put(MediaStore.Video.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                }
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video to gallery", e)
            null
        }
    }

    fun release() {
        closeCamera()
        stopBackgroundThread()
    }
}