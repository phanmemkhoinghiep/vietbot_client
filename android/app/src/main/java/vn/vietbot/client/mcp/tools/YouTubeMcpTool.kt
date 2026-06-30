package vn.vietbot.client.mcp.tools

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import vn.vietbot.client.data.MediaHistoryItem
import vn.vietbot.client.data.SettingsRepository
import vn.vietbot.client.mcp.ContentItem
import vn.vietbot.client.mcp.McpCallToolResult
import vn.vietbot.client.mcp.McpProperty
import vn.vietbot.client.mcp.McpServer

/**
 * MCP Tool for playing music/videos on YouTube
 *
 * Opens YouTube app via deep link with search query. YouTube app handles
 * playback independently. Tab Media in UI displays history of played songs.
 */
class YouTubeMcpTool(
    private val context: Context,
    private val settingsRepository: SettingsRepository? = null
) {
    companion object {
        private const val TAG = "YouTubeMcpTool"
    }

    fun register(server: McpServer) {
        server.registerTool(
            name = "self.youtube.play",
            description = """
Phát bài hát hoặc video trên YouTube.
BẮT BUỘC GỌI KHI: Người dùng yêu cầu phát/mở/chơi/play nhạc, bài hát, video trên YouTube.
Các pattern từ khóa nhận diện:
- Mở/phát/chơi/play + (nhạc/bài/bài hát/video) + [tên bài hát] + (trên YouTube/youtube/YT)
- "Mở bài [tên] YouTube", "Phát nhạc [tên] youtube", "Play [tên] on youtube", "Cho nghe bài [tên] youtube"
- "Tìm bài [tên] trên YouTube", "Tìm kiếm [tên] youtube", "Search [tên] youtube"
- "Mở YouTube tìm [tên]", "Bật YouTube bài [tên]"
Ví dụ: 'Phát bài Shape of You trên YouTube', 'Mở YouTube bài Despacito', 'Play See You Again youtube', 'Cho tôi nghe bài Lạc Trôi youtube', 'Tìm bài Never Gonna Give You Up youtube'
""",
            properties = mapOf(
                "song_name" to McpProperty(
                    type = "string",
                    description = "Tên bài hát hoặc video cần tìm kiếm và phát trên YouTube. Chỉ chứa tên bài, không chứa từ 'youtube', 'play', 'phát', 'mở'."
                )
            ),
            required = listOf("song_name")
        ) { arguments ->
            val songName = arguments["song_name"] as? String ?: ""
            Log.d(TAG, "Play YouTube: $songName")

            val result = playYouTube(songName)
            McpCallToolResult(
                content = listOf(ContentItem(text = result.message)),
                isError = result.error
            )
        }

        // Tool: Stop/pause YouTube playback via media button event
        server.registerTool(
            name = "self.youtube.stop",
            description = """
Dừng hoặc tạm dừng phát nhạc/video trên YouTube.
BẮT BUỘC GỌI KHI: Người dùng yêu cầu dừng/ngừng/tắt/pause/stop nhạc, bài hát, video YouTube.
Các pattern từ khóa nhận diện:
- Dừng/ngừng/tắt/stop/pause + (nhạc/bài/bài hát/video) + [tên bài] + (YouTube/youtube/YT)
- Dừng/ngừng/tắt/stop + (phát nhạc/YouTube/YT)
- "Tạm dừng YouTube", "Pause youtube", "Stop playing", "Ngắt nhạc", "Tắt nhạc đi", "Tắt YouTube"
- "Dừng phát bài [tên]", "Stop bài [tên] youtube", "Ngừng phát video [tên]"
Ví dụ: 'Dừng phát', 'Tạm dừng YouTube', 'Ngắt nhạc', 'Tắt nhạc đi', 'Pause youtube', 'Stop bài Shape of You youtube'
""",
            properties = emptyMap(),
            required = emptyList()
        ) { _ ->
            val result = stopYouTube()
            McpCallToolResult(
                content = listOf(ContentItem(text = result.message)),
                isError = result.error
            )
        }

        // Tool 2: Get recent media history
        server.registerTool(
            name = "self.youtube.get_history",
            description = "Lấy lịch sử các bài hát đã phát trên YouTube. SỬ DỤNG KHI: Người dùng hỏi về các bài đã nghe gần đây, ví dụ: 'Tôi vừa nghe gì?', 'Lịch sử bài hát?'.",
            properties = mapOf(
                "limit" to McpProperty(
                    type = "integer",
                    description = "Số lượng bài hát tối đa (mặc định: 10)."
                )
            ),
            required = emptyList()
        ) { arguments ->
            val limit = when (val l = arguments["limit"]) {
                is Number -> l.toInt()
                is String -> l.toIntOrNull() ?: 10
                else -> 10
            }

            val history = settingsRepository?.mediaHistory ?: emptyList()
            val result = if (history.isEmpty()) {
                "Chưa có bài hát nào trong lịch sử"
            } else {
                history.take(limit).mapIndexed { i, item ->
                    val age = (System.currentTimeMillis() - item.timestamp) / 1000
                    val timeStr = formatAge(age)
                    "${i + 1}. ${item.songName} ($timeStr)"
                }.joinToString("\n")
            }

            McpCallToolResult(
                content = listOf(ContentItem(text = result)),
                isError = false
            )
        }
    }

    private data class PlayResult(val message: String, val error: Boolean = false)

    private fun playYouTube(songName: String): PlayResult {
        if (songName.isBlank()) {
            return PlayResult("Error: Tên bài hát không được rỗng", error = true)
        }

        return try {
            val encodedQuery = Uri.encode(songName)
            // Use https:// scheme - opens YouTube app if installed, falls back to browser
            val youtubeUri = "https://www.youtube.com/results?search_query=$encodedQuery"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(youtubeUri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)

            // Save to history
            settingsRepository?.let { repo ->
                val newItem = MediaHistoryItem(
                    songName = songName,
                    timestamp = System.currentTimeMillis(),
                    source = "youtube"
                )
                val updated = (listOf(newItem) + repo.mediaHistory)
                repo.mediaHistory = updated
                Log.i(TAG, "Saved to history: $songName (total: ${updated.size})")
            }

            Log.i(TAG, "Opened YouTube for: $songName")
            PlayResult("Đã mở YouTube tìm kiếm: $songName")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "YouTube app not found", e)
            PlayResult(
                "Error: Không tìm thấy ứng dụng YouTube. Vui lòng cài đặt YouTube hoặc thử trình duyệt web.",
                error = true
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error opening YouTube", e)
            PlayResult("Error: ${e.message}", error = true)
        }
    }

    /**
     * Send MEDIA_PAUSE key event via AudioManager.
     * This is system-level and does NOT require MEDIA_CONTENT_CONTROL permission.
     * If YouTube is the active media session, it will pause playback.
     */
    private fun stopYouTube(): PlayResult {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            // Use long-press for stop instead of pause
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)
            val eventUp = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE)

            audioManager.dispatchMediaKeyEvent(event)
            audioManager.dispatchMediaKeyEvent(eventUp)

            Log.i(TAG, "Sent MEDIA_PAUSE key event")
            PlayResult("Đã gửi lệnh tạm dừng phát nhạc")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending media pause", e)
            PlayResult(
                "Không thể dừng phát nhạc từ xa. Hãy dừng trực tiếp trên app YouTube.",
                error = false
            )
        }
    }

    private fun formatAge(seconds: Long): String {
        return when {
            seconds < 60 -> "vừa xong"
            seconds < 3600 -> "${seconds / 60} phút trước"
            seconds < 86400 -> "${seconds / 3600} giờ trước"
            else -> "${seconds / 86400} ngày trước"
        }
    }
}
