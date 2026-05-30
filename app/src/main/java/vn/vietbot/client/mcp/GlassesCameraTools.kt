package vn.vietbot.client.mcp

import android.content.Context
import android.util.Base64
import android.util.Log
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera MCP Tools for HeyCyan Smart Glasses
 * Sends commands to glasses via BLE for camera operations.
 * Photos/videos are saved on the glasses, not on the phone.
 */
class GlassesCameraTools(
    private val context: Context,
    private val glassesManager: HeyCyanGlassesManager
) {
    companion object {
        private const val TAG = "GlassesCameraTools"
    }

    private val glassesScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
            name = "self.camera.take_photo",
            description = """📸 CHỤP ẢNH (KÍNH) - Chụp ảnh và lưu vào bộ nhớ kính

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Chụp ảnh", "Chụp hình" bằng kính
- Người dùng muốn chụp ảnh từ camera kính

GHI CHÚ:
- Ảnh được lưu vào bộ nhớ kính, không lưu vào điện thoại
- Dùng WiFi P2P để xem ảnh trên kính sau""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.record_video",
            description = """🎥 QUAY VIDEO (KÍNH) - Quay video với thời lượng cố định và lưu vào kính

BAT BUOC GOI FUNCTION KHI:
- Người dùng yêu cầu: "Quay video", "Quay phim" bằng kính

GHI CHÚ: Video được lưu vào bộ nhớ kính""",
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

GHI CHÚ: Quay cho đến khi gọi stop_video_recording. Video lưu trên kính.""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.stop_video_recording",
            description = """⏹️ DỪNG QUAY VIDEO (KÍNH) - Dừng quay video đang chạy

GHI CHÚ: Video được lưu vào bộ nhớ kính""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.get_video_recording_status",
            description = """📊 KIỂM TRA TRẠNG THÁI QUAY VIDEO (KÍNH)""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "self.camera.photo_ai",
            description = """👁️ PHÂN TÍCH ẢNH BẰNG AI (KÍNH) - Chụp ảnh và gửi lên AI phân tích

GHI CHÚ: Ảnh được mã hóa base64 và gửi lên server AI""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("question", JSONObject().apply {
                        put("type", "string")
                        put("description", "Câu hỏi AI cần trả lời")
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
            "self.camera.take_photo" -> takePhoto()
            "self.camera.record_video" -> recordVideo(args.optInt("duration", 10))
            "self.camera.photo_ai" -> photoAi(args.optString("question", "Mô tả những gì bạn nhìn thấy"))
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
            val thumbnailSize = 0x02
            val command = byteArrayOf(
                0x02, 0x01, CameraOp.PHOTO_AI.toByte(),
                thumbnailSize.toByte(), thumbnailSize.toByte(), 0x02
            )

            Log.i(TAG, "Sending photo AI command to glasses")

            glassesManager.glassesControl(command) { success, _, _ ->
                if (success) {
                    Log.i(TAG, "AI photo trigger sent")

                    // Fetch thumbnail
                    val thumbnailAccumulator = ByteArrayOutputStream()
                    glassesManager.getPictureThumbnails { dataSuccess, data ->
                        if (data != null) {
                            thumbnailAccumulator.write(data)
                        }
                        if (dataSuccess) {
                            val fullImage = thumbnailAccumulator.toByteArray()
                            thumbnailAccumulator.reset()

                            if (fullImage.isNotEmpty()) {
                                val imageData = findJpegData(fullImage)
                                if (imageData != null) {
                                    val base64Image = Base64.encodeToString(imageData, Base64.NO_WRAP)
                                    cont.resume(McpToolResult(
                                        content = listOf(
                                            McpContent.ImageContent("image/jpeg", base64Image),
                                            McpContent.TextContent(question)
                                        )
                                    ))
                                } else {
                                    cont.resume(McpToolResult(
                                        content = listOf(McpContent.TextContent("Không thể xử lý ảnh từ kính.")),
                                        isError = true
                                    ))
                                }
                            } else {
                                cont.resume(McpToolResult(
                                    content = listOf(McpContent.TextContent("Không nhận được ảnh từ kính.")),
                                    isError = true
                                ))
                            }
                        }
                    }
                } else {
                    cont.resume(McpToolResult(
                        content = listOf(McpContent.TextContent("Lệnh phân tích ảnh thất bại.")),
                        isError = true
                    ))
                }
            }
        }
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
