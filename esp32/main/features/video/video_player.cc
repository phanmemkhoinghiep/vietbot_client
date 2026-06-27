/**
 * @file video_player.cc
 * @brief AVI Video Player implementation for ESP32-S3.
 *
 * Playback pipeline:
 *   SD card AVI file
 *       → avi_player (demux: MJPEG video + PCM audio)
 *           → video_cb: JPEG decode → RGB565 double-buffer → LCD panel draw
 *           → audio_cb: PCM → volume scale → AudioCodec I2S output
 *
 * All large allocations use PSRAM (heap_caps_malloc with MALLOC_CAP_SPIRAM).
 * Video rendering bypasses LVGL and writes directly to the LCD panel via
 * esp_lcd_panel_draw_bitmap() for maximum throughput with zero tearing.
 */

#include "video_player.h"

#include <cstring>
#include <cmath>
#include <algorithm>
#include <dirent.h>
#include <sys/stat.h>

#include <esp_log.h>
#include <esp_timer.h>
#include <esp_heap_caps.h>
#include <esp_lcd_panel_ops.h>

#include "audio_codec.h"
#include "sd_card.h"
#include "board.h"
#include "display.h"

static const char* TAG = "VideoPlayer";

/* ================================================================== */
/*  Singleton                                                         */
/* ================================================================== */

VideoPlayer& VideoPlayer::GetInstance() {
    static VideoPlayer instance;
    return instance;
}

VideoPlayer::VideoPlayer()
    : video_directory_(VIDEO_DEFAULT_DIRECTORY) {
}

VideoPlayer::~VideoPlayer() {
    Deinitialize();
}

/* ================================================================== */
/*  Lifecycle                                                         */
/* ================================================================== */

