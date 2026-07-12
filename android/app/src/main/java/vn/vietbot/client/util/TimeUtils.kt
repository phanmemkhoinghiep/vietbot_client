package vn.vietbot.client.util

/**
 * Shared time formatting utilities.
 */
object TimeUtils {
    /**
     * Format a duration in milliseconds as a human-readable Vietnamese relative time string.
     */
    fun formatAge(deltaMillis: Long): String {
        val seconds = deltaMillis / 1000
        return when {
            seconds < 60 -> "vừa xong"
            seconds < 3600 -> "${seconds / 60} phút trước"
            seconds < 86400 -> "${seconds / 3600} giờ trước"
            else -> "${seconds / 86400} ngày trước"
        }
    }
}
