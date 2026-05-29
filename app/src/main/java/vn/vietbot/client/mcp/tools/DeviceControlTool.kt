package vn.vietbot.client.mcp.tools

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import vn.vietbot.client.mcp.ContentItem
import vn.vietbot.client.mcp.McpCallToolResult
import vn.vietbot.client.mcp.McpProperty
import vn.vietbot.client.mcp.McpServer

/**
 * MCP Tool for device control (volume, brightness, etc.)
 */
class DeviceControlTool(private val context: Context) {
    private val TAG = "DeviceControlTool"

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    fun register(server: McpServer) {
        // Tool 1: Adjust volume
        server.registerTool(
            name = "self.device.set_volume",
            description = "Điều chỉnh âm lượng thiết bị. BAT BUOC GOI KHI: Người dùng yêu cầu tăng/giảm âm lượng, chỉnh volume, tắt tiếng, bật tiếng, âm lượng to/nhỏ. Ví dụ: 'Tăng âm lượng', 'Giảm volume', 'Âm thanh to hơn', 'Tắt tiếng'.",
            properties = mapOf(
                "level" to McpProperty(
                    type = "integer",
                    description = "Mức âm lượng từ 0-100. Nếu là 'up' thì tăng, 'down' thì giảm 1 mức."
                ),
                "stream" to McpProperty(
                    type = "string",
                    description = "Loại âm thanh: 'music' (mặc định), 'ring', 'notification', 'alarm', 'system'."
                )
            ),
            required = listOf("level")
        ) { arguments ->
            val level = arguments["level"] ?: arguments["level"]
            val stream = arguments["stream"] as? String ?: "music"

            val result = adjustVolume(level, stream)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }

        // Tool 2: Adjust brightness
        server.registerTool(
            name = "self.device.set_brightness",
            description = "Điều chỉnh độ sáng màn hình. BAT BUOC GOI KHI: Người dùng yêu cầu tăng/giảm độ sáng, màn hình sáng hơn/tối hơn, chỉnh độ sáng. Ví dụ: 'Tăng độ sáng', 'Màn hình tối hơn', 'Chỉnh độ sáng 50'.",
            properties = mapOf(
                "level" to McpProperty(
                    type = "integer",
                    description = "Mức độ sáng từ 0-100. Nếu là 'up' thì tăng, 'down' thì giảm."
                )
            ),
            required = listOf("level")
        ) { arguments ->
            val level = arguments["level"]

            val result = adjustBrightness(level)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }

        // Tool 3: Get volume info
        server.registerTool(
            name = "self.device.get_volume",
            description = "Lấy thông tin âm lượng hiện tại của thiết bị. BAT BUOC GOI KHI: Người dùng hỏi 'Âm lượng bao nhiêu', 'Mức volume hiện tại', 'Bao nhiêu phần trăm'.",
            properties = emptyMap(),
            required = emptyList()
        ) { _ ->
            val result = getVolumeInfo()
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }

        // Tool 4: Toggle WiFi
        server.registerTool(
            name = "self.device.toggle_wifi",
            description = "Bật hoặc tắt WiFi. BAT BUOC GOI KHI: Người dùng yêu cầu bật/tắt WiFi, kết nối mạng. Ví dụ: 'Bật WiFi', 'Tắt WiFi', 'Kết nối WiFi'.",
            properties = mapOf(
                "enabled" to McpProperty(
                    type = "boolean",
                    description = "true để bật, false để tắt."
                )
            ),
            required = listOf("enabled")
        ) { arguments ->
            val enabled = when (val e = arguments["enabled"]) {
                is Boolean -> e
                is String -> e.toBoolean()
                else -> true
            }

            val result = toggleWifi(enabled)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }

        // Tool 5: Toggle Bluetooth
        server.registerTool(
            name = "self.device.toggle_bluetooth",
            description = "Bật hoặc tắt Bluetooth. BAT BUOC GOI KHI: Người dùng yêu cầu bật/tắt Bluetooth, kết nối tai nghe Bluetooth. Ví dụ: 'Bật Bluetooth', 'Tắt Bluetooth', 'Kết nối Bluetooth'.",
            properties = mapOf(
                "enabled" to McpProperty(
                    type = "boolean",
                    description = "true để bật, false để tắt."
                )
            ),
            required = listOf("enabled")
        ) { arguments ->
            val enabled = when (val e = arguments["enabled"]) {
                is Boolean -> e
                is String -> e.toBoolean()
                else -> true
            }

            val result = toggleBluetooth(enabled)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }

        // Tool 6: Toggle Airplane Mode
        server.registerTool(
            name = "self.device.toggle_airplane_mode",
            description = "Bật hoặc tắt chế độ máy bay. BAT BUOC GOI KHI: Người dùng yêu cầu bật/tắt chế độ máy bay. Ví dụ: 'Bật máy bay', 'Tắt máy bay', 'Chế độ máy bay'.",
            properties = mapOf(
                "enabled" to McpProperty(
                    type = "boolean",
                    description = "true để bật, false để tắt."
                )
            ),
            required = listOf("enabled")
        ) { arguments ->
            val enabled = when (val e = arguments["enabled"]) {
                is Boolean -> e
                is String -> e.toBoolean()
                else -> true
            }

            val result = toggleAirplaneMode(enabled)
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = result.startsWith("Error")
            )
        }

