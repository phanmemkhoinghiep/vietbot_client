#ifndef VIDEO_PLAYER_H
#define VIDEO_PLAYER_H

/**
 * @file video_player.h
 * @brief AVI Video Player component for ESP32-S3.
 *
 * Architecture overview:
 *   - Plays AVI files (MJPEG + PCM audio) from SD card
 *   - Uses espressif/avi_player library for AVI demuxing
 *   - MJPEG frames decoded to RGB565 via esp_new_jpeg (software JPEG decoder)
 *   - Double-buffered RGB565 frame output drawn directly to LCD panel
 *     (bypasses LVGL for maximum frame rate and zero tearing)
 *   - PCM audio forwarded to the board's AudioCodec (I2S output)
 *   - Thread-safe state machine with FreeRTOS primitives
 *   - All large buffers allocated in PSRAM (8 MB external RAM)
 *
 * Usage:
 *   1. Call VideoPlayer::GetInstance() to obtain the singleton.
 *   2. Initialize() once after Board, Display and SdCard are ready.
 *   3. Play("/sdcard/videos/demo.avi") or PlayFile("demo.avi").
 *   4. Stop() to halt playback at any time.
 *
 * Designed for easy extension:
 *   - Override OnVideoFrameReady() / OnAudioFrameReady() for custom processing
 *   - Override OnPlaybackStateChanged() for UI notifications
 *   - Playlist support via ScanDirectory() + Next()/Prev()
 */

#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <functional>
#include <cstdint>

#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <freertos/semphr.h>

#include <esp_lcd_panel_ops.h>

extern "C" {
#include "avi_player.h"
#include "esp_jpeg_dec.h"
}

/* Forward declarations */
class AudioCodec;
class SdCard;
class Display;

/* ------------------------------------------------------------------ */
/*  Configuration constants (tunable per board)                       */
/* ------------------------------------------------------------------ */

/* Enable static task creation (1 = enabled, 0 = disabled) */
#define VIDEO_PLAYER_STATIC_TASK_CREATION 1

/* Number of video lines per frame buffer chunk for partial drawing */
#define VIDEO_AVI_BUFFER_LINES      40 

/** AVI internal read buffer size (bytes). Larger = less file I/O overhead */
#define VIDEO_AVI_BUFFER_SIZE       (60 * 1024)

/** Stack size for the AVI player task (bytes) */
#define VIDEO_AVI_TASK_STACK        (8 * 1024)

/** AVI player task priority (higher than audio source, lower than audio play) */
#define VIDEO_AVI_TASK_PRIORITY     6

/** AVI player task pinned to core (APP_CPU = 1 for best LCD throughput) */
#define VIDEO_AVI_TASK_CORE         1

/** Maximum supported video resolution (pixels). Used for buffer allocation */
#define VIDEO_MAX_WIDTH             320
#define VIDEO_MAX_HEIGHT            240

/** Audio PCM ring-buffer size (bytes) -- smooths out jitter */
#define VIDEO_AUDIO_BUF_SIZE        (16 * 1024)

/** SD card video directory (relative to mount point) */
#define VIDEO_DEFAULT_DIRECTORY     "videos"

/** Maximum number of files in a single directory scan */
#define VIDEO_MAX_FILES             256

/** FPS log interval in seconds (0 = disabled) */
#define VIDEO_FPS_LOG_INTERVAL_SEC  5

/** Render task stack size (bytes) — runs JPEG decode + LCD/canvas draw */
#define VIDEO_RENDER_TASK_STACK     (8 * 1024)

/** Render task priority (slightly lower than AVI demuxer for backpressure) */
#define VIDEO_RENDER_TASK_PRIORITY  5

/** Render task pinned core (core 0 = separate from AVI demuxer on core 1) */
#define VIDEO_RENDER_TASK_CORE      0

/* ------------------------------------------------------------------ */
/*  State machine                                                     */
/* ------------------------------------------------------------------ */

enum class VideoPlayerState {
    Idle = 0,       ///< No file loaded, ready for commands
    Loading,        ///< Opening file and parsing AVI header
    Playing,        ///< Active playback (video + audio)
    Paused,         ///< Playback paused, can resume
    Stopping,       ///< Tearing down resources
    Error           ///< Unrecoverable error (call Stop() to reset)
};

/* ------------------------------------------------------------------ */
/*  Video file info                                                   */
/* ------------------------------------------------------------------ */

struct VideoFileInfo {
    std::string name;           ///< File name (e.g. "demo.avi")
    std::string path;           ///< Full path (e.g. "/sdcard/videos/demo.avi")
    uint32_t    file_size = 0;  ///< File size in bytes
};

/* ------------------------------------------------------------------ */
/*  Playback statistics                                               */
/* ------------------------------------------------------------------ */

