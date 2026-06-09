package vn.vietbot.client.mcp.tools

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import vn.vietbot.client.mcp.ContentItem
import vn.vietbot.client.mcp.McpCallToolResult
import vn.vietbot.client.mcp.McpProperty
import vn.vietbot.client.mcp.McpServer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP Tool for reading notifications
 */
class NotificationTool(private val context: Context) {
    private val TAG = "NotificationTool"

    private val notificationManager by lazy {
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    // Hàm chuẩn hóa để xử lý lỗi phát âm
    private fun normalizeAppName(text: String): String {
        val accents = "àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ"
        val noAccents = "aaaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyd"
        var result = text.lowercase()
        for (i in accents.indices) {
            result = result.replace(accents[i], noAccents[i])
        }
        // Xử lý các trường hợp phát âm sai phổ biến
        result = result.replace("va lo", "zalo")
        result = result.replace("diu tup", "youtube")
        result = result.replace("ziu tup", "youtube")
        result = result.replace("iu tup", "youtube")
        result = result.replace("tele gram", "telegram")
        result = result.replace("mes sen ger", "messenger")
        return result.replace(Regex("[^a-z0-9]"), "")
    }

    fun register(server: McpServer) {
        // Tool 1: Check notification counts
        server.registerTool(
            name = "self.notification.check_counts",
            description = "Kiểm tra số lượng thông báo của các ứng dụng. BAT BUOC GOI KHI: Người dùng muốn xem có bao nhiêu thông báo, kiểm tra thông báo mới, có tin nhắn gì không. Ví dụ: 'Có thông báo gì không', 'Kiểm tra tin nhắn', 'Xem thông báo'.",
            properties = mapOf(
                "hours" to McpProperty(
                    type = "integer",
                    description = "Số giờ để kiểm tra lại (mặc định: 24 giờ)."
                )
            ),
            required = emptyList()
        ) { arguments ->
            val hours = when (val h = arguments["hours"]) {
                is Number -> h.toInt()
                is String -> h.toIntOrNull() ?: 24
                else -> 24
            }

            val result = checkNotificationCounts(hours)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }

        // Tool 2: Summarize app notifications
        server.registerTool(
            name = "self.notification.summarize_app",
            description = "Tóm tắt thông báo của một ứng dụng cụ thể. BAT BUOC GOI KHI: Người dùng muốn xem thông báo của một app cụ thể. Ví dụ: 'Thông báo Zalo', 'Tin nhắn Facebook', 'Messenger có gì không', 'Xem thông báo YouTube'.",
            properties = mapOf(
                "app_name" to McpProperty(
                    type = "string",
                    description = "Tên ứng dụng cần xem thông báo (ví dụ: 'Zalo', 'Messenger', 'Facebook', 'Gmail')."
                ),
                "hours" to McpProperty(
                    type = "integer",
                    description = "Số giờ để kiểm tra lại (mặc định: 24 giờ)."
                )
            ),
            required = listOf("app_name")
        ) { arguments ->
            val appName = arguments["app_name"] as? String ?: ""
            val hours = when (val h = arguments["hours"]) {
                is Number -> h.toInt()
                is String -> h.toIntOrNull() ?: 24
                else -> 24
            }

            val result = summarizeAppNotifications(appName, hours)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }

        // Tool 3: Get latest notifications
        server.registerTool(
            name = "self.notification.get_latest",
            description = "Lấy các thông báo mới nhất từ tất cả ứng dụng. BAT BUOC GOI KHI: Người dùng muốn xem tất cả thông báo gần đây, kiểm tra nhanh có gì mới không.",
            properties = mapOf(
                "count" to McpProperty(
                    type = "integer",
                    description = "Số lượng thông báo mới nhất muốn lấy (mặc định: 10)."
                )
            ),
            required = emptyList()
        ) { arguments ->
            val count = when (val c = arguments["count"]) {
                is Number -> c.toInt()
                is String -> c.toIntOrNull() ?: 10
                else -> 10
            }

            val result = getLatestNotifications(count)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    private fun getInstalledAppsWithNotifications(): List<Pair<String, String>> {
        val apps = mutableListOf<Pair<String, String>>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channels = notificationManager.notificationChannels
                for (channel in channels) {
                    if (channel.importance != NotificationManager.IMPORTANCE_NONE) {
                        val pkgName: String = channel.group
                        val appName = getAppNameFromPackage(pkgName)
                        apps.add(Pair(pkgName, appName))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting notification apps", e)
        }

        return apps.distinctBy { it.first }
    }

    private fun getAppNameFromPackage(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun checkNotificationCounts(hours: Int): String {
        if (!hasNotificationPermission()) {
            return "Error: Không có quyền đọc thông báo. Vui lòng bật thông báo cho ứng dụng này."
        }

        return try {
            val apps = getInstalledAppsWithNotifications()

            if (apps.isEmpty()) {
                return "Hiện tại không có ứng dụng nào có thông báo."
            }

            val result = StringBuilder()
            result.appendLine("Danh sách ứng dụng có thông báo (${apps.size} ứng dụng):")

            apps.take(20).forEach { (pkg, name) ->
                result.appendLine("- $name")
            }

            if (apps.size > 20) {
                result.appendLine("... và ${apps.size - 20} ứng dụng khác")
            }

            result.appendLine()
            result.appendLine("Để xem thông báo chi tiết, hãy hỏi: 'Xem thông báo [tên ứng dụng]'")

            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notification counts", e)
            "Error kiểm tra thông báo: ${e.message}"
        }
    }

    private fun summarizeAppNotifications(appName: String, hours: Int): String {
        if (!hasNotificationPermission()) {
            return "Error: Không có quyền đọc thông báo. Vui lòng bật thông báo cho ứng dụng này."
        }

        return try {
            val normalizedInput = normalizeAppName(appName)
            val apps = getInstalledAppsWithNotifications()

            // Tìm app khớp với tên
            var targetApp = apps.find { (pkg, name) ->
                name.equals(appName, ignoreCase = true) ||
                normalizeAppName(name) == normalizedInput ||
                pkg.contains(normalizedInput)
            }

            if (targetApp == null) {
                val availableApps = apps.take(10).joinToString(", ") { it.second }
                return "Không tìm thấy thông báo từ '$appName'. Các ứng dụng có thông báo: $availableApps"
            }

            val (packageName, displayName) = targetApp

            val result = StringBuilder()
            result.appendLine("Thông báo từ $displayName ($hours giờ qua):")
            result.appendLine()
            result.appendLine("Lưu ý: Để xem nội dung chi tiết, hãy mở ứng dụng $displayName trên điện thoại.")
            result.appendLine()
            result.appendLine("Bạn có thể:")
            result.appendLine("- Mở $displayName để xem thông báo")
            result.appendLine("- Yêu cầu tôi mở $displayName")

            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error summarizing notifications", e)
            "Error xem thông báo: ${e.message}"
        }
    }

    private fun getLatestNotifications(count: Int): String {
        if (!hasNotificationPermission()) {
            return "Error: Không có quyền đọc thông báo. Vui lòng bật thông báo cho ứng dụng này."
        }

        return try {
            val apps = getInstalledAppsWithNotifications()

            if (apps.isEmpty()) {
                return "Không có thông báo nào gần đây."
            }

            val result = StringBuilder()
            result.appendLine("$count ứng dụng có thông báo gần đây:")

            apps.take(count).forEachIndexed { index, (pkg, name) ->
                result.appendLine("${index + 1}. $name")
            }

            result.appendLine()
            result.appendLine("Để xem chi tiết thông báo, hãy hỏi: 'Xem thông báo [tên ứng dụng]'")

            result.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting latest notifications", e)
            "Error lấy thông báo: ${e.message}"
        }
    }
}