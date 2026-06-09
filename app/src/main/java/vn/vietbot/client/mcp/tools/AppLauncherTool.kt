package vn.vietbot.client.mcp.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import vn.vietbot.client.mcp.ContentItem
import vn.vietbot.client.mcp.McpCallToolResult
import vn.vietbot.client.mcp.McpProperty
import vn.vietbot.client.mcp.McpServer

/**
 * MCP Tool for launching applications
 */
class AppLauncherTool(private val context: Context) {
    private val TAG = "AppLauncherTool"

    // Common app package mappings for voice recognition
    private val appMappings = mapOf(
        "camera" to listOf("com.android.camera2", "com.google.android.GoogleCamera", "com.samsung.android.app.camera"),
        "youtube" to listOf("com.google.android.youtube", "com.vanced.android.youtube"),
        "zalo" to listOf("com.zing.zalo"),
        "facebook" to listOf("com.facebook.katana", "com.facebook.lite"),
        "messenger" to listOf("com.facebook.orca"),
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "whatsapp" to listOf("com.whatsapp"),
        "chrome" to listOf("com.android.chrome"),
        "google" to listOf("com.google.android.googlequicksearchbox"),
        "maps" to listOf("com.google.android.apps.maps"),
        "gmail" to listOf("com.google.android.gm"),
        "phone" to listOf("com.android.contacts", "com.android.dialer"),
        "settings" to listOf("com.android.settings"),
        "calculator" to listOf("com.android.calculator2", "com.google.android.calculator"),
        "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
        "music" to listOf("com.google.android.apps.youtube.music", "com.spotify.music"),
        "spotify" to listOf("com.spotify.music"),
        "netflix" to listOf("com.netflix.mediaclient"),
        "tiktok" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
        "zalo pay" to listOf("com.zalopay"),
        "momo" to listOf("com.mservice.docquyen"),
        "shopee" to listOf("com.shopee.vn"),
        "lazada" to listOf("com.lazada.android"),
        "tiki" to listOf("vn.tiki.app"),
        "bank" to listOf("com.vietinbank.ipay", "com.bidv.bidvworld", "com.vietcombank.acb"),
        "mb bank" to listOf("com.mservice.momoplace"),
        "vpbank" to listOf("com.vpbank.toppay"),
        "tpbank" to listOf("com.tpbbank.onepay"),
        "agribank" to listOf("vn.com.agribank.emobile"),
    )

    fun register(server: McpServer) {
        // Tool 1: Open app by name
        server.registerTool(
            name = "self.app.open",
            description = "Mở một ứng dụng trên điện thoại. BAT BUOC GOI KHI: Người dùng yêu cầu mở một ứng dụng cụ thể. Ví dụ: 'Mở YouTube', 'Bật Zalo', 'Vào Facebook', 'Mở camera', 'Chạy Google Maps'.",
            properties = mapOf(
                "app_name" to McpProperty(
                    type = "string",
                    description = "Tên ứng dụng cần mở (ví dụ: 'youtube', 'zalo', 'facebook', 'camera', 'maps', 'chrome', 'telegram')."
                )
            ),
            required = listOf("app_name")
        ) { arguments ->
            val appName = arguments["app_name"] as? String ?: ""
            Log.d(TAG, "Opening app: $appName")

            val result = openApp(appName)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }

        // Tool 2: List installed apps
        server.registerTool(
            name = "self.app.list",
            description = "Liệt kê các ứng dụng người dùng đã cài đặt trên thiết bị (không bao gồm ứng dụng hệ thống). BAT BUOC GOI KHI: Người dùng hỏi có những ứng dụng nào hoặc muốn xem danh sách ứng dụng.",
            properties = mapOf(
                "search" to McpProperty(
                    type = "string",
                    description = "Từ khóa tìm kiếm để lọc ứng dụng (tùy chọn)."
                ),
                "limit" to McpProperty(
                    type = "integer",
                    description = "Số lượng ứng dụng tối đa trả về (mặc định: 20)."
                )
            ),
            required = emptyList()
        ) { arguments ->
            val search = arguments["search"] as? String ?: ""
            val limit = when (val l = arguments["limit"]) {
                is Number -> l.toInt()
                is String -> l.toIntOrNull() ?: 20
                else -> 20
            }

            val result = listInstalledApps(search, limit)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }
    }

