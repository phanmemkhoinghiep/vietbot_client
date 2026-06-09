package vn.vietbot.client.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import vn.vietbot.client.protocol.Protocol
import vn.vietbot.client.mcp.McpTool
import vn.vietbot.client.mcp.McpToolResult
import vn.vietbot.client.mcp.McpContent

/**
 * TranslateMcpTools - MCP tools cho dịch tức thời
 *
 * Khi kính HeyCyan đã pair với điện thoại,
 * AudioStreamManager thu âm liên tục từ mic kính và gửi lên server để dịch.
 */
class TranslateMcpTools(
    private val audioStreamManager: AudioStreamManager,
    private val protocol: vn.vietbot.client.protocol.Protocol?
) {

    companion object {
        private const val TAG = "TranslateMcpTools"
    }

    /**
     * Trả về danh sách MCP tools
     */
    fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "self.translate.start",
            description = """🎙️ DỊCH TỨC THỜI - Bắt đầu thu âm từ mic kính và dịch liên tục

📌 Khi nào dùng:
- Người nói tiếng Anh, muốn nghe bản dịch tiếng Việt
- Cần dịch real-time không cần chờ kết thúc câu

⚡ Bật: Gọi tool này
⏹️ Tắt: Gọi self.translate.stop""",
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("source_lang", JSONObject().apply {
                        put("type", "string")
                        put("default", "en")
                        put("description", "Ngôn ngữ nguồn (mặc định: en)")
                    })
                    put("target_lang", JSONObject().apply {
                        put("type", "string")
                        put("default", "vi")
                        put("description", "Ngôn ngữ đích (mặc định: vi)")
                    })
                })
                put("required", JSONArray())
            }
        ),
        McpTool(
            name = "self.translate.stop",
            description = """🛑 DỪNG DỊCH - Tắt chế độ dịch tức thời

Dùng khi:
- Muốn dừng dịch và trở về chế độ bình thường
- Kết thúc phiên dịch""",
            inputSchema = JSONObject().apply {
                put("type", "object")
            }
        )
    )

    /**
     * Thực thi MCP tool
     */
    suspend fun executeTool(toolName: String, args: JSONObject): McpToolResult {
        Log.i(TAG, "executeTool: $toolName, args=$args")

        return when {
            toolName.contains("translate.start") -> {
                val sourceLang = args.optString("source_lang", "en")
                val targetLang = args.optString("target_lang", "vi")

                // Gửi translation_mode message cho server
                val json = JSONObject().apply {
                    put("type", "translation_mode")
                    put("enabled", true)
                    put("source_lang", sourceLang)
                    put("target_lang", targetLang)
                }

                try {
                    protocol?.sendText(json.toString())
                    Log.i(TAG, "Sent translation_mode start to server")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send translation_mode", e)
                }

                // Đăng ký protocol cho AudioStreamManager
                protocol?.let { audioStreamManager.protocol = it }

                // Bắt đầu thu âm từ mic kính
                audioStreamManager.startStreaming(sourceLang, targetLang)

                McpToolResult(
                    content = listOf(McpContent.TextContent("🔄 Đã bật dịch tức thời: $sourceLang → $targetLang"))
                )
            }
            toolName.contains("translate.stop") -> {
                // Dừng thu âm
                audioStreamManager.stopStreaming()

                // Gửi translation_mode stop cho server
                val json = JSONObject().apply {
                    put("type", "translation_mode")
                    put("enabled", false)
                }

                try {
                    protocol?.sendText(json.toString())
                    Log.i(TAG, "Sent translation_mode stop to server")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send translation_mode", e)
                }

                McpToolResult(
                    content = listOf(McpContent.TextContent("⏹️ Đã tắt dịch tức thời"))
                )
            }
            else -> McpToolResult(
                content = listOf(McpContent.TextContent("Unknown tool: $toolName")),
                isError = true
            )
        }
    }
}