struct VideoPlaybackStats {
    uint32_t frames_decoded  = 0;   ///< Total video frames decoded
    uint32_t frames_dropped  = 0;   ///< Frames skipped (decode too slow)
    uint32_t audio_underruns = 0;   ///< Audio buffer underrun count
    float    avg_decode_ms   = 0;   ///< Average JPEG decode time (ms)
    float    avg_draw_ms     = 0;   ///< Average LCD draw time (ms)
    uint16_t video_width     = 0;   ///< Current video width
    uint16_t video_height    = 0;   ///< Current video height
    uint16_t video_fps       = 0;   ///< Video frame rate
    uint32_t audio_rate      = 0;   ///< Audio sample rate (Hz)
    uint8_t  audio_channels  = 0;   ///< Audio channel count
    uint8_t  audio_bits      = 0;   ///< Audio bits per sample
};

/* ------------------------------------------------------------------ */
/*  Rendering mode                                                    */
/* ------------------------------------------------------------------ */

/**
 * @brief Video rendering strategy.
 *
 * DirectLcd:  esp_lcd_panel_draw_bitmap() — bypasses LVGL, maximum FPS.
 * LvglCanvas: Renders through an LVGL canvas widget — goes through
 *             LVGL's refresh pipeline.  Useful for testing LVGL overhead
 *             and for overlaying UI elements on top of video.
 */
enum class VideoRenderMode {
    DirectLcd,    ///< Direct LCD panel write (bypasses LVGL)
    LvglCanvas    ///< LVGL canvas rendering pipeline
};

/* ------------------------------------------------------------------ */
/*  Callback typedefs for event notification                          */
/* ------------------------------------------------------------------ */

/** Called when playback state changes (with both old and new state) */
using VideoStateCallback = std::function<void(VideoPlayerState old_state, VideoPlayerState new_state)>;

/** Called when a video file finishes playing (natural end or error) */
using VideoEndCallback = std::function<bool(const std::string& file_path)>;

using VideoClockSyncCallback = std::function<void(uint32_t rate, uint8_t bits, uint8_t channels)>;

/** Called when FPS stats are available (periodic report) */
using VideoFpsCallback = std::function<void(float fps, float avg_decode_ms, float avg_draw_ms)>;

/** Called on each decoded video frame (for custom post-processing) */
using VideoFrameCallback = std::function<void(uint16_t* rgb565, uint16_t w, uint16_t h)>;

/** Called on each PCM audio chunk (for custom audio processing) */
using VideoAudioCallback = std::function<void(int16_t* pcm, size_t samples, int channels)>;

/* ------------------------------------------------------------------ */
/*  VideoPlayer class                                                 */
/* ------------------------------------------------------------------ */

class VideoPlayer {
public:
    /* ---- Singleton access ---- */
    static VideoPlayer& GetInstance();

    /* ---- Lifecycle ---- */

    /**
     * @brief Initialize the video player subsystem.
     *
     * Must be called once after Board peripherals are initialized.
     * Allocates PSRAM frame buffers, creates JPEG decoder, and prepares
     * the AVI player engine.
     *
     * @param lcd_panel   LCD panel handle for direct drawing
     * @param lcd_width   Display width in pixels
     * @param lcd_height  Display height in pixels
     * @param codec       Pointer to the board's AudioCodec (for PCM output)
     * @param sd_card     Pointer to the mounted SdCard instance
     * @return true on success
     */
    bool Initialize(esp_lcd_panel_handle_t lcd_panel,
                    uint16_t lcd_width, uint16_t lcd_height,
                    AudioCodec* codec, SdCard* sd_card,
                    Display* display = nullptr, VideoRenderMode mode = VideoRenderMode::DirectLcd);

    /**
     * @brief Release all resources. Safe to call multiple times.
     */
    void Deinitialize();

    /* ---- Playback control ---- */

    /**
     * @brief Play an AVI file by full path.
     * @param file_path  Absolute path, e.g. "/sdcard/videos/demo.avi"
     * @return true if playback started successfully
     */
    bool Play(const std::string& file_path);

    /**
     * @brief Play a file by name from the current video directory.
     * @param file_name  File name only, e.g. "demo.avi"
     * @return true if file found and playback started
     */
    bool PlayFile(const std::string& file_name);

    /**
     * @brief Stop current playback. Blocks until fully stopped.
     */
    void Stop();

    /**
     * @brief Pause current playback.
     * @note  Video freezes on last frame; audio silenced.
     */
    void Pause();

    /**
     * @brief Resume from paused state.
     */
    void Resume();

    /**
     * @brief Play the next file in the playlist.
     * @return true if there is a next file
     */
    bool Next();

    /**
     * @brief Play the previous file in the playlist.
     * @return true if there is a previous file
     */
    bool Prev();