bool VideoPlayer::Initialize(esp_lcd_panel_handle_t lcd_panel,
                             uint16_t lcd_width, uint16_t lcd_height,
                             AudioCodec* codec, SdCard* sd_card,
                             Display* display, VideoRenderMode mode) {
    if (initialized_.load()) {
        ESP_LOGW(TAG, "Already initialized");
        return true;
    }

    if (!lcd_panel || !codec || !sd_card) {
        ESP_LOGE(TAG, "Invalid parameters: panel=%p codec=%p sd=%p",
                 lcd_panel, codec, sd_card);
        return false;
    }

    lcd_panel_  = lcd_panel;
    lcd_width_  = lcd_width;
    lcd_height_ = lcd_height;
    audio_codec_ = codec;
    sd_card_    = sd_card;
    render_mode_ = mode;
    /* Store mount point for path construction */
    mount_point_ = sd_card_->GetMountPoint();
    display_ = display;

    /* ---- Allocate double-buffered RGB565 frame memory in PSRAM ---- */
    frame_buf_size_ = VIDEO_MAX_WIDTH * VIDEO_MAX_HEIGHT * sizeof(uint16_t);

    for (int i = 0; i < 2; i++) {
        frame_buf_[i] = static_cast<uint16_t*>(
            heap_caps_aligned_alloc(16, frame_buf_size_,
                                    MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
        if (!frame_buf_[i]) {
            ESP_LOGE(TAG, "Failed to allocate frame buffer %d (%zu bytes)", i, frame_buf_size_);
            Deinitialize();
            return false;
        }
        memset(frame_buf_[i], 0, frame_buf_size_);
    }
    ESP_LOGI(TAG, "Frame buffers allocated: 2 x %zu bytes in PSRAM", frame_buf_size_);

    /* ---- Allocate MJPEG pending buffers for render task handoff ---- */
    pending_mjpeg_buf_ = static_cast<uint8_t*>(
        heap_caps_malloc(VIDEO_AVI_BUFFER_SIZE, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    decode_mjpeg_buf_ = static_cast<uint8_t*>(
        heap_caps_malloc(VIDEO_AVI_BUFFER_SIZE, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    if (!pending_mjpeg_buf_ || !decode_mjpeg_buf_) {
        ESP_LOGE(TAG, "Failed to allocate MJPEG buffers (%d bytes each)", VIDEO_AVI_BUFFER_SIZE);
        Deinitialize();
        return false;
    }
    ESP_LOGI(TAG, "MJPEG buffers allocated: 2 x %d bytes in PSRAM", VIDEO_AVI_BUFFER_SIZE);

    /* ---- Initialize JPEG decoder (software, MJPEG → RGB565) ---- */
    if (!InitJpegDecoder(render_mode_)) {
        ESP_LOGE(TAG, "Failed to initialize JPEG decoder");
        Deinitialize();
        return false;
    }

    /* ---- Create synchronization primitives for render task ---- */
    render_sem_ = xSemaphoreCreateBinary();
    mjpeg_mutex_ = xSemaphoreCreateMutex();
    if (!render_sem_ || !mjpeg_mutex_) {
        ESP_LOGE(TAG, "Failed to create render sync primitives");
        Deinitialize();
        return false;
    }

    /* ---- Start the independent render task ---- */
    render_exit_.store(false);

#if VIDEO_PLAYER_STATIC_TASK_CREATION == 1
    if (render_task_stack_ == nullptr) {
        render_task_stack_ = (StackType_t*)heap_caps_malloc(VIDEO_RENDER_TASK_STACK, MALLOC_CAP_SPIRAM);
        assert(render_task_stack_ != nullptr);
    }
    if (render_task_buffer_ == nullptr) {
        render_task_buffer_ = (StaticTask_t*)heap_caps_malloc(sizeof(StaticTask_t), MALLOC_CAP_INTERNAL);
        assert(render_task_buffer_ != nullptr);
    }
    render_task_handle_ = xTaskCreateStatic(RenderTaskEntry, "vid_render",
                                            VIDEO_RENDER_TASK_STACK, this,
                                            VIDEO_RENDER_TASK_PRIORITY,
                                            render_task_stack_, render_task_buffer_);
    if (render_task_handle_ == nullptr) {
        ESP_LOGE(TAG, "Failed to create render task");
        Deinitialize();
        return false;
    }
#else
    BaseType_t ret = xTaskCreatePinnedToCore(
        RenderTaskEntry, "vid_render",
        VIDEO_RENDER_TASK_STACK, this,
        VIDEO_RENDER_TASK_PRIORITY,
        &render_task_handle_,
        VIDEO_RENDER_TASK_CORE);
    if (ret != pdPASS) {
        ESP_LOGE(TAG, "Failed to create render task");
        Deinitialize();
        return false;
    }
#endif
    ESP_LOGI(TAG, "Render task created: core=%d prio=%d stack=%d",
             VIDEO_RENDER_TASK_CORE, VIDEO_RENDER_TASK_PRIORITY, VIDEO_RENDER_TASK_STACK);

    initialized_.store(true);
    SetState(VideoPlayerState::Idle);
    ESP_LOGI(TAG, "Initialized: LCD %dx%d, max video %dx%d",
             lcd_width_, lcd_height_, VIDEO_MAX_WIDTH, VIDEO_MAX_HEIGHT);

    return true;
}

void VideoPlayer::Deinitialize() {
    Stop();

    /* Stop render task */
    if (render_task_handle_) {
        render_exit_.store(true);
        if (render_sem_) {
            xSemaphoreGive(render_sem_);  /* Wake task so it can exit */
        }
        /* Wait for task to finish (max 2 seconds) */
        int timeout = 100;
        while (render_task_running_.load() && --timeout > 0) {
            vTaskDelay(pdMS_TO_TICKS(20));
        }
        if (timeout <= 0) {
            ESP_LOGW(TAG, "Render task did not exit in time, force deleting");
            vTaskDelete(render_task_handle_);
        }
        render_task_handle_ = nullptr;
#if VIDEO_PLAYER_STATIC_TASK_CREATION == 1
        if (render_task_buffer_) {
            heap_caps_free(render_task_buffer_);
            render_task_buffer_ = nullptr;
        }
        if (render_task_stack_) {
            heap_caps_free(render_task_stack_);
            render_task_stack_ = nullptr;
        }
#endif

        render_exit_.store(false);
    }

    /* Destroy LVGL canvas */
    DestroyVideoCanvas();

    /* Cleanup JPEG decoder */
    DeinitJpegDecoder();
    if (render_sem_) {
        vSemaphoreDelete(render_sem_);
        render_sem_ = nullptr;
    }
    if (mjpeg_mutex_) {
        vSemaphoreDelete(mjpeg_mutex_);
        mjpeg_mutex_ = nullptr;
    }

    /* Free MJPEG buffers */
    if (pending_mjpeg_buf_) {
        heap_caps_free(pending_mjpeg_buf_);
        pending_mjpeg_buf_ = nullptr;
    }
    if (decode_mjpeg_buf_) {
        heap_caps_free(decode_mjpeg_buf_);
        decode_mjpeg_buf_ = nullptr;
    }

    /* Free frame buffers */
    for (int i = 0; i < 2; i++) {
        if (frame_buf_[i]) {
            heap_caps_free(frame_buf_[i]);
            frame_buf_[i] = nullptr;
        }
    }

    initialized_.store(false);
    SetState(VideoPlayerState::Idle);
    ESP_LOGI(TAG, "Deinitialized");
}

/* ================================================================== */
/*  Playback control                                                  */
/* ================================================================== */

bool VideoPlayer::Play(const std::string& file_path) {
    if (!initialized_.load()) {
        ESP_LOGE(TAG, "Not initialized");
        return false;
    }

    /* Stop any current playback first */
    if (state_.load() != VideoPlayerState::Idle) {
        Stop();
    }

    SetState(VideoPlayerState::Loading);
    current_file_path_ = file_path;
    stop_requested_.store(false);
    paused_.store(false);

    /* Reset statistics */
    {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        stats_ = VideoPlaybackStats{};
    }

    /* Reset render state for new file */
    pending_mjpeg_size_ = 0;
    back_buf_index_ = 0;
    if (render_sem_) {
        xSemaphoreTake(render_sem_, 0);  /* Drain stale signal */
    }

    /* ---- Initialize AVI player ---- */
    avi_player_config_t avi_cfg = {};
    avi_cfg.buffer_size         = VIDEO_AVI_BUFFER_SIZE;
    avi_cfg.video_cb            = AviVideoCallback;
    avi_cfg.audio_cb            = AviAudioCallback;
    avi_cfg.audio_set_clock_cb  = AviAudioClockCallback;
    avi_cfg.avi_play_end_cb     = AviPlayEndCallback;
    avi_cfg.priority            = VIDEO_AVI_TASK_PRIORITY;
    avi_cfg.coreID              = VIDEO_AVI_TASK_CORE;
    avi_cfg.user_data           = this;
    avi_cfg.stack_size          = VIDEO_AVI_TASK_STACK;
    avi_cfg.stack_in_psram      = true;  /* Stack in PSRAM (reading from SD, not flash) */

    esp_err_t err = avi_player_init(avi_cfg, &avi_handle_);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "avi_player_init failed: %s", esp_err_to_name(err));
        SetState(VideoPlayerState::Error);
        return false;
    }

    /* ---- Start file playback ---- */
    err = avi_player_play_from_file(avi_handle_, file_path.c_str());
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "avi_player_play_from_file failed: %s  path=%s",
                 esp_err_to_name(err), file_path.c_str());
        avi_player_deinit(avi_handle_);
        avi_handle_ = nullptr;
        SetState(VideoPlayerState::Error);
        return false;
    }

    SetState(VideoPlayerState::Playing);
    ESP_LOGI(TAG, "Playing: %s", current_file_path_.c_str());
    return true;
}

bool VideoPlayer::PlayFile(const std::string& file_name) {
    std::string full_path = BuildFullPath(file_name);
    return Play(full_path);
}

void VideoPlayer::Stop() {
    if (state_.load() == VideoPlayerState::Idle) {
        return;
    }

    stop_requested_.store(true);
    SetState(VideoPlayerState::Stopping);

    if (avi_handle_) {
        /* Stop AVI playback -- may fail if already ended, that's OK */
        esp_err_t err = avi_player_play_stop(avi_handle_);
        if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
            ESP_LOGW(TAG, "avi_player_play_stop: %s", esp_err_to_name(err));
        }

        /* Wait a bit for the player task to process the stop event */
        vTaskDelay(pdMS_TO_TICKS(100));

        /* Deinitialize AVI player (waits for task deletion) */
        avi_player_deinit(avi_handle_);
        avi_handle_ = nullptr;
    }

    /* Restore original audio sample rate */
    if (audio_codec_) {
        audio_codec_->SetOutputSampleRate(-1);
    }

    stop_requested_.store(false);
    paused_.store(false);
    current_file_path_.clear();

    /* Reset FPS tracking */
    fps_last_log_time_us_ = 0;
    fps_frame_count_ = 0;

    SetState(VideoPlayerState::Idle);

    ESP_LOGI(TAG, "Stopped");
}

void VideoPlayer::Pause() {
    if (state_.load() != VideoPlayerState::Playing) {
        return;
    }
    paused_.store(true);
    SetState(VideoPlayerState::Paused);
    ESP_LOGI(TAG, "Paused");
}

void VideoPlayer::Resume() {
    if (state_.load() != VideoPlayerState::Paused) {
        return;
    }
    paused_.store(false);
    SetState(VideoPlayerState::Playing);
    ESP_LOGI(TAG, "Resumed");
}

bool VideoPlayer::Next() {
    if (playlist_.empty()) return false;

    int next = current_index_ + 1;
    if (next >= static_cast<int>(playlist_.size())) {
        next = 0;  /* Wrap around */
    }
    current_index_ = next;
    return Play(playlist_[current_index_].path);
}

bool VideoPlayer::Prev() {
    if (playlist_.empty()) return false;

    int prev = current_index_ - 1;
    if (prev < 0) {
        prev = static_cast<int>(playlist_.size()) - 1;
    }
    current_index_ = prev;
    return Play(playlist_[current_index_].path);
}

/* ================================================================== */
/*  Directory / Playlist                                              */
/* ================================================================== */

void VideoPlayer::SetDirectory(const std::string& dir) {
    video_directory_ = dir;
    playlist_.clear();
    current_index_ = -1;
}

size_t VideoPlayer::ScanDirectory() {
    playlist_.clear();
    current_index_ = -1;

    std::string dir_path = std::string(mount_point_) + "/" + video_directory_;

    DIR* dir = opendir(dir_path.c_str());
    if (!dir) {
        ESP_LOGW(TAG, "Cannot open directory: %s", dir_path.c_str());
        return 0;
    }

    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr && playlist_.size() < VIDEO_MAX_FILES) {
        if (entry->d_type != DT_REG) continue;

        std::string name(entry->d_name);
        /* Check for .avi extension (case-insensitive) */
        if (name.size() < 5) continue;
        std::string ext = name.substr(name.size() - 4);
        for (auto& c : ext) c = static_cast<char>(tolower(static_cast<unsigned char>(c)));
        if (ext != ".avi") continue;

        VideoFileInfo info;
        info.name = name;
        info.path = dir_path + "/" + name;

        /* Get file size */
        struct stat st;
        if (stat(info.path.c_str(), &st) == 0) {
            info.file_size = static_cast<uint32_t>(st.st_size);
        }

        playlist_.push_back(std::move(info));
    }
    closedir(dir);

    /* Sort alphabetically */
    std::sort(playlist_.begin(), playlist_.end(),
              [](const VideoFileInfo& a, const VideoFileInfo& b) {
                  return a.name < b.name;
              });

    ESP_LOGI(TAG, "Scanned %zu AVI files in %s", playlist_.size(), dir_path.c_str());
    return playlist_.size();
}