        // Tool 7: Get device info
        server.registerTool(
            name = "self.device.get_info",
            description = "Lấy thông tin thiết bị bao gồm pin, WiFi, Bluetooth, độ sáng. BAT BUOC GOI KHI: Người dùng hỏi thông tin thiết bị, tình trạng pin, trạng thái kết nối.",
            properties = emptyMap(),
            required = emptyList()
        ) { _ ->
            val result = getDeviceInfo()
            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }
    }

    private fun adjustVolume(level: Any?, stream: String): String {
        return try {
            val streamType = when (stream) {
                "music" -> AudioManager.STREAM_MUSIC
                "ring" -> AudioManager.STREAM_RING
                "notification" -> AudioManager.STREAM_NOTIFICATION
                "alarm" -> AudioManager.STREAM_ALARM
                "system" -> AudioManager.STREAM_SYSTEM
                else -> AudioManager.STREAM_MUSIC
            }

            val currentVolume = audioManager.getStreamVolume(streamType)
            val maxVolume = audioManager.getStreamMaxVolume(streamType)

            val newVolume = when (level) {
                is Number -> {
                    val percent = level.toInt().coerceIn(0, 100)
                    (maxVolume * percent / 100)
                }
                is String -> {
                    when (level.lowercase()) {
                        "up", "tăng", "+" -> (currentVolume + 1).coerceAtMost(maxVolume)
                        "down", "giảm", "-" -> (currentVolume - 1).coerceAtLeast(0)
                        else -> {
                            val percent = level.toIntOrNull()?.coerceIn(0, 100) ?: 50
                            (maxVolume * percent / 100)
                        }
                    }
                }
                else -> currentVolume
            }

            audioManager.setStreamVolume(streamType, newVolume, 0)
            val percent = (newVolume * 100 / maxVolume)
            "Đã điều chỉnh âm lượng ${stream} lên $percent% (mức $newVolume/$maxVolume)"
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting volume", e)
            "Error điều chỉnh âm lượng: ${e.message}"
        }
    }

    private fun adjustBrightness(level: Any?): String {
        return try {
            val layoutParams = android.view.WindowManager.LayoutParams()
            val brightness = when (level) {
                is Number -> {
                    val percent = level.toInt().coerceIn(0, 100)
                    percent / 100f
                }
                is String -> {
                    when (level.lowercase()) {
                        "up", "tăng", "+", "sáng hơn" -> {
                            val current = layoutParams.screenBrightness.coerceAtLeast(0.3f)
                            (current + 0.1f).coerceAtMost(1f)
                        }
                        "down", "giảm", "-", "tối hơn" -> {
                            val current = layoutParams.screenBrightness.coerceAtMost(0.8f)
                            (current - 0.1f).coerceAtLeast(0.01f)
                        }
                        else -> {
                            val percent = level.toIntOrNull()?.coerceIn(0, 100) ?: 50
                            percent / 100f
                        }
                    }
                }
                else -> 0.5f
            }

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val brightnessValue = (brightness * 255).toInt().coerceIn(1, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )

            val percent = (brightness * 100).toInt()
            "Đã điều chỉnh độ sáng lên $percent%"
        } catch (e: SecurityException) {
            "Error: Không có quyền thay đổi độ sáng. Vui lòng cấp quyền WRITE_SETTINGS."
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting brightness", e)
            "Error điều chỉnh độ sáng: ${e.message}"
        }
    }

    private fun getVolumeInfo(): String {
        return try {
            val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val musicPercent = (musicVolume * 100 / musicMax)

            val ringVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val ringMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            val ringPercent = (ringVolume * 100 / ringMax)

            """
Thông tin âm lượng:
- Nhạc/Media: $musicPercent% ($musicVolume/$musicMax)
- Nhạc chuông: $ringPercent% ($ringVolume/$ringMax)
            """.trimIndent()
        } catch (e: Exception) {
            "Error lấy thông tin âm lượng: ${e.message}"
        }
    }

    private fun toggleWifi(enabled: Boolean): String {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "Đã mở cài đặt WiFi"
        } catch (e: Exception) {
            "Error: Không thể mở cài đặt WiFi. ${e.message}"
        }
    }

    private fun toggleBluetooth(enabled: Boolean): String {
        return try {
            if (enabled) {
                val intent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Đã yêu cầu bật Bluetooth"
            } else {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Đã mở cài đặt Bluetooth"
            }
        } catch (e: Exception) {
            "Error: Không thể ${if (enabled) "bật" else "tắt"} Bluetooth. ${e.message}"
        }
    }

    private fun toggleAirplaneMode(enabled: Boolean): String {
        return try {
            val intent = android.content.Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "Đã mở cài đặt chế độ máy bay"
        } catch (e: Exception) {
            "Error mở cài đặt: ${e.message}"
        }
    }

    private fun getDeviceInfo(): String {
        return try {
            val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, 0) ?: 0
            val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
            val batteryPercent = (level * 100 / scale)

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val wifiEnabled = wifiManager.isWifiEnabled

            val musicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val musicMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val volumePercent = (musicVolume * 100 / musicMax)

            val brightnessValue = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val brightnessPercent = (brightnessValue * 100 / 255)

            """
Thông tin thiết bị:
- Pin: $batteryPercent%
- WiFi: ${if (wifiEnabled) "Bật" else "Tắt"}
- Âm lượng: $volumePercent%
- Độ sáng: $brightnessPercent%
            """.trimIndent()
        } catch (e: Exception) {
            "Error lấy thông tin thiết bị: ${e.message}"
        }
    }
}