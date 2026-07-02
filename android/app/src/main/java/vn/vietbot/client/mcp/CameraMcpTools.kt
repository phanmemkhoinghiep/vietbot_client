package vn.vietbot.client.mcp

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

/**
 * Camera MCP Tools for Android
 * Provides tools for:
 * - Taking photos and uploading to server via HTTP (no gallery save)
 */
class CameraMcpTools(private val context: Context) {

    companion object {
        private const val TAG = "CameraMcpTools"
    }

    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
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
     * Get all available camera tools — only photo_ai (no gallery save, direct upload)
     */
    fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "self.camera.photo_ai",
            description = """📸 CHỤP ẢNH VÀ PHÂN TÍCH AI

CHỤP ẢNH + GỬI TRỰC TIẾP CHO AI (không lưu vào bộ sưu tập thiết bị)

BAT BUOC GOI KHI:
- Người dùng yêu cầu: "Chụp ảnh", "Chụp hình", "Lấy ảnh"
- Người dùng yêu cầu: "Phân tích hình ảnh", "Xem có gì", "Mô tả những gì bạn thấy"
- Người dùng hỏi: "Đây là cái gì", "Có gì trong ảnh", "Camera nhìn thấy gì"
- Người dùng nói: "Xem camera", "Nhìn vào camera"
- BẤT KỲ yêu cầu nào liên quan đến: chụp ảnh, xem ảnh, phân tích camera

NẾU KHÔNG CÓ CÂU HỎI: Chụp ảnh gửi lên AI phân tích
NẾU CÓ CÂU HỎI: Chụp ảnh + gửi cho AI phân tích""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", "Câu hỏi cho AI (nếu có). VD: 'mô tả những gì bạn thấy'.")
                    })
                })
            }
        )
    )

    /**
     * Execute a camera tool by name
     */
    suspend fun executeTool(toolName: String, args: JSONObject): McpToolResult {
        return when (toolName) {
            "self.camera.photo_ai" -> takePhotoAndUpload(args.optString("question", ""))
            else -> McpToolResult(
                content = listOf(McpContent.TextContent("Unknown tool: $toolName")),
                isError = true
            )
        }
    }

    /**
     * Take a photo and upload to vision server via HTTP
     * No gallery save — image bytes sent directly to server
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

            if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
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
                                                            val rotatedImageData = rotateImageIfNeeded(imageData)
                                                            val question = description.ifEmpty { "Mô tả những gì bạn nhìn thấy trong ảnh này" }
                                                            Log.i(TAG, "Uploading ${rotatedImageData.size} bytes to vision server...")
                                                            val aiResponse = uploadToVisionServer(rotatedImageData, question)
                                                            cont.resume(McpToolResult(
                                                                content = listOf(
                                                                    McpContent.TextContent("Đã chụp ảnh và phân tích:\n\n$aiResponse")
                                                                )
                                                            ))
                                                        } catch (e: Exception) {
                                                            Log.e(TAG, "Image processing error", e)
                                                            cont.resume(McpToolResult(
                                                                content = listOf(McpContent.TextContent("Lỗi xử lý ảnh: ${e.message}")),
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
                content = listOf(McpContent.TextContent("Lỗi chụp ảnh: ${e.message}")),
                isError = true
            ))
        }
    }

    /**
     * Upload image to vision server via HTTP multipart/form-data (synchronous)
     * Called from non-suspend context (e.g., McpServer follow-up questions)
     */
    fun uploadToVisionServerSync(imageData: ByteArray, question: String): String {
        return try {
            Log.i(TAG, "Uploading ${imageData.size} bytes to vision server (sync): $visionServerUrl")
            val boundary = "----ESP32_CAMERA_BOUNDARY_${System.currentTimeMillis()}"
            val contentType = "multipart/form-data; boundary=$boundary"
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

            val json = JSONObject(responseBody)
            if (json.optBoolean("success", false)) {
                json.optString("response", "Analysis complete")
            } else {
                json.optString("message", "Analysis failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload to vision server failed", e)
            "Failed to upload image: ${e.message}"
        }
    }

    /**
     * Upload image to vision server via HTTP (suspend version)
     */
    private suspend fun uploadToVisionServer(imageData: ByteArray, question: String): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "Uploading ${imageData.size} bytes to vision server: $visionServerUrl")
                val boundary = "----ESP32_CAMERA_BOUNDARY_${System.currentTimeMillis()}"
                val contentType = "multipart/form-data; boundary=$boundary"
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

        stream.write("--$boundary\r\n".toByteArray(utf8))
        stream.write("Content-Disposition: form-data; name=\"question\"\r\n\r\n".toByteArray(utf8))
        stream.write(question.toByteArray(utf8))
        stream.write("\r\n".toByteArray(utf8))

        stream.write("--$boundary\r\n".toByteArray(utf8))
        stream.write("Content-Disposition: form-data; name=\"file\"; filename=\"camera.jpg\"\r\n".toByteArray(utf8))
        stream.write("Content-Type: image/jpeg\r\n\r\n".toByteArray(utf8))
        stream.write(imageData)
        stream.write("\r\n".toByteArray(utf8))

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
     */
    private fun rotateImageIfNeeded(imageData: ByteArray): ByteArray {
        try {
            val displayRotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            val rotationDegrees = when (displayRotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> 0f
            }

            if (rotationDegrees == 0f) return imageData

            val bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.size)
            val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)

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
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }

    fun release() {
        closeCamera()
        stopBackgroundThread()
    }
}