/* ================================================================== */
/*  State queries                                                     */
/* ================================================================== */

VideoPlaybackStats VideoPlayer::GetStats() const {
    std::lock_guard<std::mutex> lock(stats_mutex_);
    return stats_;
}

/* ================================================================== */
/*  AVI player callbacks (static dispatchers)                         */
/* ================================================================== */

void VideoPlayer::AviVideoCallback(frame_data_t* data, void* arg) {
    auto* self = static_cast<VideoPlayer*>(arg);
    if (self && !self->stop_requested_.load()) {
        self->HandleVideoFrame(data);
    }
}

void VideoPlayer::AviAudioCallback(frame_data_t* data, void* arg) {
    auto* self = static_cast<VideoPlayer*>(arg);
    if (self && !self->stop_requested_.load()) {
        self->HandleAudioFrame(data);
    }
}

void VideoPlayer::AviAudioClockCallback(uint32_t rate, uint32_t bits,
                                         uint32_t ch, void* arg) {
    auto* self = static_cast<VideoPlayer*>(arg);
    if (self) {
        self->HandleAudioClock(rate, bits, ch);
    }
}

void VideoPlayer::AviPlayEndCallback(void* arg) {
    auto* self = static_cast<VideoPlayer*>(arg);
    if (self) {
        self->HandlePlayEnd();
    }
}

