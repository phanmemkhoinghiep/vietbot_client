#ifndef LYRIC_MANAGER_H
#define LYRIC_MANAGER_H

/**
 * @file lyric_manager.h
 * @brief Standalone lyrics module – download, parse (LRC), sync and display.
 *
 * Designed to be generic and reusable. Owns its own FreeRTOS task for
 * background downloading; the caller drives display updates by calling
 * UpdateDisplay() with the current playback timestamp.
 */

#include <string>
#include <vector>
#include <utility>
#include <atomic>
#include <cstdint>

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>

/* ------------------------------------------------------------------ */
/*  Macros                                                            */
/* ------------------------------------------------------------------ */

/** Stack size for lyric download task */
#define LYRIC_TASK_STACK_SIZE   (4 * 1024)

/** Lyric download task priority */
#define LYRIC_TASK_PRIORITY     4

/** Lyric download task core */
#define LYRIC_TASK_CORE         0

/** Maximum number of lyric download retries */
#define LYRIC_MAX_RETRIES       3

/** Delay between retries (ms) */
#define LYRIC_RETRY_DELAY_MS    500

/** Maximum number of redirects to follow */
#define LYRIC_MAX_REDIRECTS     5

/* ------------------------------------------------------------------ */
/*  LyricLine – single parsed line                                    */
/* ------------------------------------------------------------------ */

struct LyricLine {
    int         timestamp_ms;   ///< Position in milliseconds
    std::string text;           ///< UTF-8 lyric text

    bool operator<(const LyricLine& other) const {
        return timestamp_ms < other.timestamp_ms;
    }
};

/* ------------------------------------------------------------------ */
/*  LyricManager                                                      */
/* ------------------------------------------------------------------ */

class LyricManager {
public:
    LyricManager();
    ~LyricManager();

    /**
     * Start downloading and parsing lyrics from the given URL.
     * Runs in a background FreeRTOS task. Non-blocking.
     */
    bool Start(const std::string& lyric_url);

    /**
     * Stop the lyric task and clear all data.
     */
    void Stop();

    /**
     * Call periodically (e.g. every decoded PCM frame) to update the
     * display with the correct lyric at @p current_time_ms.
     *
     * @param current_time_ms  Current playback position in milliseconds.
     */
    void UpdateDisplay(int64_t current_time_ms);

    /** Returns true if lyrics have been loaded. */
    bool HasLyrics() const;

    /** Return total number of parsed lyric lines. */
    size_t GetLineCount() const;

    /** Get a copy of all parsed lyrics (thread-safe). */
    std::vector<LyricLine> GetAllLines() const;

    /** Get the current lyric index (for external sync). */
    int GetCurrentIndex() const { return current_index_.load(); }

private:
    /* ---- Task ---- */
    static void TaskEntry(void* param);
    void TaskFunc();

    /* ---- Download & Parse ---- */
    bool Download(const std::string& url, std::string& out_content);
    bool ParseLrc(const std::string& content);

    /**
     * Optional: subclasses or users can inject custom HTTP headers
     * by overriding this in derived classes if ever needed.
     */
    void PrepareHttp(void* http_ptr);

    /* ---- State ---- */
    std::string               lyric_url_;
    std::vector<LyricLine>    lines_;
    SemaphoreHandle_t         lines_mutex_;     ///< Protects lines_
    std::atomic<int>          current_index_;
    std::atomic<bool>         is_running_;
    TaskHandle_t              task_handle_;
};

#endif // LYRIC_MANAGER_H