    /* ---- Directory / Playlist ---- */

    /**
     * @brief Set the video directory (relative to SD mount point).
     * @param dir  e.g. "videos" or "media/clips"
     */
    void SetDirectory(const std::string& dir);

    /**
     * @brief Scan current directory for AVI files and build playlist.
     * @return Number of AVI files found
     */
    size_t ScanDirectory();

    /**
     * @brief Get the current playlist.
     */
    const std::vector<VideoFileInfo>& GetPlaylist() const { return playlist_; }

    /**
     * @brief Get current playlist index (-1 if none).
     */
    int GetCurrentIndex() const { return current_index_; }

    /* ---- State queries ---- */

    VideoPlayerState GetState() const { return state_.load(); }
    bool IsPlaying() const { return state_.load() == VideoPlayerState::Playing; }
    bool IsPaused() const { return state_.load() == VideoPlayerState::Paused; }
    bool IsInitialized() const { return initialized_.load(); }

    /** Get current playback statistics */
    VideoPlaybackStats GetStats() const;

    /* ---- Callbacks ---- */

    void SetStateCallback(VideoStateCallback cb) { state_callback_ = std::move(cb); }
    void SetEndCallback(VideoEndCallback cb) { end_callback_ = std::move(cb); }
    void SetClockSyncCallback(VideoClockSyncCallback cb) { clock_sync_callback_ = std::move(cb); }
    void SetFpsCallback(VideoFpsCallback cb) { fps_callback_ = std::move(cb); }
    void SetFrameCallback(VideoFrameCallback cb) { frame_callback_ = std::move(cb); }
    void SetAudioCallback(VideoAudioCallback cb) { audio_callback_ = std::move(cb); }

    /* ---- Render mode ---- */

    /** Set rendering mode (DirectLcd or LvglCanvas). Call before Play(). */
    void SetRenderMode(VideoRenderMode mode);
    VideoRenderMode GetRenderMode() const { return render_mode_; }

    /* ---- Volume ---- */

    /** Set video audio volume (0.0 = mute, 1.0 = normal, >1.0 = amplify) */
    void SetVolume(float factor) { volume_factor_ = factor; }
    float GetVolume() const { return volume_factor_; }

protected:
    /* ---- Virtual hooks for subclass customization ---- */

    /**
     * @brief Called after each video frame is decoded to RGB565.
     * Override for custom post-processing (e.g. overlay, scaling).
     *
     * @param rgb565_data  Pointer to decoded RGB565 pixel data
     * @param width        Frame width
     * @param height       Frame height
     */
    virtual void OnVideoFrameReady(uint16_t* rgb565_data,
                                   uint16_t width, uint16_t height) {}

    /**
     * @brief Called for each PCM audio chunk before output.
     * Override for custom audio processing (e.g. visualization).
     *
     * @param pcm_data  Pointer to PCM samples (int16_t interleaved)
     * @param samples   Number of samples
     * @param channels  Channel count
     */
    virtual void OnAudioFrameReady(int16_t* pcm_data,
                                   size_t samples, int channels) {}

    /**
     * @brief Called when playback state transitions.
     */
    virtual void OnPlaybackStateChanged(VideoPlayerState old_state,
                                        VideoPlayerState new_state) {}

private:
    /* ---- Singleton ---- */
    VideoPlayer();
    ~VideoPlayer();
    VideoPlayer(const VideoPlayer&) = delete;
    VideoPlayer& operator=(const VideoPlayer&) = delete;

    /* ---- AVI player callbacks (static → dispatch to instance) ---- */
    static void AviVideoCallback(frame_data_t* data, void* arg);
    static void AviAudioCallback(frame_data_t* data, void* arg);
    static void AviAudioClockCallback(uint32_t rate, uint32_t bits, uint32_t ch, void* arg);
    static void AviPlayEndCallback(void* arg);

    /* ---- Internal processing ---- */
    void HandleVideoFrame(frame_data_t* data);
    void HandleAudioFrame(frame_data_t* data);
    void HandleAudioClock(uint32_t rate, uint32_t bits, uint32_t ch);
    void HandlePlayEnd();

    /**
     * @brief Initialize the JPEG decoder for the specified render mode.
     * @param mode  Render mode determines pixel format (BE for LCD, LE for LVGL)
     * @return true on success
     */
    bool InitJpegDecoder(VideoRenderMode mode);

    /** Clean up JPEG decoder resources */
    void DeinitJpegDecoder();

    /** Decode MJPEG data to RGB565 into the back-buffer */
    bool DecodeMjpegFrame(const uint8_t* jpeg_data, size_t jpeg_size,
                          uint16_t width, uint16_t height);

    /** Log FPS statistics periodically */
    void LogFpsStats();