/* ================================================================== */
/*  Internal frame processing                                         */
/* ================================================================== */

void VideoPlayer::HandleVideoFrame(frame_data_t* data) {
    if (paused_.load()) return;
    if (!data || data->type != FRAME_TYPE_VIDEO) return;

    if (data->video_info.frame_format != FORMAT_MJEPG) {
        ESP_LOGW(TAG, "Unsupported video format: %d", data->video_info.frame_format);
        return;
    }

    /*
     * Copy raw MJPEG data to the pending buffer and signal the render task.
     * The actual JPEG decode + LCD/canvas draw happens in the independent
     * render task, so the AVI demuxer is NOT blocked by decode or draw latency.
     */
    size_t copy_size = data->data_bytes;
    if (copy_size > VIDEO_AVI_BUFFER_SIZE) {
        ESP_LOGW(TAG, "MJPEG frame too large: %zu > %d", copy_size, VIDEO_AVI_BUFFER_SIZE);
        copy_size = VIDEO_AVI_BUFFER_SIZE;
    }

    xSemaphoreTake(mjpeg_mutex_, portMAX_DELAY);
    memcpy(pending_mjpeg_buf_, data->data, copy_size);
    pending_mjpeg_size_ = copy_size;
    pending_frame_w_ = data->video_info.width;
    pending_frame_h_ = data->video_info.height;
    xSemaphoreGive(mjpeg_mutex_);

    /* Wake the render task */
    xSemaphoreGive(render_sem_);
}

void VideoPlayer::HandleAudioFrame(frame_data_t* data) {
    if (paused_.load()) return;
    if (!data || data->type != FRAME_TYPE_AUDIO) return;

    /* Hook for subclass customization (e.g. FFT visualization) */
    size_t samples = data->data_bytes / (data->audio_info.bits_per_sample / 8);
    OnAudioFrameReady(reinterpret_cast<int16_t*>(data->data),
                      samples, data->audio_info.channel);

    /* Notify external audio callback */
    if (audio_callback_) {
        audio_callback_(reinterpret_cast<int16_t*>(data->data),
                        samples, data->audio_info.channel);
    }

    /* Output PCM to speaker */
    OutputAudioPcm(data->data, data->data_bytes,
                   data->audio_info.channel, data->audio_info.bits_per_sample);
}

void VideoPlayer::HandleAudioClock(uint32_t rate, uint32_t bits, uint32_t ch) {
    ESP_LOGI(TAG, "Audio clock: rate=%lu bits=%lu ch=%lu", rate, bits, ch);

    if (audio_codec_) {
        /* Notify external clock sync callback for user application to enter idle mode */
        if (clock_sync_callback_) {
            clock_sync_callback_(rate, static_cast<uint8_t>(bits), static_cast<uint8_t>(ch));
        }

        // Should be called after the clock sync callback to ensure audio output is enabled
        // and set the sample rate for synchronization.
        if (!audio_codec_->output_enabled()) {
            ESP_LOGW(TAG, "%s Enabling audio output for playback", __func__);
            audio_codec_->EnableOutput(true);
        }
        audio_codec_->SetOutputSampleRate(static_cast<int>(rate));
    }

    /* Store in stats */
    {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        stats_.audio_rate     = rate;
        stats_.audio_bits     = static_cast<uint8_t>(bits);
        stats_.audio_channels = static_cast<uint8_t>(ch);
    }
}

void VideoPlayer::HandlePlayEnd() {
    ESP_LOGI(TAG, "Playback ended: %s", current_file_path_.c_str());

    std::string ended_path = current_file_path_;

    /* Clean up AVI handle -- deinit will be done in Stop() or next Play() */
    if (avi_handle_) {
        avi_player_deinit(avi_handle_);
        avi_handle_ = nullptr;
    }

    /* Restore audio sample rate */
    if (audio_codec_) {
        audio_codec_->SetOutputSampleRate(-1);
    }

    current_file_path_.clear();

    /* Log final stats */
    {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        ESP_LOGI(TAG, "Stats: decoded=%lu dropped=%lu avg_decode=%.1fms",
                 stats_.frames_decoded, stats_.frames_dropped, stats_.avg_decode_ms);
    }

    /* Notify listener */
    if (end_callback_) {
        if (!end_callback_(ended_path)) {
            SetState(VideoPlayerState::Idle);
        }
    } else {
        SetState(VideoPlayerState::Idle);
    }
}

