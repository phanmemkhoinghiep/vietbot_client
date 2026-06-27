package vn.vietbot.client.mcp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera MCP Tools for HeyCyan Smart Glasses
 * Sends commands to glasses via BLE for camera operations.
 * Photos/videos are saved on the glasses, not on the phone.
 */
class GlassesCameraTools(
    private val context: Context,
    private val glassesManager: SmartGlassesManager
) {
    companion object {
        private const val TAG = "GlassesCameraTools"
    }

    private val glassesScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // HTTP client for vision API
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Vision server configuration
    var visionServerUrl: String = "https://esp32.vietbot.vn/vision/explain"
    var deviceId: String = ""
    var clientId: String = ""

    // Operation codes for camera commands
    private object CameraOp {
        const val TAKE_PHOTO = 0x01
        const val RECORD_VIDEO = 0x02
        const val START_RECORDING = 0x03
        const val STOP_RECORDING = 0x04
        const val GET_STATUS = 0x05
        const val PHOTO_AI = 0x06
    }

    /**
     * Get all available camera tools for glasses
     */
    fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "self.camera.photo_ai",
            description = """📸📷📹 CHỤP ẢNH TỪ KÍNH VÀ PHÂN TÍCH AI

CHỨC NĂNG CHÍNH:
- Chụp ảnh từ camera kính
- Lưu ảnh vào bộ sưu tập (thư mục VietBot/DCIM)
- Gửi ảnh cho AI phân tích (nếu có câu hỏi)

BAT BUOC GOI KHI:
- Người dùng yêu cầu: "Chụp ảnh", "Chụp hình" bằng kính
- Người dùng yêu cầu: "Phân tích hình ảnh", "Xem có gì", "Mô tả những gì bạn thấy"
- Người dùng hỏi: "Đây là cái gì", "Camera nhìn thấy gì"
- BẤT KỲ yêu cầu nào liên quan đến: chụp ảnh, xem ảnh, phân tích camera

NẾU KHÔNG CÓ CÂU HỎI: Chỉ chụp ảnh và lưu vào VietBot
NẾU CÓ CÂU HỎI: Chụp ảnh, lưu vào VietBot + gửi cho AI phân tích""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", "Câu hỏi cho AI (nếu có). VD: 'mô tả những gì bạn thấy'. Nếu để trống thì chỉ chụp ảnh lưu vào VietBot.")
                    })
                })
            }
        ),
        McpTool(
            name = "self.camera.record_video",
            description = """🎥 QUAY VIDEO (KÍNH) - Quay video với thời lượng cố định và lưu vào kính

BAT BUOC GOI KHI:
- Người dùng yêu cầu: "Quay video", "Quay phim" bằng kính""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("duration", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Thời lượng video (giây)")
                        put("default", 10)
                    })
                })
            }
        ),
        McpTool(
            name = "self.camera.start_video_recording",
            description = """📹 BẮT ĐẦU QUAY VIDEO (KÍNH) - Bắt đầu quay video không giới hạn

BAT BUOC GOI KHI:
- Người dùng yêu cầu: "Bắt đầu quay video"
- PHẢI gọi stop_video_recording để kết thúc""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.stop_video_recording",
            description = """⏹️ DỪNG QUAY VIDEO (KÍNH) - Dừng quay video đang chạy

BAT BUOC GOI KHI:
- Người dùng yêu cầu: "Dừng quay", "Kết thúc quay"
- Video lưu vào bộ nhớ kính""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.get_video_recording_status",
            description = """📊 KIỂM TRA TRẠNG THÁI QUAY VIDEO (KÍNH)

BAT BUOC GOI KHI:
- Người dùng muốn biết có đang quay video không""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        )
    )

    /**
     * Execute a camera tool by name
     */
    suspend fun executeTool(toolName: String, args: JSONObject): McpToolResult {
        return when (toolName) {
            "self.camera.photo_ai" -> photoAi(args.optString("question", ""))
            "self.camera.record_video" -> recordVideo(args.optInt("duration", 10))
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
     * Check if glasses is connected
     */
    private fun isGlassesConnected(): Boolean {
        return glassesManager.connectionState.value == GlassesConnectionState.CONNECTED
    }

    /**
     * Take a photo on the glasses
     */
    private suspend fun takePhoto(): McpToolResult {
        if (!isGlassesConnected()) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("Kính chưa được kết nối. Vui lòng kết nối kính trong Cài đặt.")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            val command = byteArrayOf(0x02, 0x01, CameraOp.TAKE_PHOTO.toByte(), 0x02, 0x02, 0x02)
            glassesManager.glassesControl(command) { success, _, _ ->
                if (success) {
                    Log.i(TAG, "Take photo command sent to glasses")
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Đã chụp ảnh trên kính. Ảnh được lưu vào bộ nhớ kính."))
                    ))
                } else {
                    Log.e(TAG, "Take photo command failed")
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Lệnh chụp ảnh thất bại.")),
                        isError = true
                    ))
                }
            }
        }
    }

    /**
     * Record video on the glasses
     */
    private suspend fun recordVideo(durationSeconds: Int): McpToolResult {
        if (!isGlassesConnected()) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("Kính chưa được kết nối.")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            val duration = durationSeconds.coerceIn(1, 180).toByte()
            val command = byteArrayOf(
                0x02, 0x01, CameraOp.RECORD_VIDEO.toByte(),
                duration, duration, 0x02
            )
            glassesManager.glassesControl(command) { success, _, _ ->
                if (success) {
                    Log.i(TAG, "Record video command sent")
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Đã bắt đầu quay video ${durationSeconds}s trên kính."))
                    ))
                } else {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Lệnh quay video thất bại.")),
                        isError = true
                    ))
                }
            }
        }
    }

    /**
     * Start video recording on the glasses
     */
    private suspend fun startVideoRecording(): McpToolResult {
        if (!isGlassesConnected()) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("Kính chưa được kết nối.")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            val command = byteArrayOf(0x02, 0x01, CameraOp.START_RECORDING.toByte(), 0x02, 0x02, 0x02)
            glassesManager.glassesControl(command) { success, _, _ ->
                if (success) {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Đã bắt đầu quay video trên kính."))
                    ))
                } else {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Lệnh thất bại.")),
                        isError = true
                    ))
                }
            }
        }
    }

    /**
     * Stop video recording on the glasses
     */
    private suspend fun stopVideoRecording(): McpToolResult {
        if (!isGlassesConnected()) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("Kính chưa được kết nối.")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            val command = byteArrayOf(0x02, 0x01, CameraOp.STOP_RECORDING.toByte(), 0x02, 0x02, 0x02)
            glassesManager.glassesControl(command) { success, _, _ ->
                if (success) {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Đã dừng quay video trên kính."))
                    ))
                } else {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Lệnh thất bại.")),
                        isError = true
                    ))
                }
            }
        }
    }

    /**
     * Get video recording status
     */
    private suspend fun getVideoRecordingStatus(): McpToolResult {
        if (!isGlassesConnected()) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("Kính chưa được kết nối.")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            val command = byteArrayOf(0x02, 0x01, CameraOp.GET_STATUS.toByte(), 0x02, 0x02, 0x02)
            glassesManager.glassesControl(command) { success, _, _ ->
                if (success) {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Trạng thái kính: Sẵn sàng"))
                    ))
                } else {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Không thể lấy trạng thái.")),
                        isError = true
                    ))
                }
            }
        }
    }

    /**
     * Take photo with AI analysis
     */
    private suspend fun photoAi(question: String): McpToolResult {
        if (!isGlassesConnected()) {
            return McpToolResult(
                content = listOf(McpContent.TextContent("Kính chưa được kết nối.")),
                isError = true
            )
        }

        return suspendCancellableCoroutine { cont ->
            Log.i(TAG, "photoAi: sending AI photo command to glasses (question='$question')")

            glassesManager.photoAiCapture { imageData ->
                if (imageData != null && imageData.isNotEmpty()) {
                    Log.i(TAG, "photoAi: received ${imageData.size} bytes from glasses")

                    // Save to VietBot gallery and get content URI
                    val galleryUri = saveToGallery(imageData)

                    val jpegData = findJpegData(imageData) ?: imageData
                    val base64Image = Base64.encodeToString(jpegData, Base64.NO_WRAP)

                    if (question.isNotEmpty()) {
                        // FIX: Send image via HTTP instead of WebSocket to avoid 1007 error
                        Log.i(TAG, "Sending image to vision server via HTTP...")
                        val aiResponse = uploadToVisionServer(jpegData, question)
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Đã chụp ảnh từ kính và phân tích: $aiResponse")),
                            imageUri = galleryUri
                        ))
                    } else {
                        // Photo-only mode: just confirm saved
                        cont.resume(McpToolResult(
                            content = listOf(McpContent.TextContent("Đã chụp ảnh từ kính và lưu vào VietBot (${imageData.size} bytes)")),
                            imageUri = galleryUri
                        ))
                    }
                } else {
                    Log.e(TAG, "photoAi: no image data received from glasses")
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Không nhận được ảnh từ kính.")),
                        isError = true
                    ))
                }
            }
        }
    }

    private fun saveToGallery(imageData: ByteArray): String? {
        return try {
            val filename = "Glasses_${System.currentTimeMillis()}.jpg"
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_DCIM + "/VietBot")
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(imageData)
                    stream.flush()
                }
                contentValues.clear()
                contentValues.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
                Log.i(TAG, "Saved glasses photo to gallery: $filename")
                uri.toString()
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save glasses photo to gallery", e)
            null
        }
    }

    /**
     * Upload image to vision server via HTTP multipart/form-data
     */
    private fun uploadToVisionServer(imageData: ByteArray, question: String): String {
        return try {
            Log.i(TAG, "Uploading ${imageData.size} bytes to vision server (glasses): $visionServerUrl")

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

    private fun buildMultipartBody(imageData: ByteArray, question: String, boundary: String): ByteArray {
        val stream = ByteArrayOutputStream()
        val utf8 = java.nio.charset.StandardCharsets.UTF_8

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

    /**
     * Find JPEG data in raw bytes
     */
    private fun findJpegData(rawData: ByteArray): ByteArray? {
        for (i in rawData.indices) {
            if (i + 1 < rawData.size &&
                rawData[i].toInt() == 0xFF &&
                rawData[i + 1].toInt() == 0xD8) {
                return rawData.copyOfRange(i, rawData.size)
            }
        }
        return if (rawData.size > 2) rawData else null
    }
}