    private fun openApp(appName: String): String {
        return try {
            val packageName = findPackageByName(appName)
            if (packageName != null) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                    Log.i(TAG, "Opened app: $packageName")
                    "Đã mở ứng dụng $appName thành công"
                } else {
                    "Error: Không thể mở ứng dụng $appName"
                }
            } else {
                "Error: Không tìm thấy ứng dụng '$appName'. Hãy thử tên khác hoặc cài đặt ứng dụng này."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app", e)
            "Error mở ứng dụng: ${e.message}"
        }
    }

    private fun findPackageByName(appName: String): String? {
        val normalizedName = appName.lowercase().trim()
        Log.d(TAG, "Finding package for: $normalizedName")

        // Get all installed apps (both user and system) for better search
        val pm = context.packageManager
        val allApps = pm.getInstalledApplications(0)
        Log.d(TAG, "Total installed apps: ${allApps.size}")

        // Build package map for faster lookup
        val pkgMap = allApps.associateBy { it.packageName.lowercase() }

        // Check predefined mappings first
        for ((name, packages) in appMappings) {
            Log.d(TAG, "Checking mapping: '$name' vs '$normalizedName'")
            if (normalizedName.contains(name) || name.contains(normalizedName) || normalizedName == name) {
                Log.d(TAG, "Match found in mappings for: $name")
                // Find the first installed package
                for (pkg in packages) {
                    Log.d(TAG, "Checking if package installed: $pkg")
                    if (pkgMap.containsKey(pkg.lowercase())) {
                        Log.i(TAG, "Found installed package: $pkg")
                        return pkg
                    }
                }
            }
        }

        // Search in all installed apps (not just user apps)
        val searchTerms = normalizedName.split(" ", "-", "_").filter { it.isNotEmpty() }

        for (app in allApps) {
            val appLabel = pm.getApplicationLabel(app).toString().lowercase()
            val packageName = app.packageName.lowercase()

            // Match by package name or app label
            val matches = searchTerms.any { term ->
                (appLabel.contains(term) || packageName.contains(term))
            }

            if (matches) {
                Log.i(TAG, "Found in installed apps: ${app.packageName} (label: $appLabel)")
                return app.packageName
            }
        }

        // Log top 20 user-installed apps for debugging
        val userApps = allApps.filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
        Log.d(TAG, "User-installed apps (first 20):")
        userApps.take(20).forEach { app ->
            val label = pm.getApplicationLabel(app).toString()
            Log.d(TAG, "  - $label (${app.packageName})")
        }

        Log.w(TAG, "Package not found for: $normalizedName")
        return null
    }

    private fun listInstalledApps(search: String, limit: Int): String {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            // Include user-installed apps AND updated system apps
            // Exclude only pure system apps that haven't been updated
            val visibleApps = apps.filter { app ->
                val isSystemOnly = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdated = (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                // Show if: user app OR updated system app
                !isSystemOnly || isUpdated
            }

            val filteredApps = if (search.isNotEmpty()) {
                val searchLower = search.lowercase()
                visibleApps.filter { app ->
                    val label = pm.getApplicationLabel(app).toString().lowercase()
                    val pkg = app.packageName.lowercase()
                    label.contains(searchLower) || pkg.contains(searchLower)
                }
            } else {
                visibleApps
            }

            // Sort by app name
            val sortedApps = filteredApps.sortedBy { app ->
                pm.getApplicationLabel(app).toString()
            }

            val result = sortedApps.take(limit).mapIndexed { index, app ->
                val label = pm.getApplicationLabel(app).toString()
                "${index + 1}. $label (${app.packageName})"
            }.joinToString("\n")

            "Danh sách ứng dụng (${sortedApps.size} ứng dụng):\n$result"
        } catch (e: Exception) {
            "Error lấy danh sách ứng dụng: ${e.message}"
        }
    }
}