    /** Draw the current back-buffer to LCD panel */
    void DrawFrameToLcd(uint16_t width, uint16_t height);

    /** Draw the decoded frame via LVGL canvas (for render mode comparison) */
    void DrawFrameToCanvas(uint16_t width, uint16_t height);

    /** Create / destroy the LVGL canvas used in LvglCanvas render mode */
    void CreateVideoCanvas();
    void DestroyVideoCanvas();

    /* ---- Render task (decouples decode+draw from AVI demuxer) ---- */
    static void RenderTaskEntry(void* arg);
    void RenderTaskLoop();

    /** Output PCM audio through AudioCodec */
    void OutputAudioPcm(const uint8_t* pcm_data, size_t data_bytes,
                        uint8_t channels, uint8_t bits_per_sample);

    /** Transition state with notification */
    void SetState(VideoPlayerState new_state);

    /** Build full path from mount point + directory + filename */
    std::string BuildFullPath(const std::string& filename) const;

    /* ---- Hardware handles ---- */
    esp_lcd_panel_handle_t lcd_panel_  = nullptr;
    uint16_t               lcd_width_  = 0;
    uint16_t               lcd_height_ = 0;
    AudioCodec*            audio_codec_ = nullptr;
    SdCard*                sd_card_    = nullptr;

    /* ---- AVI player ---- */
    avi_player_handle_t    avi_handle_ = nullptr;

    /* ---- JPEG decoder ---- */
    jpeg_dec_handle_t      jpeg_dec_   = nullptr;
    jpeg_dec_io_t*         jpeg_io_    = nullptr;
    jpeg_dec_header_info_t* jpeg_hdr_  = nullptr;

    /* ---- Double-buffered RGB565 frame data (PSRAM) ---- */
    uint16_t* frame_buf_[2]    = {nullptr, nullptr};
    int       back_buf_index_  = 0;   ///< Index of buffer currently being decoded into
    size_t    frame_buf_size_  = 0;   ///< Size of each buffer in bytes

    /* ---- Render task (independent from AVI demuxer task) ---- */
    TaskHandle_t      render_task_handle_  = nullptr;
#if VIDEO_PLAYER_STATIC_TASK_CREATION == 1
    StackType_t*      render_task_stack_   = nullptr;
    StaticTask_t*     render_task_buffer_  = nullptr;
#endif
    SemaphoreHandle_t render_sem_          = nullptr;  ///< Signals new frame ready
    SemaphoreHandle_t mjpeg_mutex_         = nullptr;  ///< Protects pending MJPEG buffer
    uint8_t*          pending_mjpeg_buf_   = nullptr;  ///< AVI cb writes MJPEG here
    uint8_t*          decode_mjpeg_buf_    = nullptr;  ///< Render task's private copy
    size_t            pending_mjpeg_size_  = 0;
    uint16_t          pending_frame_w_     = 0;
    uint16_t          pending_frame_h_     = 0;
    std::atomic<bool> render_task_running_{false};
    std::atomic<bool> render_exit_{false};             ///< Signals render task to exit

    /* ---- LVGL canvas rendering ---- */
    Display*          display_       = nullptr;  ///< For LVGL lock (canvas mode)
    void*             video_canvas_  = nullptr;  ///< lv_obj_t* (avoids lvgl.h dep)
    uint16_t*         canvas_buf_    = nullptr;  ///< PSRAM buffer for LVGL canvas
    VideoRenderMode   render_mode_   = VideoRenderMode::DirectLcd;

    /* ---- State ---- */
    std::atomic<VideoPlayerState> state_{VideoPlayerState::Idle};
    std::atomic<bool>             initialized_{false};
    std::atomic<bool>             stop_requested_{false};
    std::atomic<bool>             paused_{false};
    mutable std::mutex            mutex_;

    /* ---- Playback stats ---- */
    mutable std::mutex            stats_mutex_;
    VideoPlaybackStats            stats_{};
    int64_t                       last_decode_start_us_ = 0;

    /* ---- Playlist ---- */
    std::string                   video_directory_;
    std::string                   mount_point_;
    std::vector<VideoFileInfo>    playlist_;
    int                           current_index_ = -1;
    std::string                   current_file_path_;

    /* ---- Volume ---- */
    float                         volume_factor_ = 1.0f;

    /* ---- Callbacks ---- */
    VideoStateCallback            state_callback_;
    VideoEndCallback              end_callback_;
    VideoFpsCallback              fps_callback_;
    VideoFrameCallback            frame_callback_;
    VideoAudioCallback            audio_callback_;
    VideoClockSyncCallback        clock_sync_callback_;

    /* ---- FPS tracking ---- */
    int64_t                       fps_last_log_time_us_ = 0;
    uint32_t                      fps_frame_count_ = 0;
};

#endif // VIDEO_PLAYER_H
