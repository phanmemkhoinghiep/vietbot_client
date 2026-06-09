package vn.vietbot.client.mcp.tools

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.util.Log
import vn.vietbot.client.mcp.ContentItem
import vn.vietbot.client.mcp.McpCallToolResult
import vn.vietbot.client.mcp.McpProperty
import vn.vietbot.client.mcp.McpServer

/**
 * MCP Tool for creating alarms
 */
class AlarmTool(private val context: Context) {
    private val TAG = "AlarmTool"

    fun register(server: McpServer) {
        server.registerTool(
            name = "self.alarm.create_alarm",
            description = "Tạo báo thức mới vào thời gian chỉ định. BAT BUOC GOI KHI: Người dùng yêu cầu đặt báo thức, hẹn giờ báo thức, chuông báo thức. Ví dụ: 'Đặt báo thức 7 giờ', 'Hẹn giờ 5 phút nữa', 'Báo thức dậy đi'.",
            properties = mapOf(
                "hour" to McpProperty(
                    type = "integer",
                    description = "Giờ cho báo thức (0-23). Ví dụ: 7 cho 7 giờ sáng, 22 cho 10 giờ tối."
                ),
                "minutes" to McpProperty(
                    type = "integer",
                    description = "Phút cho báo thức (0-59). Ví dụ: 30 cho nửa giờ."
                ),
                "message" to McpProperty(
                    type = "string",
                    description = "Nhãn/thông điệp cho báo thức (mặc định: 'Báo thức')."
                ),
                "skip_ui" to McpProperty(
                    type = "boolean",
                    description = "Bỏ qua giao diện báo thức và tạo ngay lập tức. Mặc định là true."
                )
            ),
            required = listOf("hour", "minutes")
        ) { arguments ->
            Log.d(TAG, "Received create_alarm call with arguments: $arguments")

            val hour = when (val h = arguments["hour"]) {
                is Number -> h.toInt()
                is String -> h.toIntOrNull() ?: 0
                else -> 0
            }
            val minutes = when (val m = arguments["minutes"]) {
                is Number -> m.toInt()
                is String -> m.toIntOrNull() ?: 0
                else -> 0
            }
            val message = arguments["message"] as? String ?: "Báo thức"
            val skipUi = when (val s = arguments["skip_ui"]) {
                is Boolean -> s
                is String -> s.toBoolean()
                else -> true
            }

            val result = createAlarm(hour, minutes, message, skipUi)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }
    }

    private fun createAlarm(hour: Int, minutes: Int, message: String, skipUi: Boolean): String {
        return try {
            Log.i(TAG, "Creating alarm: $hour:$minutes, message=$message, skipUi=$skipUi")
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                putExtra(AlarmClock.EXTRA_MESSAGE, message)
                putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            context.startActivity(intent)
            Log.i(TAG, "Alarm intent sent successfully")
            "Đã tạo báo thức lúc %02d:%02d thành công".format(hour, minutes)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating alarm", e)
            "Error tạo báo thức: ${e.message}"
        }
    }
}