/* ================================================================== */
/*  JPEG decoder lifecycle (per-mode initialization)                  */
/* ================================================================== */

bool VideoPlayer::InitJpegDecoder(VideoRenderMode mode) {
    /* Clean up existing decoder first */
    DeinitJpegDecoder();

    jpeg_dec_config_t jpeg_cfg = {
        .output_type  = JPEG_PIXEL_FORMAT_RGB565_LE,
        .scale        = {.width = 0, .height = 0},
        .clipper      = {.width = 0, .height = 0},
        .rotate       = JPEG_ROTATE_0D,
        .block_enable = false,
    };

    /* Select pixel format based on render mode:
     * DirectLcd:  RGB565 big-endian (native for esp_lcd panel write)
     * LvglCanvas: RGB565 little-endian (native for lv_color_t / LVGL) */
    if (mode == VideoRenderMode::DirectLcd) {
        jpeg_cfg.output_type = JPEG_PIXEL_FORMAT_RGB565_BE;
        ESP_LOGI(TAG, "JPEG decoder: RGB565_BE (DirectLcd mode)");
    } else {
        jpeg_cfg.output_type = JPEG_PIXEL_FORMAT_RGB565_LE;
        ESP_LOGI(TAG, "JPEG decoder: RGB565_LE (LvglCanvas mode)");
    }

    jpeg_error_t jerr = jpeg_dec_open(&jpeg_cfg, &jpeg_dec_);
    if (jerr != JPEG_ERR_OK) {
        ESP_LOGE(TAG, "Failed to open JPEG decoder: %d", jerr);
        return false;
    }

    /* Allocate JPEG I/O and header structs in PSRAM */
    jpeg_io_ = static_cast<jpeg_dec_io_t*>(
        heap_caps_calloc(1, sizeof(jpeg_dec_io_t), MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    jpeg_hdr_ = static_cast<jpeg_dec_header_info_t*>(
        heap_caps_aligned_alloc(16, sizeof(jpeg_dec_header_info_t),
                                MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));

    if (!jpeg_io_ || !jpeg_hdr_) {
        ESP_LOGE(TAG, "Failed to allocate JPEG I/O structs");
        DeinitJpegDecoder();
        return false;
    }

    ESP_LOGI(TAG, "JPEG decoder initialized for %s mode",
             mode == VideoRenderMode::DirectLcd ? "DirectLcd" : "LvglCanvas");
    return true;
}

void VideoPlayer::DeinitJpegDecoder() {
    if (jpeg_dec_) {
        jpeg_dec_close(jpeg_dec_);
        jpeg_dec_ = nullptr;
    }
    if (jpeg_io_) {
        heap_caps_free(jpeg_io_);
        jpeg_io_ = nullptr;
    }
    if (jpeg_hdr_) {
        heap_caps_free(jpeg_hdr_);
        jpeg_hdr_ = nullptr;
    }
}

/* ================================================================== */
/*  FPS logging                                                       */
/* ================================================================== */

void VideoPlayer::LogFpsStats() {
#if VIDEO_FPS_LOG_INTERVAL_SEC > 0
    int64_t now_us = esp_timer_get_time();

    /* Initialize on first call */
    if (fps_last_log_time_us_ == 0) {
        fps_last_log_time_us_ = now_us;
        fps_frame_count_ = 0;
        return;
    }

    fps_frame_count_++;
    float elapsed_sec = static_cast<float>(now_us - fps_last_log_time_us_) / 1000000.0f;

    if (elapsed_sec >= VIDEO_FPS_LOG_INTERVAL_SEC) {
        float fps = static_cast<float>(fps_frame_count_) / elapsed_sec;

        std::lock_guard<std::mutex> lock(stats_mutex_);
        ESP_LOGI(TAG, "[FPS] %.1f fps | decode=%.1fms draw=%.1fms | %lux%lu | decoded=%lu dropped=%lu",
                 fps, stats_.avg_decode_ms, stats_.avg_draw_ms,
                 (unsigned long)stats_.video_width, (unsigned long)stats_.video_height,
                 (unsigned long)stats_.frames_decoded, (unsigned long)stats_.frames_dropped);

        /* Notify external FPS callback */
        if (fps_callback_) {
            fps_callback_(fps, stats_.avg_decode_ms, stats_.avg_draw_ms);
        }

        /* Reset counters */
        fps_last_log_time_us_ = now_us;
        fps_frame_count_ = 0;
    }
#endif
}

/* ================================================================== */
/*  JPEG decoding (MJPEG → RGB565)                                    */
/* ================================================================== */

bool VideoPlayer::DecodeMjpegFrame(const uint8_t* jpeg_data, size_t jpeg_size,
                                    uint16_t width, uint16_t height) {
    if (!jpeg_dec_ || !jpeg_io_ || !jpeg_hdr_) return false;

    /* Validate frame fits in our buffer */
    size_t needed = static_cast<size_t>(width) * height * sizeof(uint16_t);
    if (needed > frame_buf_size_) {
        ESP_LOGE(TAG, "Frame %ux%u exceeds buffer (%zu > %zu)",
                 width, height, needed, frame_buf_size_);
        return false;
    }

    /* Set up JPEG decoder I/O */
    memset(jpeg_io_, 0, sizeof(jpeg_dec_io_t));
    memset(jpeg_hdr_, 0, sizeof(jpeg_dec_header_info_t));

    jpeg_io_->inbuf     = const_cast<uint8_t*>(jpeg_data);
    jpeg_io_->inbuf_len = static_cast<int>(jpeg_size);

    /* Parse JPEG header to validate dimensions */
    jpeg_error_t jerr = jpeg_dec_parse_header(jpeg_dec_, jpeg_io_, jpeg_hdr_);
    if (jerr != JPEG_ERR_OK) {
        ESP_LOGW(TAG, "JPEG header parse failed: %d", jerr);
        return false;
    }

    /* Set output buffer (back-buffer) -- must be 16-byte aligned */
    jpeg_io_->outbuf = reinterpret_cast<uint8_t*>(frame_buf_[back_buf_index_]);

    /* Advance inbuf past consumed header bytes */
    int consumed = jpeg_io_->inbuf_len - jpeg_io_->inbuf_remain;
    jpeg_io_->inbuf     = const_cast<uint8_t*>(jpeg_data) + consumed;
    jpeg_io_->inbuf_len = jpeg_io_->inbuf_remain;

    /* Decode JPEG → RGB565 */
    jerr = jpeg_dec_process(jpeg_dec_, jpeg_io_);
    if (jerr != JPEG_ERR_OK) {
        ESP_LOGW(TAG, "JPEG decode failed: %d", jerr);
        return false;
    }

    return true;
}

/* ================================================================== */
/*  LCD rendering (direct panel draw, bypasses LVGL)                  */
/* ================================================================== */

void VideoPlayer::DrawFrameToLcd(uint16_t vw, uint16_t vh) {
    if (!lcd_panel_) return;

    /*
     * Center the video frame on the LCD if the video is smaller than the
     * display. If video is larger, it is clipped to LCD dimensions.
     */
    uint16_t draw_w = std::min(vw, lcd_width_);
    uint16_t draw_h = std::min(vh, lcd_height_);
    int x_offset = (lcd_width_  - draw_w) / 2;
    int y_offset = (lcd_height_ - draw_h) / 2;

    int64_t draw_start = esp_timer_get_time();

    for (uint16_t row = 0; row < draw_h; row += VIDEO_AVI_BUFFER_LINES) {
        int x_start = x_offset;
        int y_start = y_offset + row;
        int x_end = x_offset + draw_w;
        int y_end = std::min(y_offset + row + VIDEO_AVI_BUFFER_LINES,
                                y_offset + draw_h);
        uint16_t* src_row = frame_buf_[back_buf_index_] + (row * vw);
        esp_lcd_panel_draw_bitmap(lcd_panel_,
                                    x_start, y_start,
                                    x_end, y_end,
                                    src_row);
    }

    int64_t draw_end = esp_timer_get_time();
    float draw_ms = static_cast<float>(draw_end - draw_start) / 1000.0f;

    /* Update draw time in stats (running average) */
    {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        float n = static_cast<float>(stats_.frames_decoded);
        if (n > 0) {
            stats_.avg_draw_ms = stats_.avg_draw_ms * ((n - 1.0f) / n) + draw_ms / n;
        }
    }
}

/* ================================================================== */
/*  LVGL canvas rendering (goes through LVGL refresh pipeline)        */
/* ================================================================== */

void VideoPlayer::CreateVideoCanvas() {
    if (!display_) {
        ESP_LOGW(TAG, "Cannot create canvas: no Display* provided");
        return;
    }

    /* Destroy any existing canvas first */
    DestroyVideoCanvas();

    /* Allocate canvas buffer in PSRAM (full LCD size, RGB565) */
    size_t buf_size = static_cast<size_t>(lcd_width_) * lcd_height_ * sizeof(uint16_t);
    canvas_buf_ = static_cast<uint16_t*>(
        heap_caps_malloc(buf_size, MALLOC_CAP_SPIRAM | MALLOC_CAP_8BIT));
    if (!canvas_buf_) {
        ESP_LOGE(TAG, "Failed to allocate canvas buffer (%zu bytes)", buf_size);
        return;
    }
    memset(canvas_buf_, 0, buf_size);

    /* Create LVGL canvas (must hold display lock for LVGL calls) */
    {
        DisplayLockGuard lock(display_);
        lv_obj_t* canvas = lv_canvas_create(lv_scr_act());
        lv_canvas_set_buffer(canvas, canvas_buf_, lcd_width_, lcd_height_,
                             LV_COLOR_FORMAT_RGB565);
        lv_obj_set_pos(canvas, 0, 0);
        lv_obj_set_size(canvas, lcd_width_, lcd_height_);
        lv_canvas_fill_bg(canvas, lv_color_black(), LV_OPA_COVER);
        lv_obj_move_foreground(canvas);
        video_canvas_ = canvas;
    }

    ESP_LOGI(TAG, "Video canvas created: %dx%d (%zu bytes)",
             lcd_width_, lcd_height_, buf_size);
}

void VideoPlayer::DestroyVideoCanvas() {
    if (video_canvas_ && display_) {
        DisplayLockGuard lock(display_);
        lv_obj_del(static_cast<lv_obj_t*>(video_canvas_));
        video_canvas_ = nullptr;
    }
    if (canvas_buf_) {
        heap_caps_free(canvas_buf_);
        canvas_buf_ = nullptr;
    }
}

void VideoPlayer::DrawFrameToCanvas(uint16_t vw, uint16_t vh) {
    if (!display_ || !video_canvas_ || !canvas_buf_) return;

    lv_obj_t* canvas = static_cast<lv_obj_t*>(video_canvas_);
    uint16_t* src = frame_buf_[back_buf_index_];

    /* Determine draw area (center video if smaller than LCD) */
    uint16_t draw_w = std::min(vw, lcd_width_);
    uint16_t draw_h = std::min(vh, lcd_height_);
    int x_offset = (lcd_width_  - draw_w) / 2;
    int y_offset = (lcd_height_ - draw_h) / 2;

    int64_t draw_start = esp_timer_get_time();

    /*
     * Copy decoded RGB565 frame data into the LVGL canvas buffer.
     * If the video exactly matches LCD size, use a single fast memcpy.
     * Otherwise, center the frame and clear the border regions.
     */
    if (x_offset == 0 && draw_w == lcd_width_ &&
        y_offset == 0 && draw_h == lcd_height_) {
        memcpy(canvas_buf_, src, draw_w * draw_h * sizeof(uint16_t));
    } else {
        /* Clear entire canvas to black */
        memset(canvas_buf_, 0,
               static_cast<size_t>(lcd_width_) * lcd_height_ * sizeof(uint16_t));
        /* Copy video rows with offset */
        for (uint16_t row = 0; row < draw_h; row++) {
            uint16_t* dst_row = canvas_buf_ + (y_offset + row) * lcd_width_ + x_offset;
            uint16_t* src_row = src + row * vw;
            memcpy(dst_row, src_row, draw_w * sizeof(uint16_t));
        }
    }

    /* Invalidate canvas so LVGL redraws it on next refresh cycle */
    {
        DisplayLockGuard lock(display_);
        lv_obj_invalidate(canvas);
    }

    int64_t draw_end = esp_timer_get_time();
    float draw_ms = static_cast<float>(draw_end - draw_start) / 1000.0f;

    /* Update draw time in stats */
    {
        std::lock_guard<std::mutex> lock(stats_mutex_);
        float n = static_cast<float>(stats_.frames_decoded);
        if (n > 0) {
            stats_.avg_draw_ms = stats_.avg_draw_ms * ((n - 1.0f) / n) + draw_ms / n;
        }
    }
}

/* ================================================================== */
/*  Render mode switching                                             */
/* ================================================================== */

void VideoPlayer::SetRenderMode(VideoRenderMode mode) {
    if (mode == render_mode_) return;

    VideoRenderMode old_mode = render_mode_;
    render_mode_ = mode;

    /* Re-initialize JPEG decoder for the new pixel format */
    if (initialized_.load()) {
        if (!InitJpegDecoder(mode)) {
            ESP_LOGE(TAG, "Failed to reinit JPEG decoder for new mode, reverting");
            render_mode_ = old_mode;
            InitJpegDecoder(old_mode);
            return;
        }
    }

    ESP_LOGI(TAG, "Render mode: %s -> %s",
             old_mode == VideoRenderMode::DirectLcd ? "DirectLcd" : "LvglCanvas",
             mode == VideoRenderMode::DirectLcd ? "DirectLcd" : "LvglCanvas");
}

/* ================================================================== */
/*  Render task (independent from AVI demuxer task)                   */
/* ================================================================== */

void VideoPlayer::RenderTaskEntry(void* arg) {
    auto* self = static_cast<VideoPlayer*>(arg);
    self->RenderTaskLoop();
}

void VideoPlayer::RenderTaskLoop() {
    ESP_LOGI(TAG, "Render task started on core %d", xPortGetCoreID());
    render_task_running_.store(true);

    while (true) {
        /* Wait for a new frame signal (or timeout to check exit flag) */
        if (xSemaphoreTake(render_sem_, pdMS_TO_TICKS(100)) != pdTRUE) {
            if (render_exit_.load()) break;
            continue;
        }

        if (render_exit_.load()) break;
        if (stop_requested_.load() || paused_.load()) continue;

        int64_t decode_start = esp_timer_get_time();

        /* Atomically copy pending MJPEG data to local decode buffer.
         * The mutex is held only during the fast memcpy, so the AVI
         * callback is blocked at most ~1 ms (not during decode/draw). */
        size_t mjpeg_size;
        uint16_t vw, vh;

        xSemaphoreTake(mjpeg_mutex_, portMAX_DELAY);
        mjpeg_size = pending_mjpeg_size_;
        vw = pending_frame_w_;
        vh = pending_frame_h_;
        if (mjpeg_size > 0 && mjpeg_size <= VIDEO_AVI_BUFFER_SIZE) {
            memcpy(decode_mjpeg_buf_, pending_mjpeg_buf_, mjpeg_size);
        }
        xSemaphoreGive(mjpeg_mutex_);

        if (mjpeg_size == 0) continue;

        /* Decode MJPEG -> RGB565 into the back-buffer */
        bool decoded = DecodeMjpegFrame(decode_mjpeg_buf_, mjpeg_size, vw, vh);

        if (decoded) {
            /* Hook for subclass customization */
            OnVideoFrameReady(frame_buf_[back_buf_index_], vw, vh);

            /* Notify external frame callback */
            if (frame_callback_) {
                frame_callback_(frame_buf_[back_buf_index_], vw, vh);
            }

            /* Render based on current mode */
            if (render_mode_ == VideoRenderMode::LvglCanvas) {
                DrawFrameToCanvas(vw, vh);
            } else {
                DrawFrameToLcd(vw, vh);
            }

            /* Swap back-buffer for next frame */
            back_buf_index_ = 1 - back_buf_index_;

            /* Update statistics */
            int64_t decode_end = esp_timer_get_time();
            float decode_ms = static_cast<float>(decode_end - decode_start) / 1000.0f;
            {
                std::lock_guard<std::mutex> lock(stats_mutex_);
                stats_.frames_decoded++;
                stats_.video_width  = vw;
                stats_.video_height = vh;
                float n = static_cast<float>(stats_.frames_decoded);
                stats_.avg_decode_ms = stats_.avg_decode_ms * ((n - 1.0f) / n)
                                     + decode_ms / n;
            }

            /* Periodic FPS logging */
            LogFpsStats();
        } else {
            std::lock_guard<std::mutex> lock(stats_mutex_);
            stats_.frames_dropped++;
        }
    }

    render_task_running_.store(false);
    ESP_LOGI(TAG, "Render task exiting");
    vTaskDelete(NULL);
}

/* ================================================================== */
/*  Audio output (PCM → AudioCodec I2S)                               */
/* ================================================================== */

void VideoPlayer::OutputAudioPcm(const uint8_t* pcm_data, size_t data_bytes,
                                  uint8_t channels, uint8_t bits_per_sample) {
    if (!audio_codec_ || !pcm_data || data_bytes == 0) return;

    /* Currently only 16-bit PCM is supported */
    if (bits_per_sample != 16) {
        ESP_LOGW(TAG, "Unsupported audio bits: %u", bits_per_sample);
        return;
    }

    size_t num_samples = data_bytes / sizeof(int16_t);
    const int16_t* src = reinterpret_cast<const int16_t*>(pcm_data);

    /* Build output vector for AudioCodec (expects interleaved int16_t) */
    std::vector<int16_t> audio_out;

    int codec_channels = audio_codec_->output_channels();

    if (channels == 1 && codec_channels >= 2) {
        /*
         * Mono video audio → stereo codec output.
         * Duplicate each sample for left and right channels.
         */
        audio_out.resize(num_samples * 2);
        for (size_t i = 0; i < num_samples; i++) {
            int32_t sample = static_cast<int32_t>(src[i]);
            /* Apply volume scaling */
            sample = static_cast<int32_t>(sample * volume_factor_);
            /* Clamp to int16 range */
            if (sample > 32767) sample = 32767;
            if (sample < -32768) sample = -32768;
            audio_out[i * 2]     = static_cast<int16_t>(sample);
            audio_out[i * 2 + 1] = static_cast<int16_t>(sample);
        }
    } else if (channels == 2 && codec_channels == 1) {
        /*
         * Stereo video audio → mono codec output.
         * Average left and right channels.
         */
        size_t frames = num_samples / 2;
        audio_out.resize(frames);
        for (size_t i = 0; i < frames; i++) {
            int32_t left  = static_cast<int32_t>(src[i * 2]);
            int32_t right = static_cast<int32_t>(src[i * 2 + 1]);
            int32_t mixed = (left + right) / 2;
            mixed = static_cast<int32_t>(mixed * volume_factor_);
            if (mixed > 32767) mixed = 32767;
            if (mixed < -32768) mixed = -32768;
            audio_out[i] = static_cast<int16_t>(mixed);
        }
    } else {
        /*
         * Same channel count -- direct pass-through with volume.
         */
        audio_out.resize(num_samples);
        for (size_t i = 0; i < num_samples; i++) {
            int32_t sample = static_cast<int32_t>(src[i]);
            sample = static_cast<int32_t>(sample * volume_factor_);
            if (sample > 32767) sample = 32767;
            if (sample < -32768) sample = -32768;
            audio_out[i] = static_cast<int16_t>(sample);
        }
    }

    /* Send to codec -- blocking until I2S DMA accepts the data */
    audio_codec_->OutputData(audio_out);
}

/* ================================================================== */
/*  State management                                                  */
/* ================================================================== */

void VideoPlayer::SetState(VideoPlayerState new_state) {
    VideoPlayerState old_state = state_.exchange(new_state);
    if (old_state != new_state) {
        ESP_LOGD(TAG, "State: %d → %d",
                 static_cast<int>(old_state), static_cast<int>(new_state));

        switch (new_state)
        {
        case VideoPlayerState::Idle:
            if (render_mode_ == VideoRenderMode::LvglCanvas) {
                DestroyVideoCanvas();
            }
            break;
        case VideoPlayerState::Loading:
            if (render_mode_ == VideoRenderMode::LvglCanvas) {
                CreateVideoCanvas();
            }
            break;
        case VideoPlayerState::Playing:
            break;
        case VideoPlayerState::Paused:
            break;
        case VideoPlayerState::Stopping:
            break;
        case VideoPlayerState::Error:
            if (render_mode_ == VideoRenderMode::LvglCanvas) {
                DestroyVideoCanvas();
            }
             break;
        default:
            break;
        }

        /* Virtual hook */
        OnPlaybackStateChanged(old_state, new_state);

        /* User callback (with both old and new state) */
        if (state_callback_) {
            state_callback_(old_state, new_state);
        }
    }
}

/* ================================================================== */
/*  Path utilities                                                    */
/* ================================================================== */

std::string VideoPlayer::BuildFullPath(const std::string& filename) const {
    return std::string(mount_point_) + "/" + video_directory_ + "/" + filename;
}
