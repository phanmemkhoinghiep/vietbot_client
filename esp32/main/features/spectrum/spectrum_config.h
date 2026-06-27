/**
 * @file spectrum_config.h
 * @brief Configuration structures and constants for the spectrum analyzer component.
 *
 * Provides a single configuration struct that parameterizes the FFT engine,
 * the bar renderer, the FreeRTOS task, and the LVGL canvas layout.
 * All values have sensible defaults for an ESP32-S3 with 320×240 LCD.
 *
 * Contributors: Xiaozhi AI-IoT Vietnam Team
 */
#pragma once

#include <cstdint>

namespace spectrum {

/**
 * @brief Complete configuration for SpectrumAnalyzer + SpectrumRenderer.
 *
 * Users should create a SpectrumConfig, adjust the fields they care about,
 * then pass it to SpectrumManager::Start().
 */
struct SpectrumConfig {
    // ---- FFT Parameters ----
    int fft_size            = 512;   ///< FFT window size (must be power of 2)
    int accumulate_frames   = 3;     ///< PCM frames accumulated before each FFT cycle
    int audio_frame_size    = 1152;  ///< Samples per audio frame (MP3 standard)

    // ---- Display Parameters ----
    int bar_count           = 40;    ///< Number of spectrum bars
    int canvas_x            = 0;     ///< Canvas X position on screen (pixels)
    int canvas_y            = 0;     ///< Canvas Y position on screen (pixels)
    int canvas_width        = 320;   ///< Canvas width  (pixels)
    int canvas_height       = 240;   ///< Canvas height (pixels)
    int lcd_width           = 320;   ///< LCD width (pixels) — for canvas positioning
    int lcd_height          = 240;   ///< LCD height (pixels) — for canvas positioning
    int status_bar_h        = 24;    ///< Status bar height (pixels) — for UI layout fallback
    int bar_max_height      = 0;     ///< Max bar height (0 = canvas_height / 2)

    // ---- Display Mode ----
    bool monochrome         = false; ///< true for I1 (1-bit OLED); false for RGB565 (LCD)

    // ---- Timing Parameters ----
    int refresh_rate_hz             = 30;  ///< LVGL refresh rate for spectrum (Hz)
    int audio_process_interval_ms   = 10;  ///< Audio processing interval (ms)

    // ---- FreeRTOS Task Parameters ----
    int task_stack_size     = 4 * 1024;  ///< Stack size in bytes
    int task_priority       = 1;         ///< Task priority
    int task_core           = 0;         ///< CPU core affinity

    /**
     * @brief Effective maximum bar height.
     * @return Explicit bar_max_height, or canvas_height / 2 when left at 0.
     */
    int GetBarMaxHeight() const {
        return (bar_max_height > 0) ? bar_max_height : (canvas_height / 2);
    }
};

} // namespace spectrum
